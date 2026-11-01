package com.example.agentloop;

/**
 * loop 里一轮的完整记录（行动 + 评估 + 耗时），
 * 一轮一轮串起来就是这条 loop 的轨迹——评估和排障都看它。
 */
public record LoopStep(int turn, String output, Verdict verdict, long durationMs) {
}
