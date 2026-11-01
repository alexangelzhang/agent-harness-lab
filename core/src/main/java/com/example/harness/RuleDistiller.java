package com.example.harness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 外循环最小版：从运行档案里蒸馏规则草稿。
 *
 * <p>内循环的记录是外循环学习的原料（cross-task loop）：档案攒够之后，
 * 这里把<b>重复出现的失败模式</b>翻成 AGENTS.md 规则条目草稿——
 * 对应 Meta「同一错误重复超过两次就写进永久项目规则」+ Warp 的改进 Skill
 * （定时收集反馈、提小范围调整、经维护者审查合并）。</p>
 *
 * <p>提案制，不自动生效：产出的每条草稿带证据（哪几次运行、各是什么终态），
 * 人审后手动合并进 AGENTS.md。规则修改是否有效要在后续同类任务里检验——
 * 不能把「反馈被采纳」等同于「质量已改善」。</p>
 *
 * <p>目前蒸馏三类模式：越权反复被拦（档案 step 里的 DENIED 文本）、
 * 终态反复失败（BUDGET_EXHAUSTED / EXHAUSTED / MAX_ROUNDS 计数）、
 * 护栏反复触发（GUARD_BLOCKED 记录里「护栏」步的 output，按规则类型 +
 * runner 聚类——哪类任务容易被哪条规则拦下，同样能沉淀成规则）。
 * 模式识别只依赖档案本身，新增模式 = 加一个 scan 方法。</p>
 */
public final class RuleDistiller {

    /** Meta 阈值：同一错误重复超过两次才值得写成规则。 */
    public static final int MIN_OCCURRENCES = 2;

    /** 正常收口的终态各方言——只有不在这张表里的终态才算失败模式。 */
    static final java.util.Set<String> GOOD_TERMINALS =
            java.util.Set.of("SUCCESS", "DONE", "ACCEPT", "APPROVED_DONE", "AUTO_DONE");

    private static final Pattern DENIED_PATTERN = Pattern.compile(
            "DENIED: 用户 (\\S+?)（(\\w+)）无权使用工具 (\\S+?)（需要 (\\w+) 权限）");

    /** 一条规则草稿：类别、规则文本、证据（来源运行与计数）。 */
    public record RuleDraft(String category, String rule, String evidence) {
    }

    /** 从一批运行档案蒸馏规则草稿。 */
    public List<RuleDraft> distill(List<RunRecord> runs) {
        List<RuleDraft> out = new ArrayList<>();
        out.addAll(denyRules(runs));
        out.addAll(terminalRules(runs));
        out.addAll(guardRules(runs));
        return out;
    }

    /**
     * 与既有规则库去重——防同一教训以两种文本存两份（档案蒸馏一份、
     * AGENTS.md 深坑一份）。调用方按类目给探测词（如「预算」「绕圈」），
     * 命中既有文本的草稿在规则前标【已收录】、证据里注明探测词，人审时
     * 直接跳过；未命中的才是要合并的新教训。探测词由调用方维护——
     * 去重逻辑不猜什么是「同一条教训」。
     */
    public List<RuleDraft> dedupeKnown(List<RuleDraft> drafts, String existingRules,
                                       Map<String, List<String>> probesByCategory) {
        List<RuleDraft> out = new ArrayList<>(drafts.size());
        for (RuleDraft d : drafts) {
            String hit = null;
            for (String probe : probesByCategory.getOrDefault(d.category(), List.of())) {
                if (existingRules.contains(probe)) {
                    hit = probe;
                    break;
                }
            }
            out.add(hit == null ? d : new RuleDraft(d.category(),
                    "【已收录】" + d.rule(),
                    d.evidence() + "；探测词「" + hit + "」已见于既有规则库，合并时跳过"));
        }
        return out;
    }

    /** 模式一：越权反复被拦——同一用户同一工具被拦 ≥2 次，提权限或改流程。 */
    private List<RuleDraft> denyRules(List<RunRecord> runs) {
        // key = 用户|角色|工具|所需权限；value = 出现的 runId 列表（保序去重）
        Map<String, List<String>> hits = new LinkedHashMap<>();
        for (RunRecord r : runs) {
            for (RunRecord.Step s : r.steps()) {
                Matcher m = DENIED_PATTERN.matcher(s.output());
                while (m.find()) {
                    String key = String.join("|", m.group(1), m.group(2), m.group(3), m.group(4));
                    hits.computeIfAbsent(key, k -> new ArrayList<>()).add(r.runId());
                }
            }
        }
        List<RuleDraft> out = new ArrayList<>();
        hits.forEach((key, runIds) -> {
            if (runIds.size() < MIN_OCCURRENCES) {
                return;
            }
            String[] parts = key.split("\\|", -1);
            out.add(new RuleDraft("权限缺口",
                    "用户 " + parts[0] + "（" + parts[1] + "）反复因权限不足被拦调用 " + parts[2]
                            + "（需要 " + parts[3] + "，已出现 " + runIds.size() + " 次）。"
                            + "确认是业务需要就提权到 " + parts[3] + "；不是就在该角色的工作流里"
                            + "明示走审批通道，别每次撞墙。",
                    "来源：" + runIds.stream().distinct().collect(Collectors.joining(", "))));
        });
        return out;
    }

