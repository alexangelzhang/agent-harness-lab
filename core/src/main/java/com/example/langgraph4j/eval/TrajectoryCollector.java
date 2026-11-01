package com.example.langgraph4j.eval;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.AgentState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 轨迹采集器：包装一个 CompiledGraph，用 {@code stream(GraphInput, RunnableConfig)}
 * （1.9.6 中非废弃的重载）逐 superstep 拉出 NodeOutput，记录节点序列与终态。
 *
 * <p>对应书中 Ch8「完整的时间线捕获（Trajectory Rollout）」：观测、动作、
 * 状态快照逐步对齐展开，是后续 Pass@k / 步数 / 冗余度统计的原始数据。
 */
public final class TrajectoryCollector<S extends AgentState> {

    /** 单次原始运行结果（采集层），答案提取与通过判定交给 EvalHarness。 */
    public record RawRun<S extends AgentState>(
            List<String> nodePath,
            Optional<S> finalState,
            Optional<String> errorMessage,
            boolean recursionLimitHit,
            long durationMs) {
    }

    private final CompiledGraph<S> graph;

    public TrajectoryCollector(CompiledGraph<S> graph) {
        this.graph = graph;
    }

    public RawRun<S> run(Map<String, Object> inputs, RunnableConfig config) {
        List<String> nodePath = new ArrayList<>();
        Optional<S> finalState = Optional.empty();
        Optional<String> error = Optional.empty();
        boolean limitHit = false;
        long start = System.nanoTime();
        try {
            var generator = graph.stream(GraphInput.args(inputs), config);
            for (NodeOutput<S> out : generator) {
                nodePath.add(out.node());
                finalState = Optional.of(out.state());
            }
        } catch (Throwable t) {
            // langgraph4j 的异步执行会把异常包在 RuntimeException 里，沿 cause 链找关键信息
            String chain = messageChain(t);
            limitHit = chain.contains("Maximum number of iterations");
            error = Optional.of(chain);
        }
        return new RawRun<>(List.copyOf(nodePath), finalState, error, limitHit,
                (System.nanoTime() - start) / 1_000_000);
    }

    private static String messageChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null && sb.length() < 500; c = c.getCause()) {
            if (c.getMessage() != null) {
                sb.append(c.getMessage()).append(" | ");
            }
        }
        return sb.toString();
    }
}
