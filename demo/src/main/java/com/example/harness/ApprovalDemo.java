package com.example.harness;

import com.example.langgraph4j.llm.CountingLlm;
import com.example.langgraph4j.llm.ScriptedLlm;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 审批策略演示：动作按风险分级，写操作挂起等人工、高危操作直接拒、读操作直通。
 *
 * <p>图结构：fetch（读 CRM）→ propose（确定拟执行动作）→ 条件边按
 * {@link ApprovalPolicy} 分流：FORBIDDEN → END；AUTO → apply；
 * NEEDS_APPROVAL → await_approval（编译期 {@code interruptBefore} 布点，
 * 到此挂起，人工确认后 {@code GraphInput.resume()} 恢复）→ apply → report。
 * 审批通过的动作在 apply 里临时提升权限执行（一事一议授权，网关之外的双保险）。</p>
 *
 * <p>三种跑法（同一策略表）：专员更新预测 → 挂起 → 模拟经理批准 → 放行；
 * 专员全量导出 → 策略直接拒，不浪费一次人工；只查 CRM → 直通不等审批。
 * 运行入口：{@code mvn exec:java@approval}。</p>
 */
public class ApprovalDemo {

    // ---- State ----
    public static final class ApprovalState extends AgentState {
        static final Map<String, Channel<?>> SCHEMA = Map.of(
                "task", Channels.base(() -> ""),
                "crm", Channels.base(() -> ""),
                "action", Channels.base(() -> ""),
                "approved", Channels.base(() -> false),
                "applyResult", Channels.base(() -> ""),
                "verdict", Channels.base(() -> ""));

        ApprovalState(Map<String, Object> initData) {
            super(initData);
        }

        String task() {
            return this.<String>value("task").orElse("");
        }

        String action() {
            return this.<String>value("action").orElse("");
        }

        boolean approved() {
            return this.<Boolean>value("approved").orElse(false);
        }
    }

    private static final String FETCH = "fetch";
    private static final String PROPOSE = "propose";
    private static final String AWAIT = "await_approval";
    private static final String APPLY = "apply";
    private static final String REPORT = "report";

    /** 动作 → 所需工具权限，apply 节点过 ToolGate 时用。 */
    private static final Map<String, ToolGate.Level> ACTION_LEVEL = Map.of(
            "read_crm", ToolGate.Level.READ,
            "update_forecast", ToolGate.Level.WRITE,
            "export_all_data", ToolGate.Level.ADMIN);

    private final ApprovalPolicy policy;
    private final ToolGate gate;
    private final CountingLlm llm;

    public ApprovalDemo(ApprovalPolicy policy, ToolGate gate, CountingLlm llm) {
        this.policy = policy;
        this.gate = gate;
        this.llm = llm;
    }

    Map<String, Object> fetch(ApprovalState state) {
        String crm = gate.callUnchecked("read_crm", ToolGate.Level.READ,
                "CRM 记录：Acme 上季度续约率 80%，本月新增 3 个坐席");
        System.out.println("  [fetch] 读取 CRM（READ，自动放行）");
        return Map.of("crm", crm);
    }

    /** 剧本化的「模型决策」：从任务文本确定拟执行动作。 */
    Map<String, Object> propose(ApprovalState state) {
        String task = state.task();
        String action = task.contains("导出") ? "export_all_data"
                : task.contains("预测") ? "update_forecast" : "read_crm";
        ApprovalPolicy.Risk risk = policy.of(action);
        System.out.println("  [propose] 拟执行动作 " + action + " → 策略评级 " + risk);
        return Map.of("action", action);
    }

    /** 条件边：策略分流。挂起节点 await_approval 由编译期 interruptBefore 布点。 */
    String routeAfterPropose(ApprovalState state) {
        ApprovalPolicy.Risk risk = policy.of(state.action());
        if (risk == ApprovalPolicy.Risk.FORBIDDEN) {
            System.out.println("  [路由] " + state.action() + " 策略禁止 → 直接终止（不浪费一次人工）");
            return StateGraph.END;
        }
        if (risk == ApprovalPolicy.Risk.NEEDS_APPROVAL) {
            System.out.println("  [路由] " + state.action() + " 需要审批 → 挂起等待人工");
            return AWAIT;
        }
        System.out.println("  [路由] " + state.action() + " 自动放行 → 执行");
        return APPLY;
    }

