package com.example.agent.patterns.compiler;

import com.example.agent.llm.LlmClient;
import com.example.agent.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模式 4：LLMCompiler（并行 Function Calling）
 *
 * <p>论文《An LLM Compiler for Parallel Function Calling》。
 * 核心思想：Planner 生成的不再是线性计划，而是一张 DAG
 * （有向无环图），调度器把「依赖已全部就绪」的任务并发提交到线程池，
 * 从而提高执行效率。例如问「张译和吴京相差几岁」，两次搜索可以同时进行，
 * 最后由 Joiner 合并结果。</p>
 *
 * <p>计划行格式：{@code 任务id | 描述 | 工具名[参数] | 依赖id列表(逗号分隔，可为空)}；
 * 合并任务把工具名写为 {@code Join}。</p>
 */
public class LlmCompilerAgent {

    private static final Pattern TASK_PATTERN = Pattern.compile(
            "(\\d+)\\s*\\|\\s*(.*?)\\s*\\|\\s*([A-Za-z0-9_]+)\\[(.*)\\]\\s*\\|\\s*([\\d,\\s]*)");

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final ExecutorService pool;

    public LlmCompilerAgent(LlmClient llm, ToolRegistry tools) {
        this(llm, tools, Executors.newCachedThreadPool());
    }

    public LlmCompilerAgent(LlmClient llm, ToolRegistry tools, ExecutorService pool) {
        this.llm = llm;
        this.tools = tools;
        this.pool = pool;
    }

    /** DAG 任务节点。 */
    record Task(int id, String description, String toolName, String args, List<Integer> deps) {
        boolean isJoin() {
            return toolName.equals("Join");
        }
    }

    public String run(String question) {
        // ---- Planner：生成 DAG ----
        String planOutput = llm.complete("""
                请把用户的问题拆解为一张任务 DAG（有向无环图），尽量识别出可以并行执行的任务。
                每行一个任务，格式：任务id | 描述 | 工具名[参数] | 依赖任务id(逗号分隔，无依赖留空)
                合并/汇总任务使用 Join[汇总说明]。
                可用工具：
                %s
                问题：%s
                任务DAG：""".formatted(tools.describe(), question));
        log("\n[Planner 生成的 DAG]\n" + planOutput);

        List<Task> dag = parseDag(planOutput);

        // ---- 调度器：依赖就绪的任务并行执行 ----
        Map<Integer, String> results = new LinkedHashMap<>();
        List<Task> pending = new ArrayList<>(dag);
        while (!pending.isEmpty()) {
            List<Task> ready = pending.stream()
                    .filter(t -> results.keySet().containsAll(t.deps()))
                    .toList();
            if (ready.isEmpty()) {
                throw new IllegalStateException("DAG 中存在环或不可满足的依赖: " + pending);
            }
            List<Future<?>> futures = new ArrayList<>();
            for (Task task : ready) {
                futures.add(pool.submit(() -> {
                    if (task.isJoin()) {
                        return; // Join 任务在全部依赖完成后统一由 Joiner 处理
                    }
                    String result = tools.find(task.toolName())
                            .map(tool -> tool.observe(task.args()))
                            .orElse("工具未注册: " + task.toolName());
                    synchronized (results) {
                        results.put(task.id(), result);
                    }
                    log("[Worker 任务 " + task.id() + "] " + task.toolName() + "[" + task.args() + "] -> " + result);
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("任务执行被中断", e);
                } catch (ExecutionException e) {
                    throw new IllegalStateException("任务执行失败", e.getCause());
                }
            }
            pending.removeAll(ready);
        }

        // ---- Joiner：合并结果 ----
        StringBuilder joined = new StringBuilder();
        for (Map.Entry<Integer, String> e : results.entrySet()) {
            joined.append("任务 ").append(e.getKey()).append(" 结果: ").append(e.getValue()).append('\n');
        }
        Task joinTask = dag.stream().filter(Task::isJoin).findFirst().orElse(null);
        if (joinTask != null) {
            log("\n[Joiner] " + joinTask.description() + "（" + joinTask.args() + "）");
        }
        String finalAnswer = llm.complete("""
                请合并以下并行任务的结果，回答用户的问题。

                问题：%s
                %s
                最终答案：""".formatted(question, joined));
        log("[最终答案] " + finalAnswer);
        return finalAnswer;
    }

    static List<Task> parseDag(String output) {
        List<Task> tasks = new ArrayList<>();
        for (String line : output.split("\n")) {
            Matcher matcher = TASK_PATTERN.matcher(line.strip());
            if (matcher.find()) {
                List<Integer> deps = new ArrayList<>();
                if (!matcher.group(5).isBlank()) {
                    for (String d : matcher.group(5).split(",")) {
                        deps.add(Integer.parseInt(d.trim()));
                    }
                }
                tasks.add(new Task(Integer.parseInt(matcher.group(1)), matcher.group(2),
                        matcher.group(3), matcher.group(4).trim(), deps));
            }
        }
        if (tasks.isEmpty()) {
            throw new IllegalStateException("无法解析 DAG: " + output);
        }
        return tasks;
    }

    private static void log(String message) {
        System.out.println(message);
    }
}
