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
 * <p>检索是两个入口：对话开始时自动注入当前项目相关记忆，模型干到一半
 * 发现缺背景时主动 {@link #search}。两个入口共用确定性的关键词重叠打分。
 * 所有读写方法同步；带版本的更新在同一个临界区里检查并替换，旧整理者
 * 不能覆盖已经推进的版本。</p>
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

    /** 限容量的记忆库：新增到上限后，先淘汰最久未用的 ACTIVE 记忆。 */
    public MemoryStore(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    /** 淘汰计数（capacity 评测用）。 */
    public synchronized long evictedCount() {
        return evicted;
    }

    /** 写入一条记忆（默认置信 0.8 / 有效 / 长期 / 不限权限），分配自增 id。 */
    public synchronized Memory add(Memory.Scope scope, String owner, String content,
                                   String source, List<String> tags) {
        Memory m = Memory.of("mem-" + seq.incrementAndGet(), scope, owner,
                content, source, nextTimestamp(), List.copyOf(tags));
        return insert(m);
    }

    /**
     * 完整写入：显式指定置信度、有效期、权限。
     * 置信 &lt; 0.6 的记忆以 UNCONFIRMED 落库，确认后才转 ACTIVE。
     */
    public synchronized Memory add(Memory.Scope scope, String owner, String content,
                                   String source, List<String> tags, double confidence,
                                   long validUntilMs, List<String> permissions) {
        Memory.Status status = confidence < 0.6
                ? Memory.Status.UNCONFIRMED : Memory.Status.ACTIVE;
        Memory m = new Memory("mem-" + seq.incrementAndGet(), scope, owner, content,
                source, nextTimestamp(), List.copyOf(tags), confidence,
                status, validUntilMs, List.copyOf(permissions));
        return insert(m);
    }

    /** 完整写入并显式声明知识状态，供离线整理等有证据分类的入口使用。 */
    public synchronized Memory add(Memory.Scope scope, String owner, String content,
                                   String source, List<String> tags, double confidence,
                                   long validUntilMs, List<String> permissions,
                                   Memory.KnowledgeState knowledgeState) {
        Memory.Status status = confidence < 0.6
                ? Memory.Status.UNCONFIRMED : Memory.Status.ACTIVE;
        Memory m = new Memory("mem-" + seq.incrementAndGet(), scope, owner, content,
                source, nextTimestamp(), List.copyOf(tags), confidence, status,
                validUntilMs, List.copyOf(permissions), knowledgeState, 0);
        return insert(m);
    }

    private static long nextTimestamp() {
        return CLOCK.updateAndGet(last -> Math.max(System.currentTimeMillis(), last + 1));
    }

    /**
     * 与写入记忆时同一个时钟的当前值——比对这个库里的 createdAt 就用它，
     * 否则严格递增时钟领先墙钟 1ms 时，保留期判断会把刚写的误判成还没到点。
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

    /** 淘汰最久未用的 ACTIVE 记忆（其余生命周期状态由 Janitor 管）。 */
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
    public synchronized boolean confirm(String id) {
        return replace(id, null, MemoryStore::promote);
    }

    /** 只在调用方仍持有当前版本时确认，过期版本返回 false 且不改内容。 */
    public synchronized boolean confirm(String id, long expectedRevision) {
        return replace(id, expectedRevision, MemoryStore::promote);
    }

    private static Memory promote(Memory m) {
        if (m.status() != Memory.Status.UNCONFIRMED) {
            return m;
        }
        return copy(m, m.content(), m.source(), Math.max(m.confidence(), 0.8),
                Memory.Status.ACTIVE, Memory.KnowledgeState.VERIFIED);
    }

    /**
     * 依据新证据纠错：只有期望版本仍匹配才替换，并把知识标为已验证。
     * 证据是否真存在于原始记录由调用前的整理流程核验。
     */
    public synchronized boolean correct(String id, long expectedRevision,
                                        String content, String source) {
        return replace(id, expectedRevision, m -> copy(m, content, source,
                Math.max(m.confidence(), 0.9), Memory.Status.ACTIVE,
                Memory.KnowledgeState.VERIFIED));
    }

    /** 按 id 读取当前版本；不存在时返回 null。 */
    public synchronized Memory get(String id) {
        return memories.stream().filter(m -> m.id().equals(id)).findFirst().orElse(null);
    }

    /** 物理清除（遗忘的终点：Janitor 对超保留期的归档项调用）。 */
    public synchronized boolean purge(String id) {
        lastAccess.remove(id);
        return memories.removeIf(m -> m.id().equals(id));
    }

    /** 主动检索（不限角色）：query 分词后与 内容+标签 做重叠打分，降序返回。 */
    public synchronized List<Hit> search(String query) {
        return search(query, null, List.of());
    }

    /** 限定作用域的检索。 */
    public synchronized List<Hit> search(String query, Memory.Scope scope) {
        return search(query, scope, List.of());
    }

    /** 带角色校验的检索：只出有效、没过期且权限够的记忆。 */
    public synchronized List<Hit> search(String query, Memory.Scope scope, List<String> roles) {
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
        // 同分取新：新旧事实并存时，召回必须给更新的
        hits.sort(Comparator.comparingDouble(Hit::score).reversed()
                .thenComparing(Comparator.comparingLong(
                        (Hit h) -> h.memory().createdAtMs()).reversed()));
        return hits;
    }

    /** 自动注入的素材：某作用域下分数过线的头部记忆。 */
    public synchronized List<Memory> injectable(Memory.Scope scope, String queryHint, int topK) {
        return search(queryHint, scope).stream()
                .limit(topK)
                .map(Hit::memory)
                .toList();
    }

    /** 标记一条记忆为已被替代：保留备查，检索默认不再出它。 */
    public synchronized boolean supersede(String id) {
        return replace(id, null, m -> copy(m, m.content(), m.source(), m.confidence(),
                Memory.Status.SUPERSEDED, Memory.KnowledgeState.STALE));
    }

    /** 到期置 EXPIRED（Janitor 调用；也可单独用于演示）。 */
    public synchronized boolean expire(String id) {
        return replace(id, null, m -> copy(m, m.content(), m.source(), m.confidence(),
                Memory.Status.EXPIRED, Memory.KnowledgeState.STALE));
    }

    /** 合并：把补充内容并进已有记忆，而不是多一条重复。 */
    public synchronized boolean merge(String id, String addition, String source) {
        return replace(id, null, m -> copy(m, m.content() + "；" + addition,
                m.source() + " / " + source, Math.max(m.confidence(), 0.9),
                m.status(), m.knowledgeState()));
    }

    /** 只在调用方仍持有当前版本时合并；过期版本返回 false，调用方应重读后重试。 */
    public synchronized boolean merge(String id, long expectedRevision,
                                      String addition, String source) {
        return replace(id, expectedRevision, m -> copy(m, m.content() + "；" + addition,
                m.source() + " / " + source, Math.max(m.confidence(), 0.9),
                m.status(), m.knowledgeState()));
    }

    /** 全部记忆（含被替代/过期的，审计与断言用）。 */
    public synchronized List<Memory> all() {
        return List.copyOf(memories);
    }

    private boolean replace(String id, Long expectedRevision,
                            Function<Memory, Memory> update) {
        for (int i = 0; i < memories.size(); i++) {
            Memory current = memories.get(i);
            if (!current.id().equals(id)) {
                continue;
            }
            if (expectedRevision != null && current.revision() != expectedRevision) {
                return false;
            }
            Memory updated = update.apply(current);
            if (updated.equals(current)) {
                return true;
            }
            memories.set(i, new Memory(updated.id(), updated.scope(), updated.owner(),
                    updated.content(), updated.source(), updated.createdAtMs(), updated.tags(),
                    updated.confidence(), updated.status(), updated.validUntilMs(),
                    updated.permissions(), updated.knowledgeState(), current.revision() + 1));
            return true;
        }
        return false;
    }

    private static Memory copy(Memory m, String content, String source, double confidence,
                               Memory.Status status, Memory.KnowledgeState knowledgeState) {
        return new Memory(m.id(), m.scope(), m.owner(), content, source, m.createdAtMs(),
                m.tags(), confidence, status, m.validUntilMs(), m.permissions(),
                knowledgeState, m.revision());
    }

    /** query 分词：空白/标点切开、转小写；含中文的词再拆 2-gram。 */
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