    /** 挂起节点（interruptBefore 布点）：resume 后执行到这里 = 人工已批准。 */
    Map<String, Object> awaitApproval(ApprovalState state) {
        System.out.println("  [await_approval] 人工已批准 " + state.action() + " → 继续执行");
        return Map.of("approved", true);
    }

    /** 执行动作：审批通过的一事一议授权临时提升权限；未审批的走角色网关。 */
    Map<String, Object> apply(ApprovalState state) {
        String action = state.action();
        ToolGate.Level required = ACTION_LEVEL.getOrDefault(action, ToolGate.Level.ADMIN);
        String result;
        if (state.approved()) {
            result = "经审批提升权限执行 " + action + "：完成";
            System.out.println("  [apply] " + action + "（一事一议授权，已留痕）");
        } else {
            result = gate.callUnchecked(action, required, action + " 已执行");
            System.out.println("  [apply] " + action + "（角色 " + gate.role() + " 网关校验）");
        }
        return Map.of("applyResult", result, "verdict",
                state.approved() ? "APPROVED_DONE" : "AUTO_DONE");
    }

    Map<String, Object> report(ApprovalState state) {
        String text = llm.complete("""
                写一句任务汇报：已查 CRM；动作 %s 的处理结果是：%s。
                只输出汇报本身。""".formatted(state.action(), state.value("applyResult").orElse("")));
        System.out.println("  [report] " + text.strip());
        return Map.of("verdict", state.value("verdict").orElse(""));
    }

    public StateGraph<ApprovalState> build() throws Exception {
        return new StateGraph<>(ApprovalState.SCHEMA, ApprovalState::new)
                .addNode(FETCH, node_async(this::fetch))
                .addNode(PROPOSE, node_async(this::propose))
                .addNode(AWAIT, node_async(this::awaitApproval))
                .addNode(APPLY, node_async(this::apply))
                .addNode(REPORT, node_async(this::report))
                .addEdge(StateGraph.START, FETCH)
                .addEdge(FETCH, PROPOSE)
                .addConditionalEdges(PROPOSE, edge_async(this::routeAfterPropose),
                        Map.of(AWAIT, AWAIT, APPLY, APPLY, StateGraph.END, StateGraph.END))
                .addEdge(AWAIT, APPLY)
                .addEdge(APPLY, REPORT)
                .addEdge(REPORT, StateGraph.END);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(66));
        System.out.println("  审批策略 —— 按风险分级：写操作挂起等人工，高危直接拒");
        System.out.println("=".repeat(66));
        System.out.println("""
                  策略表：read_crm=AUTO  update_forecast=NEEDS_APPROVAL  export_all_data=FORBIDDEN
                """);

        ApprovalPolicy policy = ApprovalPolicy.of(Map.of(
                "read_crm", ApprovalPolicy.Risk.AUTO,
                "update_forecast", ApprovalPolicy.Risk.NEEDS_APPROVAL,
                "export_all_data", ApprovalPolicy.Risk.FORBIDDEN), ApprovalPolicy.Risk.NEEDS_APPROVAL);
        RunStore store = new RunStore(Path.of("runs", "runs.jsonl"));

        // 跑法 1：专员要更新预测 → 挂起 → 模拟经理批准 → 放行
        runWithApproval(policy, store);
        // 跑法 2：专员要全量导出 → 策略直接拒
        runForbidden(policy, store);
        // 跑法 3：只查 CRM → 直通
        runAuto(policy, store);

