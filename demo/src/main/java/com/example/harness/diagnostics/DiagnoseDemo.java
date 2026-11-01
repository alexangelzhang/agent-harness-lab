package com.example.harness.diagnostics;

import com.example.harness.RunRecord;

import java.util.List;

/**
 * 失败归因演示（Definitive Guide Ch7 缺口补齐）：三种典型失败各归因
 * 一次——下游抖动（轨迹里全是 ENV_FAIL）、Prompt 漂移（同任务历史
 * 成功过现在失败）、模型退化（同一改法理由打转 ≥3 次），看归因报告
 * 各指哪层、给人什么修法建议。
 *
 * <p>运行：{@code mvn exec:java@diagnose}（纯构造档案，离线）。</p>
 */
public class DiagnoseDemo {

    public static void main(String[] args) {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("失败归因：下游抖动 / Prompt 漂移 / 模型退化");
        System.out.println(line);
        FailureDiagnostician diagnostician = new FailureDiagnostician();

        System.out.println("\n── 病例一：下游抖动 ── 轨迹里全是环境故障");
        RunRecord envCase = new RunRecord("run-env-1", "agentloop",
                "生成周报", "ENV_ABORTED",
                List.of(new RunRecord.Step(1, "草稿一", "ENV_FAIL",
                                "网关超时", 3000),
                        new RunRecord.Step(2, "草稿一", "ENV_FAIL",
                                "网关超时", 3000),
                        new RunRecord.Step(3, "", "ENV_FAIL",
                                "网关超时", 3000)),
                6, 9000, 0);
        print(diagnostician.diagnose(envCase, List.of()));

        System.out.println("\n── 病例二：Prompt 漂移 ── 同任务两周前还成功");
        List<RunRecord> history = List.of(
                new RunRecord("run-ok-1", "agentloop", "生成周报", "SUCCESS",
                        List.of(new RunRecord.Step(1, "草稿", "PASS", "", 1000)),
                        2, 2000, 0),
                new RunRecord("run-ok-2", "agentloop", "生成周报", "SUCCESS",
                        List.of(new RunRecord.Step(1, "草稿", "PASS", "", 1000)),
                        2, 2000, 0));
        RunRecord driftCase = new RunRecord("run-drift-1", "agentloop",
                "生成周报", "EXHAUSTED",
                List.of(new RunRecord.Step(1, "草稿", "RETRY", "结构不对", 1000),
                        new RunRecord.Step(2, "草稿二", "RETRY", "口径不对", 1000)),
                4, 4000, 0);
        print(diagnostician.diagnose(driftCase, history));

        System.out.println("\n── 病例三：模型退化 ── 同一改法理由打转 3 次");
        RunRecord degradeCase = new RunRecord("run-deg-1", "agentloop",
                "写接口文档", "EXHAUSTED",
                List.of(new RunRecord.Step(1, "v1", "RETRY", "缺超时说明", 1000),
                        new RunRecord.Step(2, "v2", "RETRY", "缺超时说明", 1000),
                        new RunRecord.Step(3, "v3", "RETRY", "缺超时说明", 1000)),
                6, 6000, 0);
        print(diagnostician.diagnose(degradeCase, List.of()));

        boolean ok = diagnostician.diagnose(envCase, List.of()).topCause().equals("下游抖动")
                && diagnostician.diagnose(driftCase, history).topCause().equals("Prompt 漂移")
                && diagnostician.diagnose(degradeCase, List.of()).topCause().equals("模型退化");
        System.out.println();
        System.out.println(line);
        System.out.println(ok
                ? "收尾：失败不再只是「失败了」——是哪种病，报告指哪层，人修哪层。"
                : "!! 归因没指对");
        System.out.println(line);
        System.exit(ok ? 0 : 1);
    }

    private static void print(FailureDiagnostician.Diagnosis d) {
        System.out.println("  命中病因：");
        d.causes().forEach(c -> System.out.println("    - " + c.name()
                + "（置信 " + c.confidence() + "）：" + c.evidence()));
        System.out.println("  建议：" + d.recommendation());
    }
}
