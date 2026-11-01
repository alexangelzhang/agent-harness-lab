package com.example.langgraph4j.eval;

import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.AgentState;

import java.util.function.Predicate;

/**
 * 对抗探针：一次「必失败的运行」，验证失败方式符合预期。
 *
 * <p>AWS Bedrock AgentCore 平台文里的「对抗双胞胎」做法——每个放行测试配一个
 * 对抗探针，该拒的没拒，门禁就是摆设。这里的探针断言的是失败方式对得上：
 * 步数护栏真的拦了（recursionLimitHit）、judge 复核真的打回了（JUDGE_REJECT）、
 * 非法输入真的报错了。断言不成立 = 该拒没拒 = 探针失败。
 *
 * @param <S> 图的状态类型
 */
public final class AdversarialProbe<S extends AgentState> {

    private final String name;
    private final EvalHarness.Trial<S> trial;
    private final Predicate<TrajectoryCollector.RawRun<S>> expectedDenial;

    public AdversarialProbe(String name,
                            EvalHarness.Trial<S> trial,
                            Predicate<TrajectoryCollector.RawRun<S>> expectedDenial) {
        this.name = name;
        this.trial = trial;
        this.expectedDenial = expectedDenial;
    }

    /** 探针结果：denied = 如预期被拒；denied=false 就是「该拒没拒」。 */
    public record Result(String name, boolean denied, String actual) {
    }

    public Result run() {
        var raw = new TrajectoryCollector<>(trial.graph()).run(
                trial.inputs(),
                RunnableConfig.builder().threadId("probe-" + name).build());
        boolean denied = expectedDenial.test(raw);
        String actual = raw.errorMessage()
                .map(e -> "报错: " + e)
                .orElseGet(() -> "正常跑完（终态 verdict="
                        + raw.finalState().map(s -> s.<String>value("verdict").orElse("")).orElse("无")
                        + "，路径 " + String.join("→", raw.nodePath()) + "）");
        return new Result(name, denied, actual);
    }
}