        System.out.println("""
                
                  结论：审批与否是策略决策（可评审、可版本化），不是写死在图里；
                  挂起/恢复靠 checkpoint + interruptBefore，批准是一事一议授权并留痕。
                """);
        System.exit(0);
    }

    private static void runWithApproval(ApprovalPolicy policy, RunStore store) throws Exception {
        System.out.println("── 跑法 1：专员（READ）要更新预测 → 挂起等审批 ──");
        var llm = new CountingLlm(new ScriptedLlm().says("已查 CRM，预测更新已获批准并完成。"));
        var demo = new ApprovalDemo(policy, new ToolGate("专员", ToolGate.Level.READ), llm);
        var compiled = demo.build().compile(CompileConfig.builder()
                .checkpointSaver(new MemorySaver()).interruptBefore(AWAIT).build());
        var config = RunnableConfig.builder().threadId("approval-1").build();

        long t0 = System.nanoTime();
        var interrupted = compiled.invoke(GraphInput.args(Map.of(
                "task", "查 Acme 客户记录，并更新本季度销售预测")), config);
        interrupted.ifPresent(s -> System.out.println("  [挂起] 等待人工审批，拟执行动作："
                + s.<String>value("action").orElse("") + "（checkpoint 已存，可回滚）"));

        System.out.println("  [人工审批] 模拟经理批准 → 恢复运行");
        var resumed = compiled.invoke(GraphInput.resume(), config);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        String verdict = resumed.map(s -> s.<String>value("verdict").orElse("")).orElse("");
        System.out.println("  → 终态=" + verdict + "\n");
        store.append(RunRecord.ofGraph("demo-approval-approved", "更新预测",
                List.of("fetch", "propose", "await_approval(挂起→批准)", "apply", "report"),
                verdict, Optional.empty(), llm.calls(), ms));
    }

    private static void runForbidden(ApprovalPolicy policy, RunStore store) throws Exception {
        System.out.println("── 跑法 2：专员（READ）要全量导出 → 策略禁止 ──");
        var llm = new CountingLlm(new ScriptedLlm().says("已查 CRM；全量导出被策略禁止。"));
        var demo = new ApprovalDemo(policy, new ToolGate("专员", ToolGate.Level.READ), llm);
        var compiled = demo.build().compile(CompileConfig.builder()
                .checkpointSaver(new MemorySaver()).interruptBefore(AWAIT).build());
        long t0 = System.nanoTime();
        var end = compiled.invoke(GraphInput.args(Map.of(
                "task", "查 Acme 客户记录，并全量导出客户数据")), RunnableConfig.builder().threadId("approval-2").build());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        String verdict = end.map(s -> s.<String>value("verdict").orElse("")).orElse("");
        System.out.println("  → 终态=" + (verdict.isEmpty() ? "FORBIDDEN（未到达 report 节点）" : verdict) + "\n");
        store.append(RunRecord.ofGraph("demo-approval-forbidden", "全量导出",
                List.of("fetch", "propose", "FORBIDDEN"), "FORBIDDEN", Optional.empty(), llm.calls(), ms));
    }

    private static void runAuto(ApprovalPolicy policy, RunStore store) throws Exception {
        System.out.println("── 跑法 3：专员（READ）只查 CRM → 直通不等审批 ──");
        var llm = new CountingLlm(new ScriptedLlm().says("已查 CRM：续约率 80%。"));
        var demo = new ApprovalDemo(policy, new ToolGate("专员", ToolGate.Level.READ), llm);
        var compiled = demo.build().compile(CompileConfig.builder()
                .checkpointSaver(new MemorySaver()).interruptBefore(AWAIT).build());
        long t0 = System.nanoTime();
        var end = compiled.invoke(GraphInput.args(Map.of(
                "task", "查 Acme 客户记录")), RunnableConfig.builder().threadId("approval-3").build());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        String verdict = end.map(s -> s.<String>value("verdict").orElse("")).orElse("");
        System.out.println("  → 终态=" + verdict + "\n");
        store.append(RunRecord.ofGraph("demo-approval-auto", "只查 CRM",
                List.of("fetch", "propose", "apply", "report"), verdict, Optional.empty(), llm.calls(), ms));
    }
}
