package com.example.langgraph4j.eval;

import java.util.List;
import java.util.Optional;

/**
 * 单次运行的轨迹（Trajectory）：对应《AI Agents: The Definitive Guide》Ch8 的
 * 「轨迹级行为评估」——传统 BLEU/ROUGE 只看最终文本，Agent 评估要看整条执行轨迹。
 *
 * @param trialIndex        第几次试验（1..k）
 * @param nodePath          依次执行的节点序列（含 __START__ / __END__ 标记）
 * @param llmCalls          本次运行 LLM 调用次数（CountingLlm 统计，-1 表示未接入）
 * @param durationMs        运行耗时
 * @param answer            从终态提取的答案（未完成则为空）
 * @param passed            本次运行是否通过判定器（Pass@k 的分子）
 * @param errorMessage      运行期错误（正常完成为空）
 * @param recursionLimitHit 是否触发 langgraph4j 的 recursionLimit 护栏
 */
public record Trajectory(
        int trialIndex,
        List<String> nodePath,
        int llmCalls,
        long durationMs,
        Optional<String> answer,
        boolean passed,
        Optional<String> errorMessage,
        boolean recursionLimitHit) {

    /** 步数 = 节点序列长度（Ch8 的步数惩罚指标输入）。 */
    public int steps() {
        return nodePath.size();
    }

    /** 轨迹冗余度：步数 / 去重后的实际节点数。比值越大说明在环里空转越多。 */
    public double redundancy() {
        long distinct = nodePath.stream().filter(n -> !n.startsWith("__")).distinct().count();
        return distinct == 0 ? 0 : (double) steps() / distinct;
    }
}
