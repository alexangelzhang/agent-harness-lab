package com.example.harness.skills;

import com.example.agent.tool.Tool;
import com.example.harness.memory.MemoryStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 任务单元检索：agent 按任务发现能力，而不是面对数千份工具说明（文章 #1）。
 *
 * <p>三个入口对应模型在任务层的三个动作：</p>
 * <ul>
 *   <li>{@link #listCapabilities}——「你能访问什么？」的答案，进系统提示；</li>
 *   <li>{@link #searchOperations}——按任务关键词在 capability 名/描述和
 *       工具名/描述上打分，返回排序后的 operation 候选；</li>
 *   <li>{@link #loadOperation}——只把命中的那个工具定义加载进上下文。</li>
 * </ul>
 *
 * <p>简单任务仍然是简单路径：检索只回一个工具时直接调用，
 * 不过 skill、不绕弯——直接调工具是正常路径，不是例外。</p>
 */
public final class OperationIndex {

    /** 一个 operation 候选：哪个能力下的哪个工具 + 相关度。 */
    public record Operation(String capability, Tool tool, double score) {
    }

    private final List<Capability> capabilities = new ArrayList<>();

    /** 登记一个能力（连接一个系统时做）。 */
    public OperationIndex register(Capability capability) {
        capabilities.add(capability);
        return this;
    }

    /** 能力清单（回答「你能访问什么」，也帮助发现请求有歧义）。 */
    public String listCapabilities() {
        StringBuilder sb = new StringBuilder("已连接的系统：");
        for (Capability c : capabilities) {
            sb.append('\n').append(c.manifestLine());
        }
        return sb.toString();
    }

    /** 按任务检索 operation：capability 名/描述权重 1，工具名/描述权重 2。 */
    public List<Operation> searchOperations(String query) {
        Set<String> terms = MemoryStore.terms(query);
        List<Operation> hits = new ArrayList<>();
        for (Capability cap : capabilities) {
            double capScore = overlap(terms, cap.name() + " " + cap.description());
            for (Tool tool : cap.tools()) {
                double score = capScore + 2 * overlap(terms, tool.name() + " " + tool.description());
                if (score > 0) {
                    hits.add(new Operation(cap.name(), tool, score / 3));
                }
            }
        }
        hits.sort(Comparator.comparingDouble(Operation::score).reversed());
        return hits;
    }

    /** 加载一个 operation 的工具定义（注入上下文的就这一份，不是整个目录）。 */
    public Optional<Tool> loadOperation(String capability, String toolName) {
        return capabilities.stream()
                .filter(c -> c.name().equals(capability))
                .flatMap(c -> c.tools().stream())
                .filter(t -> t.name().equals(toolName))
                .findFirst();
    }

    private static double overlap(Set<String> terms, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (terms.isEmpty()) {
            return 0;
        }
        int hit = 0;
        for (String term : terms) {
            if (lower.contains(term)) {
                hit++;
            }
        }
        return (double) hit / terms.size();
    }
}
