package com.example.harness;

import com.example.agentloop.AgentLoop;
import com.example.agentloop.LlmVerifier;
import com.example.agentloop.LoopResult;
import com.example.langgraph4j.llm.CountingLlm;
import com.example.langgraph4j.llm.ScriptedLlm;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具权限网关演示：同一个销售助手任务，三种角色三种结局。
 *
 * <p>任务：查 Acme 客户记录并更新季度预测。工具权限：读 CRM（READ）、
 * 更新预测（WRITE）、全量导出（ADMIN）。经理（WRITE）全程畅通；
 * 专员（READ）更新预测时被网关拦下——DENIED 回到 agent 手里改提建议，
 * 越权尝试留痕进轨迹和档案；经理调 ADMIN 工具同样被拒（级别不够就是不够）。
 * 运行入口：{@code mvn exec:java@gate}。</p>
 */
public class ToolGateScenario {

    private static final String TASK = "查一下 Acme 的客户记录，并更新本季度销售预测";

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(66));
        System.out.println("  工具权限网关 —— 角色够格才放行，越权尝试留痕");
        System.out.println("=".repeat(66));
        System.out.println("""
                  工具权限矩阵：
                    read_crm（READ）        所有角色可用
                    update_forecast（WRITE）经理及以上
                    export_all_data（ADMIN）仅管理员
                """);

        RunStore store = new RunStore(Path.of("runs", "runs.jsonl"));
        List<ToolGate.Denied> denials = new ArrayList<>();

        runAs("scenario-gate-manager", "经理", ToolGate.Level.WRITE, denials, store, false);
        runAs("scenario-gate-specialist", "专员", ToolGate.Level.READ, denials, store, false);
        runAs("scenario-gate-admin-try", "经理", ToolGate.Level.WRITE, denials, store, true);

        // ── 越权留痕审计 ──
        System.out.println("\n── 越权尝试留痕（" + denials.size() + " 次）──");
        if (denials.isEmpty()) {
            System.out.println("  无");
        }
        for (ToolGate.Denied d : denials) {
            System.out.printf("  用户 %-4s 试图调用 %-16s 需要 %-6s 实际 %-6s → 已拦截%n",
                    d.user(), d.tool(), d.required(), d.actual());
        }

        // ── 回放专员那趟：轨迹里 DENIED 可见 ──
        var pick = store.find("scenario-gate-specialist");
        if (pick.isPresent()) {
            RunRecord r = pick.get();
            System.out.println("\n── 轨迹回放：" + r.runId() + "（终态 " + r.terminal() + "）──");
            for (RunRecord.Step s : r.steps()) {
                System.out.printf("  [第 %d 步 %dms] %s%n", s.turn(), s.durationMs(), s.output());
                System.out.println("    " + s.verdictKind() + (s.reason().isEmpty() ? "" : "：" + s.reason()));
            }
        }

        System.out.println("""
                
                  结论：工具权限在执行前校验，越权调用无副作用、留痕进轨迹和档案；
                  agent 拿到 DENIED 后改提建议，任务降级完成而不是崩掉。
                """);
        System.exit(0);
    }

    /** 以指定角色跑一趟销售助手任务；tryAdmin 时把第三步换成 ADMIN 工具。 */
    private static void runAs(String runId, String user, ToolGate.Level role,
                              List<ToolGate.Denied> denials, RunStore store,
                              boolean tryAdmin) throws Exception {
        ToolGate gate = new ToolGate(user, role, denials::add);
        var verifierLlm = new CountingLlm(new ScriptedLlm().says("YES"));
        System.out.println("── " + runId + "：用户 " + user + "（" + role + "）──");

        long t0 = System.nanoTime();
        LoopResult r = new AgentLoop(salesAgent(gate, tryAdmin),
                new LlmVerifier(verifierLlm, "汇报里要有客户情况，以及预测的处理结果"), 3).run(TASK);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        store.append(RunRecord.ofLoop(runId, TASK, r, verifierLlm.calls(), ms));
        System.out.println("  → " + r.stopReason() + "，成果：" + r.finalOutput());
    }

    /** 剧本销售助手：读 CRM → 更新预测（或 ADMIN 导出）→ 汇总。
     *  工具全走 ToolGate；拿到 DENIED 就改提建议，任务降级完成。 */
    private static AgentLoop.Actor salesAgent(ToolGate gate, boolean tryAdmin) {
        return ctx -> {
            String crm = gate.call("read_crm", ToolGate.Level.READ,
                    in -> "CRM 记录：Acme 上季度续约率 80%，本月新增 3 个坐席", ctx.task());
            if (tryAdmin) {
                String exp = gate.call("export_all_data", ToolGate.Level.ADMIN,
                        in -> "全量导出完成", ctx.task());
                return crm + "；" + (exp.startsWith("DENIED")
                        ? exp + "。改提建议：全量导出需管理员审批后另行执行。"
                        : exp);
            }
            String upd = gate.call("update_forecast", ToolGate.Level.WRITE,
                    in -> "预测已更新（" + in + "）", "Q4 上调 15%");
            return crm + "；" + (upd.startsWith("DENIED")
                    ? upd + "。改提建议：请经理确认后手动更新预测。"
                    : upd);
        };
    }
}
