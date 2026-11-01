package com.example.workflow;

import com.example.harness.Budget;
import com.example.langgraph4j.llm.LlmClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 动态工作流引擎：模拟 Claude Code 的 {@code agent()} / {@code parallel()} 能力。
 *
 * <p>出处：claude.dev《每项任务都有一个 harness：Claude Code 中的动态工作流》
 * （docs/dynamic_workflows_claude.md）。核心理念是<strong>模型运行时按眼前的任务
 * 现写 harness</strong>——编排不是预先画死的图，而是生成一批各自独立上下文的
 * 子智能体并把它们组合起来。这与本仓库 {@code com.example.langgraph4j} 里的
 * 静态多智能体模式互补：静态图通用、可复用；动态工作流为单个任务量身定制。</p>
 *
 * <p>三个方法对应文章的三种失效模式：</p>
 * <ul>
 *   <li>{@link #agent}：子智能体只看到自己的提示词，不带主线历史——隔离上下文，
 *       防目标漂移和步骤间交叉污染；每个智能体只盯一个目标，防"做到一半就宣布完成"
 *       的智能体懈怠。</li>
 *   <li>{@link #parallel}：扇出后由调用方做屏障综合，所有结果到齐才合并。</li>
 *   <li>管道（pipeline）不单独建方法：顺序调用 {@code agent()}，把上一步输出拼进
 *       下一步提示词即是管道。</li>
 * </ul>
 *
 * <p>对抗自我偏好偏差靠的是用法而非引擎：验证/评审永远起<strong>全新的</strong>
 * 子智能体做比较式判断（见 DynamicWorkflowDemo 的对抗验证与锦标赛场景），
 * 而不是让原作者给自己的产出打分。</p>
 *
 * <p>治理层挂钩（生产级底线，见 harness 包）：可选传入 {@link Budget}——
 * 动态编排会起多少子智能体是模型现场决定的，比静态图更需要油表；
 * 每次 spawn 前检查预算，耗尽抛 {@link BudgetExhausted} 显式收场转人工，
 * 而不是悄悄跑完（同 AgentLoop 的第四种终态语义）。每次 spawn 同时进
 * {@link #trace}，供 {@link com.example.harness.RunRecord} 落盘审计。</p>
 */
public class Workflow {

    private final LlmClient model;
    private final Budget budget;
    private int spawned;
    private final List<AgentCall> trace = new ArrayList<>();

    /** @param model 扮演"会写 harness 的模型"，每个子智能体由它的一次 complete() 驱动。 */
    public Workflow(LlmClient model) {
        this(model, null);
    }

    /** 带预算的工作流；{@code budget} 为 null 表示不限（演示默认）。 */
    public Workflow(LlmClient model, Budget budget) {
        this.model = model;
        this.budget = budget;
    }

    /**
     * 生成一个独立上下文的子智能体：它只看到自己的提示词，
     * 看不到主线对话历史，也不记得上一个子智能体说过什么。
     *
     * <p>spawn 前检查预算：耗尽抛 {@link BudgetExhausted}（按检查点结算，
     * 一次 complete 本身总会跑完）。</p>
     *
     * @return 该子智能体的输出
     */
    public String agent(String name, String prompt) {
        if (budget != null && budget.exhausted()) {
            throw new BudgetExhausted("预算耗尽（" + budget.describe() + "），转人工");
        }
        spawned++;
        long t0 = System.nanoTime();
        String reply = model.complete("[子智能体 " + name + "]\n" + prompt);
        trace.add(new AgentCall(name, reply, (System.nanoTime() - t0) / 1_000_000));
        return reply;
    }

    /** 一次子智能体调用留痕：名字、输出、耗时。供 RunRecord 审计。 */
    public record AgentCall(String name, String output, long durationMs) {
    }

    /** 本次工作流已发生的子智能体调用，按序。 */
    public List<AgentCall> trace() {
        return List.copyOf(trace);
    }

    /** 预算耗尽：第四种终态的载体，由调用方捕获后记 BUDGET_EXHAUSTED。 */
    public static final class BudgetExhausted extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BudgetExhausted(String message) {
            super(message);
        }
    }

    /**
     * 扇出：同一批子智能体各处理一份提示词，按传入顺序返回结果。
     * 演示用普通循环顺序执行，效果等价于并行——每个智能体的上下文彼此隔离。
     * 综合是调用方的责任：拿到全部结果（屏障）后再起一个新的智能体合并。
     */
    public List<String> parallel(String name, String... prompts) {
        List<String> results = new ArrayList<>();
        for (String prompt : prompts) {
            results.add(agent(name, prompt));
        }
        return results;
    }

    /** 本次工作流已生成的子智能体数——呼应文章的 token 预算提示。 */
    public int spawned() {
        return spawned;
    }
}
