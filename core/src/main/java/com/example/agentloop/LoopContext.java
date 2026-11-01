package com.example.agentloop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 一次 loop 运行的现场：任务、已经走过的轮次、跨轮暂存区。
 * Actor / Verifier / StopCondition 都拿它当唯一入参，互不依赖。
 */
public final class LoopContext {

    private final String task;
    private final List<LoopStep> steps = new ArrayList<>();
    private final Map<String, Object> scratch = new HashMap<>();

    LoopContext(String task) {
        this.task = task;
    }

    public String task() {
        return task;
    }

    /** 当前轮次（1 起）；行动前调用 = 本轮序号。 */
    public int turn() {
        return steps.size() + 1;
    }

    public List<LoopStep> steps() {
        return Collections.unmodifiableList(steps);
    }

    public Optional<LoopStep> lastStep() {
        return steps.isEmpty() ? Optional.empty() : Optional.of(steps.get(steps.size() - 1));
    }

    /** 上一轮评估的改进意见（回流给本轮行动）；首轮为空。
     *  环境故障不算改进意见——那不归行动方管，别让它照着「改」。 */
    public Optional<String> lastFeedback() {
        return lastStep()
                .filter(s -> s.verdict().kind() != Verdict.Kind.ENV_FAIL)
                .map(s -> s.verdict().reason()).filter(r -> !r.isBlank());
    }

    /** 跨轮暂存：行动和评估之间要传递的任意状态（如中间产物、工具结果）。 */
    public Object scratch(String key) {
        return scratch.get(key);
    }

    public void scratch(String key, Object value) {
        scratch.put(key, value);
    }

    void record(LoopStep step) {
        steps.add(step);
    }
}
