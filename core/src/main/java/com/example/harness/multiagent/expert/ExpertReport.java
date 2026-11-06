package com.example.harness.multiagent.expert;

import java.util.List;

/**
 * 只读专家的收工报告：结论 + 审计轨迹。
 *
 * @param expert 专家名
 * @param conclusion 结论文本（进派单方上下文的那一份）
 * @param reads 读过的东西
 * @param denials 被策略拦下的工具调用（应为空或只有写/再委派）
 * @param tier 烧的模型档
 */
public record ExpertReport(String expert, String conclusion,
                           List<String> reads, List<String> denials,
                           ExpertSession.Tier tier) {

    /** 一行摘要，派单方日志里记账用。 */
    public String summaryLine() {
        return expert + "（" + tier + "）读了 " + reads.size()
                + " 处、被拦 " + denials.size() + " 次："
                + (conclusion.length() <= 60 ? conclusion : conclusion.substring(0, 60) + "…");
    }
}
