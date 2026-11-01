package com.example.harness.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 记忆库：分作用域存、按关键词检索。
 *
 * <p>检索是<b>两条路</b>（文章 #3）：对话开始时 Harness 自动把当前项目相关的
 * 记忆放进初始上下文；模型干到一半发现缺背景，主动 {@link #search}——
 * 两个入口走同一个打分器。打分器是确定性的关键词重叠，
 * 不假装是向量库：生产换 embedding/reranker 是挂点，分层与生命周期
 * 的骨架先立住。</p>
 */
public final class MemoryStore {

    /** 一次检索的命中：记忆 + 得分。 */
    public record Hit(Memory memory, double score) {
    }

    private final List<Memory> memories = new ArrayList<>();
    private final AtomicLong seq = new AtomicLong();
    // 严格递增时钟：同毫秒写入多条时 createdAt 仍单调——
    // 同分召回取新的（interference 维度）才有确定依据
    private static final AtomicLong CLOCK = new AtomicLong();
    private final Map<String, Long> lastAccess = new LinkedHashMap<>();
    private final int maxEntries;
    private long evicted;

    /** 不限容量的记忆库。 */
    public MemoryStore() {
        this(Integer.MAX_VALUE);
    }

    /**
     * 限容量的记忆库：写到上限后，新增触发<b>最久未用淘汰</b>——
     * 容量是控制面问题，不是无限涨（综述评测五维之 capacity）。
     */
    public MemoryStore(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    /** 淘汰计数（capacity 评测用）。 */
    public long evictedCount() {
        return evicted;
    }

    /** 写入一条记忆（默认置信 0.8 / 有效 / 长期 / 不限权限），分配自增 id。 */
    public Memory add(Memory.Scope scope, String owner, String content,
                      String source, List<String> tags) {
        Memory m = Memory.of("mem-" + seq.incrementAndGet(), scope, owner,
                content, source, nextTimestamp(), List.copyOf(tags));
        return insert(m);
    }

    /**
     * 完整写入：显式指定置信度、有效期、权限。
     * <b>写入闸门</b>：置信 &lt; 0.6（模型推断而非用户确认）的记忆以
     * UNCONFIRMED 落库——检索默认不出，确认（{@link #confirm}）后才转 ACTIVE。
     */
    public Memory add(Memory.Scope scope, String owner, String content, String source,
                      List<String> tags, double confidence, long validUntilMs,
                      List<String> permissions) {
        Memory.Status status = confidence < 0.6
                ? Memory.Status.UNCONFIRMED : Memory.Status.ACTIVE;
        Memory m = new Memory("mem-" + seq.incrementAndGet(), scope, owner, content,
                source, nextTimestamp(), List.copyOf(tags), confidence,
                status, validUntilMs, List.copyOf(permissions));
        return insert(m);
    }

    private static long nextTimestamp() {
        return CLOCK.updateAndGet(last -> Math.max(System.currentTimeMillis(), last + 1));
    }

    /**
     * 与写入记忆时同一个时钟的当前值——比对这个库里的 createdAt 就用它，
     * 否则严格递增时钟领先墙钟 1ms 时，「超保留期」判断会把刚写的误判成
     还没到点（Janitor 的 sweep 用这个，别直接用 currentTimeMillis）。
     */
    public static long now() {
        return Math.max(System.currentTimeMillis(), CLOCK.get());
    }

    private Memory insert(Memory m) {
        while (memories.size() >= maxEntries) {
            if (!evictLeastRecentlyUsed()) {
                break; // 库里全是不可淘汰的，放任超限（比死循环强）
            }
        }
        memories.add(m);
        lastAccess.put(m.id(), System.currentTimeMillis());
        return m;
    }

    /** 淘汰最久未用的 ACTIVE 记忆（UNCONFIRMED/SUPERSEDED/EXPIRED 由 Janitor 管）。 */
    private boolean evictLeastRecentlyUsed() {
        Memory victim = memories.stream()
                .filter(m -> m.status() == Memory.Status.ACTIVE)
                .min(Comparator.comparingLong(m -> lastAccess.getOrDefault(m.id(), 0L)))
                .orElse(null);
        if (victim == null) {
            return false;
        }
        memories.remove(victim);
        lastAccess.remove(victim.id());
        evicted++;
        return true;
    }

    /** 确认一条待确认记忆转正（人审/复跑验证后调用）。 */
    public boolean confirm(String id) {
        return replace(id, m -> m.status() == Memory.Status.UNCONFIRMED
                ? new Memory(m.id(), m.scope(), m.owner(), m.content(), m.source(),
                        m.createdAtMs(), m.tags(), Math.max(m.confidence(), 0.8),
                        Memory.Status.ACTIVE, m.validUntilMs(), m.permissions())
                : m);
    }

    /** 物理清除（遗忘的终点：Janitor 对超保留期的归档项调用）。 */
    public boolean purge(String id) {
        lastAccess.remove(id);
        return memories.removeIf(m -> m.id().equals(id));
    }

    /** 主动检索（不限角色）：query 分词后与 内容+标签 做重叠打分，降序返回。 */
    public List<Hit> search(String query) {
        return search(query, null, List.of());
    }

    /** 限定作用域的检索。 */
    public List<Hit> search(String query, Memory.Scope scope) {
        return search(query, scope, List.of());
    }

    /**
     * 带角色校验的检索：只出「有效 + 没过期 + 权限够」的记忆——
     * 被替代的和过期的留在库里（{@link #all} 可查）但不进结果。
     */
    public List<Hit> search(String query, Memory.Scope scope, List<String> roles) {
        Set<String> terms = terms(query);
        List<Hit> hits = new ArrayList<>();
        for (Memory m : memories) {
            if (scope != null && m.scope() != scope) {
                continue;
            }
            if (!m.injectableFor(roles)) {
                continue;
            }
            double score = score(terms, m);
            if (score > 0) {
                hits.add(new Hit(m, score));
                lastAccess.put(m.id(), System.currentTimeMillis());
            }
        }
        // 同分取新：新旧事实并存时，召回必须给更新的——
        // 否则记得越久越差（MemoryBench.interference 守这条）
        hits.sort(Comparator.comparingDouble(Hit::score).reversed()
                .thenComparing(Comparator.comparingLong(
                        (Hit h) -> h.memory().createdAtMs()).reversed()));
        return hits;
    }

    /** 自动注入的素材：某作用域下分数过线的头部记忆。 */
    public List<Memory> injectable(Memory.Scope scope, String queryHint, int topK) {
        return search(queryHint, scope).stream()
                .limit(topK)
                .map(Hit::memory)
                .toList();
    }

    /** 标记一条记忆为「已被替代」：保留备查，检索默认不再出它。 */
    public boolean supersede(String id) {
        return replace(id, m -> new Memory(m.id(), m.scope(), m.owner(), m.content(),
                m.source(), m.createdAtMs(), m.tags(), m.confidence(),
                Memory.Status.SUPERSEDED, m.validUntilMs(), m.permissions()));
    }

    /** 到期置 EXPIRED（Janitor 调用；也可单独用于演示）。 */
    public boolean expire(String id) {
        return replace(id, m -> new Memory(m.id(), m.scope(), m.owner(), m.content(),
                m.source(), m.createdAtMs(), m.tags(), m.confidence(),
                Memory.Status.EXPIRED, m.validUntilMs(), m.permissions()));
    }

    /** 合并：把补充内容并进已有记忆（记录更完整，不是多一条重复）。 */
    public boolean merge(String id, String addition, String source) {
        return replace(id, m -> new Memory(m.id(), m.scope(), m.owner(),
                m.content() + "；" + addition,
                m.source() + " / " + source, m.createdAtMs(), m.tags(),
                Math.max(m.confidence(), 0.9), m.status(), m.validUntilMs(),
                m.permissions()));
    }

    /** 全部记忆（含被替代/过期的，审计与断言用）。 */
    public List<Memory> all() {
        return List.copyOf(memories);
    }

    private boolean replace(String id, Function<Memory, Memory> update) {
        for (int i = 0; i < memories.size(); i++) {
            if (memories.get(i).id().equals(id)) {
                memories.set(i, update.apply(memories.get(i)));
                return true;
            }
        }
        return false;
    }

    /** query 分词：空白/标点切开、转小写；含中文的词再拆 2-gram（「部署命名规范」→ 部署/署命/命名/名规/规范）。记忆检索与候选筛选共用同一套分词。 */
    public static Set<String> terms(String query) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : query.toLowerCase(Locale.ROOT).split("[\\s,，。；;？?！!]+")) {
            if (t.isBlank()) {
                continue;
            }
            out.add(t);
            if (t.codePoints().anyMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF)) {
                for (int i = 0; i + 2 <= t.length(); i++) {
                    out.add(t.substring(i, i + 2));
                }
            }
        }
        return out;
    }

    /** 打分：命中标签权重 2，命中内容权重 1，按 query 分片数归一。 */
    private static double score(Set<String> terms, Memory m) {
        String content = m.content().toLowerCase(Locale.ROOT);
        Set<String> tagSet = new LinkedHashSet<>();
        for (String tag : m.tags()) {
            tagSet.add(tag.toLowerCase(Locale.ROOT));
        }
        double raw = 0;
        for (String term : terms) {
            if (tagSet.contains(term)) {
                raw += 2;
            }
            if (content.contains(term)) {
                raw += 1;
            }
        }
        return raw / terms.size();
    }
}
