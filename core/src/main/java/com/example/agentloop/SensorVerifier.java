package com.example.agentloop;

import com.example.harness.sensor.CodeChange;
import com.example.harness.sensor.SensorSuite;

/**
 * 带感知器的验收方：先走内层验收，不达标再让 SensorSuite 对产出
 * 过一遍——发现以「问题 + 怎么改」的结构化块追加进回流理由
 * （正向 prompt injection，Fowler：信号本身为 LLM 消费优化）。
 *
 * <p>对 AgentLoop 零侵入：它就是 {@link Verifier} 的一个实现，
 * 装饰任意内层验收。行动—验收—感知—重试，闭环比裸 loop 多一环；
 * 感知器拦住的结构性问题（占位符/超长/重复/适应度），不用烧
 * LLM 验收的额度去碰运气。</p>
 */
public final class SensorVerifier implements AgentLoop.Verifier {

    private final AgentLoop.Verifier inner;
    private final SensorSuite sensors;
    private final String fileName;
    private int runIndex;

    public SensorVerifier(AgentLoop.Verifier inner, SensorSuite sensors, String fileName) {
        this.inner = inner;
        this.sensors = sensors;
        this.fileName = fileName;
    }

    @Override
    public Verdict verify(LoopContext ctx, String actionOutput) throws Exception {
        Verdict verdict = inner.verify(ctx, actionOutput);
        if (verdict.done() || verdict.kind() != Verdict.Kind.RETRY) {
            return verdict;
        }
        runIndex++;
        SensorSuite.Report report = sensors.run(
                new CodeChange(fileName, actionOutput), runIndex);
        String block = report.toPromptBlock();
        if (block.isEmpty()) {
            return verdict;
        }
        return Verdict.retry(verdict.reason() + "\n" + block);
    }
}
