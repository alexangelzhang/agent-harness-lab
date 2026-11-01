package com.example.harness.context;

import com.example.langgraph4j.llm.LlmClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 响应缓存：同样的提示词不重复调模型（教程第 5 章「成本控制 · 缓存」）。
 *
 * <p>包装在任意 {@link LlmClient} 外层的透明缓存：key 是完整提示词，
 * 命中直接返回上次的结果，miss 才走委托。配合 {@code CountingLlm}
 * 一眼看到调用数下降——缓存的收益不是体感，是账本上的少烧。</p>
 *
 * <p>生产语义补齐（2026-10-31）：</p>
 * <ul>
 *   <li><b>TTL</b>：条目带写入时间，过期即视为未命中（{@link #expired} 可查账）；</li>
 *   <li><b>容量</b>：LRU 封顶（最久未用的先淘汰），防内存无限长；</li>
 *   <li><b>按谓词失效</b>：{@link #invalidate}——比如提示词里带日期的场景
 *       一键作废，不用等 TTL。</li>
 * </ul>
 */
public final class CachingLlm implements LlmClient {

    /** 一条缓存：内容 + 写入时间。 */
    private record Entry(String value, long writtenAt) {
    }

    private final LlmClient delegate;
    private final long ttlMillis;
    private final int maxEntries;
    private final Map<String, Entry> cache;
    private int hits;
    private int misses;
    private int expired;
    private int evicted;

    /** 不限时、不限容量的简易缓存。 */
    public CachingLlm(LlmClient delegate) {
        this(delegate, Long.MAX_VALUE, Integer.MAX_VALUE);
    }

    /**
     * @param ttlMillis  条目有效期（毫秒），Long.MAX_VALUE 表示永不过期
     * @param maxEntries 容量上限，超出淘汰最久未用
     */
    public CachingLlm(LlmClient delegate, long ttlMillis, int maxEntries) {
        this.delegate = delegate;
        this.ttlMillis = ttlMillis;
        this.maxEntries = maxEntries;
        // 访问序 LRU：get 时 LinkedHashMap 会把条目移到尾部
        this.cache = new LinkedHashMap<>(16, 0.75f, true);
    }

    @Override
    public synchronized String complete(String prompt) {
        Entry e = cache.get(prompt);
        if (e != null) {
            if (System.currentTimeMillis() - e.writtenAt() <= ttlMillis) {
                hits++;
                return e.value();
            }
            cache.remove(prompt);
            expired++;
            // 过期不算命中也不算未命中——单独记账，账要分清
        }
        misses++;
        String reply = delegate.complete(prompt);
        cache.put(prompt, new Entry(reply, System.currentTimeMillis()));
        evicted += evictIfNeeded();
        return reply;
    }

    /** 按谓词批量失效（如 prompt.contains("今日") 的全部作废）。 */
    public synchronized int invalidate(Predicate<String> which) {
        int before = cache.size();
        cache.keySet().removeIf(which);
        return before - cache.size();
    }

    /** 命中次数（省掉的调用）。 */
    public int hits() {
        return hits;
    }

    /** 未命中次数（真正烧了调用的）。 */
    public int misses() {
        return misses;
    }

    /** 过期次数（TTL 到点作废的）。 */
    public int expired() {
        return expired;
    }

    /** 淘汰次数（容量不够挤出去的）。 */
    public int evicted() {
        return evicted;
    }

    /** 当前缓存条数。 */
    public synchronized int size() {
        return cache.size();
    }

    /** 超容量时从头（最久未用）淘汰，返回淘汰条数。 */
    private int evictIfNeeded() {
        int removed = 0;
        while (cache.size() > maxEntries) {
            var it = cache.entrySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            it.next();
            it.remove();
            removed++;
        }
        return removed;
    }
}
