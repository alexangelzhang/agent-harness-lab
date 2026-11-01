package com.example.harness.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库：外部事实源，不是记忆的替代品（智能体AI 文章模块 #7）。
 *
 * <p>语义分工：知识库放<b>外部资料</b>（制度、文档、业务系统），长期记忆放
 * <b>与用户/任务/历史交互有关</b>的信息。两者可以共用存储基础设施，
 * 但管理规则不能混——产品价格以业务系统为准，不该把一次对话里的报价
 * 永久存成事实。{@code authoritative} 标记来源是否权威：冲突裁决时
 * 权威来源压过非权威。</p>
 */
public interface KnowledgeBase {

    /** 一条外部事实。 */
    record Fact(String content, String source, boolean authoritative) {
    }

    /** 按查询召回相关事实。 */
    List<Fact> lookup(String query);

    /** 没有外部知识的空实现（任务确实不需要第三路时的显式选择）。 */
    KnowledgeBase NONE = query -> List.of();

    /** 内存实现：关键词索引的静态资料库（离线确定性）。 */
    final class InMemory implements KnowledgeBase {

        private final Map<String, List<Fact>> byKeyword;

        public InMemory(Map<String, List<Fact>> byKeyword) {
            this.byKeyword = byKeyword;
        }

        @Override
        public List<Fact> lookup(String query) {
            Set<String> terms = MemoryStore.terms(query);
            List<Fact> hits = new ArrayList<>();
            for (Map.Entry<String, List<Fact>> e : byKeyword.entrySet()) {
                if (terms.stream().anyMatch(e.getKey()::contains)) {
                    hits.addAll(e.getValue());
                }
            }
            return hits;
        }
    }
}
