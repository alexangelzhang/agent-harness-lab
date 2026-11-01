package com.example.multiagent;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Workflow 模式（多 Agent 架构选型之一）：<b>控制流由代码写死</b>，
 * 模型只负责执行具体某一道工序，不决定流程走向。
 *
 * <p>适合工序固定、依赖清楚的场景（报表、审批、文档流水线）——
 * 成本最低、延迟最低、最好测试、最好审计，出了问题逐步排查，
 * 没有「到底是哪个 Agent 的锅」这种扯皮。短板：出现「下一步走 A
 * 还是 B」需要临场判断的分支就笨拙，该换 Supervisor。</p>
 *
 * <p>工序可以是模型步（包装在 {@link Step#work} 里）也可以是确定性步——
 * 分工明确：代码管走向，模型管内容。</p>
 */
public final class WorkflowPattern {

    /** 一道工序：名字 + 对上游产物做加工。 */
    public record Step(String name, Function<String, String> work) {
    }

    private WorkflowPattern() {
    }

    /** 顺序执行全部工序，每道拿到上一道的产出；返回最终产物。 */
    public static String run(String task, List<Step> steps,
                             BiConsumer<String, String> trace) {
        String artifact = task;
        for (Step s : steps) {
            artifact = s.work().apply(artifact);
            trace.accept(s.name(), artifact);
        }
        return artifact;
    }
}
