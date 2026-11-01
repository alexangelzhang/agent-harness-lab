package com.example.harness.diagnostics;

import com.example.harness.RunRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 失败归因（Definitive Guide Ch7「迭代诊断流」）：线上失败会话的底账
 * （RunRecord）早就在攒，缺的是<b>归因</b——这次失败是哪种病，人该修
 * 哪一层。三种病因按规则从轨迹里拆：
 *
 * <ul>
 *   <li><b>下游抖动</b>：轨迹里出现 ENV_FAIL 步或终态 ENV_ABORTED——
 *       错不在模型，在网关超时/依赖抖动，重试机制和容错该在这层修；</li>
 *   <li><b>Prompt 漂移</b>：同一任务历史上成功过（底账可查），现在
 *       同一套提示词失败——模型权重没变的话，变的可能是提示词或配置，
 *       去 diff 这两次运行之间改了什么；</li>
 *   <li><b>模型退化</b>：没有历史成功、没有环境故障，同一改法理由在
 *       轨迹里反复出现 ≥3 次还过不去——模型在同一个坑上打转，该换模型、
 *       加示例或拆任务。</li>
 * </ul>
 *
 * <p>病因可以同时命中（级联故障）：下游抖动引发超时重试，重试理由
 * 又高度重复——归因报告把每个命中的病因连同证据摆出来，不替人拍板
 * 只修哪个。</p>
 */
public final class FailureDiagnostician {

    /** 一个命中病因：名字 + 置信度 + 证据。 */
    public record Cause(String name, String confidence, String evidence) {
    }

    /** 一次归因报告。 */
    public record Diagnosis(List<Cause> causes, String recommendation) {

        public boolean hasCause(String name) {
            return causes.stream().anyMatch(c -> c.name().equals(name));
        }

        public String topCause() {
            return causes.isEmpty() ? "无明确病因" : causes.get(0).name();
        }
    }

    private static final int REPEAT_THRESHOLD = 3;

    /** 归因一次失败运行；history 是同 runner 的历史档案（可空）。 */
    public Diagnosis diagnose(RunRecord failed, List<RunRecord> history) {
        List<Cause> causes = new ArrayList<>();
        long envSteps = failed.steps().stream()
                .filter(s -> "ENV_FAIL".equals(s.verdictKind())).count();
        if ("ENV_ABORTED".equals(failed.terminal()) || envSteps > 0) {
            causes.add(new Cause("下游抖动", "高",
                    envSteps + " 步环境故障（ENV_FAIL），终态 "
                            + failed.terminal() + "——错不在模型，在依赖。"));
        }
        List<RunRecord> pastSuccess = history.stream()
                .filter(r -> sameTask(r, failed) && isSuccess(r.terminal()))
                .toList();
        if (!pastSuccess.isEmpty() && envSteps == 0) {
            RunRecord last = pastSuccess.get(pastSuccess.size() - 1);
            causes.add(new Cause("Prompt 漂移", "高",
                    "同一任务 " + pastSuccess.size() + " 次成功过（最近 "
                            + last.runId() + "），同一套提示词现在失败——"
                            + "去 diff 这两次之间改了什么。"));
        }
        Map<String, Integer> retries = new LinkedHashMap<>();
        failed.steps().stream()
                .filter(s -> "RETRY".equals(s.verdictKind()) && !s.reason().isBlank())
                .forEach(s -> retries.merge(normalize(s.reason()), 1, Integer::sum));
        retries.forEach((reason, n) -> {
            if (n >= REPEAT_THRESHOLD) {
                causes.add(new Cause("模型退化", "中",
                        "同一改法理由反复出现 " + n + " 次还过不去：「"
                                + abbreviate(reason) + "」——模型在同一个坑上打转。"));
            }
        });
        if (causes.isEmpty()) {
            causes.add(new Cause("未识别", "—",
                    "三种规则都没命中——归因规则本身该补了（新病因 = 加一个 scan）。"));
        }
        return new Diagnosis(List.copyOf(causes), recommend(causes));
    }

    private static String recommend(List<Cause> causes) {
        boolean env = causes.stream().anyMatch(c -> c.name().equals("下游抖动"));
        boolean drift = causes.stream().anyMatch(c -> c.name().equals("Prompt 漂移"));
        boolean degrade = causes.stream().anyMatch(c -> c.name().equals("模型退化"));
        if (env) {
            return "先修容错层：超时重试/熔断/降级——别让下游抖动污染成成果问题。";
        }
        if (drift) {
            return "先 diff 提示词与配置，拿历史成功会话做对照重放——别急着换模型。";
        }
        if (degrade) {
            return "动模型层：换更强的、补少样本示例、或把任务拆小——同坑打转是能力或粒度问题。";
        }
        return "人工排查。";
    }

    private static boolean sameTask(RunRecord a, RunRecord b) {
        return a.task() != null && a.task().equals(b.task());
    }

    private static boolean isSuccess(String terminal) {
        return "SUCCESS".equals(terminal) || "ACCEPT".equals(terminal)
                || "APPROVED_DONE".equals(terminal);
    }

    private static String normalize(String reason) {
        return reason.strip().replaceAll("\\s+", " ");
    }

    private static String abbreviate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }
}
