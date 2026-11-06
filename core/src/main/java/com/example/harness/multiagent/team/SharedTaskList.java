package com.example.harness.multiagent.team;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 共享任务清单：全队看得见进度的那块板（OmO Team Mode 研读落地，2026-11-06）。
 *
 * <p>对着 OmO 的 {@code team-tasklist} 与仓库自己的写密集教训压出来
 * 的三条：</p>
 * <ul>
 *   <li><b>认领原子</b>：{@link #claim} 一人得手、另一人抢同一任务
 *       直接 false——PENDING→CLAIMED 只发生一次（写密集互相覆盖
 *       的第一道闸：一块活明确只有一双手）；</li>
 *   <li><b>谁认领谁完成</b>：{@link #complete} 校验 claimedBy，
 *       别人没资格替你把活勾掉；</li>
 *   <li><b>全留痕</b>：任务从挂牌到完工的每次转手都在记录里，
 *       收工审计对着板看就行。</li>
 * </ul>
 */
public final class SharedTaskList {

    /** 任务状态。 */
    public enum Status {
        PENDING, CLAIMED, DONE
    }

    /** 一条任务记录。 */
    public record Task(long id, String title, Status status, String claimedBy) {
    }

    private final List<Task> tasks = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    /** 挂牌。 */
    public synchronized long create(String title) {
        long id = sequence.incrementAndGet();
        tasks.add(new Task(id, title, Status.PENDING, ""));
        return id;
    }

    /** 认领：PENDING→CLAIMED 只发生一次，抢不到返回 false。 */
    public synchronized boolean claim(long id, String member) {
        Task task = require(id);
        if (task.status() != Status.PENDING) {
            return false;
        }
        tasks.set(tasks.indexOf(task),
                new Task(task.id(), task.title(), Status.CLAIMED, member));
        return true;
    }

    /** 完工：只有认领者本人能勾。 */
    public synchronized boolean complete(long id, String member) {
        Task task = require(id);
        if (task.status() != Status.CLAIMED || !task.claimedBy().equals(member)) {
            return false;
        }
        tasks.set(tasks.indexOf(task),
                new Task(task.id(), task.title(), Status.DONE, member));
        return true;
    }

    /** 当前快照。 */
    public synchronized List<Task> list() {
        return List.copyOf(tasks);
    }

    private Task require(long id) {
        return tasks.stream().filter(t -> t.id() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("没有这块活：" + id));
    }
}