    /** 模式二：终态反复失败——同类终态 ≥2 次，各自给修法草稿。 */
    private List<RuleDraft> terminalRules(List<RunRecord> runs) {
        // 正常收口的各方言不进失败统计；GUARD_BLOCKED 由 guardRules 按规则类型细分，
        // 这里不混进笼统计数
        Map<String, List<RunRecord>> byTerminal = runs.stream()
                .filter(r -> !GOOD_TERMINALS.contains(r.terminal())
                        && !"GUARD_BLOCKED".equals(r.terminal()))
                .collect(Collectors.groupingBy(RunRecord::terminal, LinkedHashMap::new, Collectors.toList()));
        List<RuleDraft> out = new ArrayList<>();
        byTerminal.forEach((terminal, rs) -> {
            if (rs.size() < MIN_OCCURRENCES) {
                return;
            }
            String runners = rs.stream().map(RunRecord::runner).distinct()
                    .collect(Collectors.joining("/"));
            String rule = switch (terminal) {
                case "BUDGET_EXHAUSTED" -> "预算反复耗尽（" + rs.size() + " 次）：要么按典型消耗上调预算，"
                        + "要么先修验收协议减少无效轮次——钱花在撞墙上，两边总要治一边。";
                case "EXHAUSTED" -> "轮数反复耗尽（" + rs.size() + " 次）：验收协议可能太严或反馈不够具体，"
                        + "翻轨迹里的回流理由，确认每条都是可操作的改进意见。";
                case "MAX_ROUNDS" -> "顶满轮数无终裁（" + rs.size() + " 次）：给图版配 judge 终裁"
                        + "（参考 loopbench：无 judge 0/3 → 配 judge 3/3）。";
                default -> "终态 " + terminal + " 反复出现（" + rs.size() + " 次）：见来源运行逐条排查。";
            };
            out.add(new RuleDraft("终态模式",
                    rule,
                    "runner=" + runners + "，来源：" + rs.stream().map(RunRecord::runId)
                            .collect(Collectors.joining(", "))));
        });
        return out;
    }

    /**
     * 模式三：护栏反复触发——GUARD_BLOCKED 记录里「护栏」步的 output 是
     * 「规则类型 → 理由」，按 规则类型|runner 聚类，≥2 次说明这类拓扑
     * 跟这条护栏天天打架：要么改配置，要么改分工。
     */
    private List<RuleDraft> guardRules(List<RunRecord> runs) {
        // key = 规则类型|runner；value = runId 列表（保序）
        Map<String, List<String>> hits = new LinkedHashMap<>();
        for (RunRecord r : runs) {
            if (!"GUARD_BLOCKED".equals(r.terminal())) {
                continue;
            }
            for (RunRecord.Step s : r.steps()) {
                if (!"护栏".equals(s.reason())) {
                    continue;
                }
                String ruleKind = s.output().contains(" → ")
                        ? s.output().substring(0, s.output().indexOf(" →"))
                        : "unknown";
                hits.computeIfAbsent(ruleKind + "|" + r.runner(), k -> new ArrayList<>())
                        .add(r.runId());
            }
        }
        List<RuleDraft> out = new ArrayList<>();
        hits.forEach((key, runIds) -> {
            if (runIds.size() < MIN_OCCURRENCES) {
                return;
            }
            String[] parts = key.split("\\|", -1);
            String rule = switch (parts[0]) {
                case "max-depth" -> "分层委派反复顶到层数上限（" + runIds.size()
                        + " 次，" + parts[1] + "）：先想清楚层级是不是真有必要——"
                        + "层级每深一层上下文丢一分；确有需要就把 maxDepth 调到"
                        + "贴近真实层数，别让护栏天天响。";
                case "cycle" -> "交接反复绕圈（A→B→A，" + runIds.size() + " 次，"
                        + parts[1] + "）：路由跳过持有者自己是底线；仍绕圈说明"
                        + "分工本身有洞——把这对互相转交的场景合并给一个专员。";
                case "max-handoffs" -> "交接次数反复到顶（" + runIds.size() + " 次，"
                        + parts[1] + "）：要么收敛专员数量，要么把要多棒才办完的事"
                        + "改回主管直派，别让用户陪跑。";
                default -> "护栏规则 " + parts[0] + " 反复触发（" + runIds.size()
                        + " 次，" + parts[1] + "）：见来源运行逐条排查。";
            };
            out.add(new RuleDraft("护栏模式",
                    rule,
                    "来源：" + runIds.stream().distinct().collect(Collectors.joining(", "))));
        });
        return out;
    }
}
