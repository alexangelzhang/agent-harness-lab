package com.example.harness.wake;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 任务板：外部长任务（构建/部署/压测……）的登记处 + 叫醒交换机
 * （OmO「等待即订阅」研读落地，2026-11-05）。
 *
 * <p>OmO 的机制：agent 等构建不轮询——任务终态由基础设施检出
 * （idle 事件 + 稳定性检测），结果作为系统消息注入等待方会话把它叫醒；
 * 另有 loop-detector 拦「轮询烧预算」的循环。本类把这条机制压成可断言的
 * 最小内核：</p>
 * <ul>
 *   <li><b>订阅不轮询</b>：等待方 {@link #await} 挂订阅，任务到终态时
 *       {@link WakeMessage} 恰好投递一次；终态后才来挂的订阅立即补投——
 *       晚到不等下一场。</li>
 *   <li><b>终态先到先定</b>：complete/fail/超时只有一个能成交，之后的
 *       重复上报被吞掉，不重复叫醒。</li>
 *   <li><b>超时兜底</b>：挂牌时声明期限（tick 数），{@link #tick()} 推进
 *       时钟，超期仍 RUNNING 的以 TIMEOUT 叫醒——失败和超时都得有着落，
 *       不许让等待方干等。</li>
 *   <li><b>轮询有护栏</b>：{@link #poll} 是给「实在要查」留的门，按任务
 *       计数，超上限后不再返回真实状态、改回劝退指引，违规次数入账——
 *       对应 OmO 的 loop-detector：轮询烧的是 LLM 轮次，得拦。</li>
 * </ul>
 *
 * <p>时钟是手动的（{@link #tick()}），离线剧本可确定性复现；接真实
 * 时钟是调用方的事，不进这个类。</p>
 */
public final class TaskBoard {

    /** 任务状态。 */
    public enum Status {
        RUNNING, COMPLETED, FAILED, TIMEOUT
    }

    private static final class Task {
        final String id;
        final String name;
        final long deadlineTick;
        Status status = Status.RUNNING;
        String detail = "";
        int polls;
        int pollViolations;
        int wakes;

        Task(String id, String name, long deadlineTick) {
            this.id = id;
            this.name = name;
            this.deadlineTick = deadlineTick;
        }

        boolean terminal() {
            return status != Status.RUNNING;
        }
    }

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<WakeMessage>>> subscribers = new ConcurrentHashMap<>();
    private final int maxPolls;
    private final AtomicInteger sequence = new AtomicInteger();
    private long clock;

    /**
     * @param maxPolls 每个任务允许的轮询上限，超了 {@link #poll} 改回劝退指引
     */
    public TaskBoard(int maxPolls) {
        if (maxPolls < 1) {
            throw new IllegalArgumentException("maxPolls 至少为 1");
        }
        this.maxPolls = maxPolls;
    }

    /** 默认每任务最多轮询 3 次。 */
    public TaskBoard() {
        this(3);
    }

    /**
     * 登记一个外部长任务，返回任务 id。
     *
     * @param timeoutTicks 期限：再过多少个 {@link #tick()} 没终态就按超时叫醒
     */
    public String launch(String name, int timeoutTicks) {
        String id = "task-" + sequence.incrementAndGet();
        tasks.put(id, new Task(id, name, clock + timeoutTicks));
        return id;
    }

    /**
     * 挂订阅：任务到终态时把 {@link WakeMessage} 恰好投递一次。
     * 任务已终态则立即补投（晚到的订阅者不等下一场）。
     */
    public synchronized void await(String taskId, Consumer<WakeMessage> wake) {
        Task task = requireTask(taskId);
        if (task.terminal()) {
            deliver(task, wake);
            return;
        }
        subscribers.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(wake);
    }

    /** 外部上报完成。已终态的任务重复上报被吞掉，返回 false。 */
    public boolean complete(String taskId, String result) {
        return settle(taskId, Status.COMPLETED, result);
    }

    /** 外部上报失败。已终态的任务重复上报被吞掉，返回 false。 */
    public boolean fail(String taskId, String error) {
        return settle(taskId, Status.FAILED, error);
    }

    /**
     * 推进一个时钟格：超期仍 RUNNING 的任务以 TIMEOUT 叫醒。
     *
     * @return 这一格被超时叫醒的任务数
     */
    public synchronized int tick() {
        clock++;
        int timedOut = 0;
        for (Task task : tasks.values()) {
            if (task.status == Status.RUNNING && clock > task.deadlineTick) {
                task.status = Status.TIMEOUT;
                task.detail = "已超期限仍无结果（期限定在第 " + task.deadlineTick + " 格）";
                wakeAll(task);
                timedOut++;
            }
        }
        return timedOut;
    }

    /**
     * 等待方面向的查询——每次调用都计一次轮询；超上限后不再返回真实状态，
     * 改回劝退指引（轮询烧的是 LLM 轮次）。
     */
    public synchronized String poll(String taskId) {
        Task task = requireTask(taskId);
        task.polls++;
        if (task.polls > maxPolls) {
            task.pollViolations++;
            return "轮询已超上限（" + maxPolls + " 次）：等待请用 await(...) 挂订阅，"
                    + "终态会以系统消息叫醒你；继续轮询只是白烧轮次。";
        }
        return task.status.name();
    }

    /** 基础设施面向的查询，不计轮询（检出器/监控走这个口）。 */
    public Status peek(String taskId) {
        return requireTask(taskId).status;
    }

    /** 该任务被等待方轮询了几次（含被拦的）。 */
    public int pollCount(String taskId) {
        return requireTask(taskId).polls;
    }

    /** 该任务轮询超上限被拒了几次。 */
    public int pollViolations(String taskId) {
        return requireTask(taskId).pollViolations;
    }

    /** 该任务累计投递了几条叫醒消息（正常恰为订阅数，重复上报不加）。 */
    public int wakeCount(String taskId) {
        return requireTask(taskId).wakes;
    }

    private boolean settle(String taskId, Status status, String detail) {
        Task task;
        synchronized (this) {
            task = requireTask(taskId);
            if (task.terminal()) {
                return false;
            }
            task.status = status;
            task.detail = detail;
            wakeAll(task);
        }
        return true;
    }

    private void wakeAll(Task task) {
        WakeMessage message = toMessage(task);
        for (Consumer<WakeMessage> wake : subscribers.getOrDefault(task.id, List.of())) {
            task.wakes++;
            wake.accept(message);
        }
    }

    private void deliver(Task task, Consumer<WakeMessage> wake) {
        task.wakes++;
        wake.accept(toMessage(task));
    }

    private static WakeMessage toMessage(Task task) {
        WakeMessage.Reason reason = switch (task.status) {
            case COMPLETED -> WakeMessage.Reason.COMPLETED;
            case FAILED -> WakeMessage.Reason.FAILED;
            case TIMEOUT -> WakeMessage.Reason.TIMEOUT;
            case RUNNING -> throw new IllegalStateException("RUNNING 不该触发叫醒");
        };
        return new WakeMessage(task.id, task.name, reason, task.detail);
    }

    private Task requireTask(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("未知任务：" + taskId);
        }
        return task;
    }
}
