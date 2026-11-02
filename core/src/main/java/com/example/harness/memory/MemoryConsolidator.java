package com.example.harness.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆整理：一轮对话结束后，把值得留下的 distill 成事实写回记忆库
 * （智能体AI 文章的写回链路：不是只有新增）。
 *
 * <p>每个事实落库前走<b>三操作 + 一空转</b>：</p>
 * <ul>
 *   <li>{@link WriteOp#SUPERSEDE}——新事实让旧事实作废：旧记录不删，
 *       标「已被替代」留历史（审计/回滚可查），检索默认不再出；</li>
 *   <li>{@link WriteOp#MERGE}——与已有记忆同主题（共享标签）但内容是补充：
 *       并成一条更完整的记录，而不是多一条重复；</li>
 *   <li>{@link WriteOp#ADD}——有价值且此前不存在：新增；</li>
 *   <li>{@link WriteOp#SKIP}——与现有 ACTIVE 记忆完全重复：不动。</li>
 * </ul>
 *
 * <p>抽取器这里是<b>规则版</b>：只认「以后用 X / 统一用 X / 改用 X /
 * 不再用 Y」这类显式偏好句。生产里这一步是 LLM 抽取 + 人审，
 * {@link #consolidate} 拿到的就是抽取后的事实，接口形状不变。</p>
 */
public final class MemoryConsolidator {

    /** 写回操作类型。 */
    public enum WriteOp { ADD, MERGE, SUPERSEDE, SKIP }

    /** 一次写回的决定：对哪条记忆做了什么、为什么。 */
    public record WriteDecision(WriteOp op, Memory target, String why) {
    }

    /** 抽取出来的事实：要写入的表述 + 因此退役的关键词。 */
    public record ExtractedFact(String content, String source, List<String> tags,
                                List<String> retiredKeywords) {
    }

    /** 一轮整理的全部决定（空列表 = 这轮没有值得记的）。 */
    public record Result(List<WriteDecision> decisions) {

        public long count(WriteOp op) {
            return decisions.stream().filter(d -> d.op() == op).count();
        }
    }

    private static final Pattern RETIRE = Pattern.compile(
            "不再?用\\s*([^，,。；;\\s]+)");
    private static final Pattern ADOPT = Pattern.compile(
            "(?:改用|以后用|统一用|只用)\\s*([^，,。；;\\s]+)");

    /**
     * 整理一轮对话的收尾：抽取事实 → SUPERSEDE 命中退役词的旧记忆 →
     * SKIP 重复 → MERGE 同主题补充 → ADD 新事实。
     */
    public Result consolidate(MemoryStore store, Memory.Scope scope, String owner,
                              String turnText, String source) {
        List<WriteDecision> decisions = new ArrayList<>();
        for (ExtractedFact fact : extract(turnText, source)) {
            // 1) 退役：含退役关键词的同域同主 ACTIVE 记忆标记被替代
            for (Memory old : store.all()) {
                boolean sameOwner = old.scope() == scope && old.owner().equals(owner);
                boolean hit = fact.retiredKeywords().stream().anyMatch(k ->
                        old.content().toLowerCase(Locale.ROOT)
                                .contains(k.toLowerCase(Locale.ROOT)));
                if (sameOwner && old.status() == Memory.Status.ACTIVE && hit
                        && store.supersede(old.id())) {
                    decisions.add(new WriteDecision(WriteOp.SUPERSEDE, old,
                            "含退役关键词「" + fact.retiredKeywords() + "」"));
                }
            }
            // 2) 重复：已有同域同主同内容的 ACTIVE 记忆
            boolean duplicate = store.all().stream().anyMatch(m ->
                    m.scope() == scope && m.owner().equals(owner)
                            && m.status() == Memory.Status.ACTIVE
                            && m.content().equals(fact.content()));
            if (duplicate) {
                decisions.add(new WriteDecision(WriteOp.SKIP, null, "与现有记忆重复"));
                continue;
            }
            // 3) 合并：与某条 ACTIVE 记忆共享标签且不是替代关系
            Memory mergeTarget = bestMergeTarget(store, scope, owner, fact);
            if (mergeTarget != null) {
                store.merge(mergeTarget.id(), fact.content(), fact.source());
                decisions.add(new WriteDecision(WriteOp.MERGE, mergeTarget,
                        "共享标签 " + sharedTags(mergeTarget, fact) + "，并入更完整记录"));
                continue;
            }
            // 4) 新增
            Memory added = store.add(scope, owner, fact.content(), fact.source(),
                    fact.tags());
            decisions.add(new WriteDecision(WriteOp.ADD, added, "新事实"));
        }
        return new Result(decisions);
    }

    /** 找合并目标：同域同主 ACTIVE、共享标签最多、内容不重复的记忆。 */
    private static Memory bestMergeTarget(MemoryStore store, Memory.Scope scope,
                                          String owner, ExtractedFact fact) {
        Memory best = null;
        int bestShared = 0;
        for (Memory m : store.all()) {
            if (m.scope() != scope || !m.owner().equals(owner)
                    || m.status() != Memory.Status.ACTIVE) {
                continue;
            }
            int shared = sharedTags(m, fact).size();
            if (shared > bestShared) {
                best = m;
                bestShared = shared;
            }
        }
        return best;
    }

    private static List<String> sharedTags(Memory m, ExtractedFact fact) {
        List<String> shared = new ArrayList<>();
        for (String tag : fact.tags()) {
            if (m.tags().contains(tag)) {
                shared.add(tag);
            }
        }
        return shared;
    }

    /** 规则抽取：只认显式偏好句，场景 之外请换成 LLM 抽取。 */
    public static List<ExtractedFact> extract(String turnText, String source) {
        List<ExtractedFact> out = new ArrayList<>();
        List<String> retired = new ArrayList<>();
        Matcher rm = RETIRE.matcher(turnText);
        while (rm.find()) {
            retired.add(rm.group(1));
        }
        String adopt = null;
        Matcher am = ADOPT.matcher(turnText);
        if (am.find()) {
            adopt = am.group(1);
        }
        if (adopt != null) {
            String content = "统一使用 " + adopt;
            List<String> tags = new ArrayList<>();
            tags.add(adopt.toLowerCase(Locale.ROOT));
            retired.forEach(k -> tags.add(k.toLowerCase(Locale.ROOT)));
            out.add(new ExtractedFact(content, source, tags, retired));
        }
        return out;
    }
}
