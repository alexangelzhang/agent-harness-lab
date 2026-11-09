package com.example.harness.memory;

import java.util.List;

/**
 * 夜间增量窗口的纯状态迁移。这里不调度任务：只有完整成功才把 since
 * 推进到调用者给出的上界；运行中的输入只留跳过记录，不会被隐式补抓。
 */
public final class MemoryNightlyWindow {

    private MemoryNightlyWindow() {
    }

    /** 候选会话及其运行状态；endedAtMs 是调用方提供的窗口时间。 */
    public record Input(String inputId, long endedAtMs, boolean running,
                        MemoryTranscript transcript) {
    }

    /** 本轮选择与显式边界。 */
    public record Selection(long sinceMs, long untilMs,
                            List<MemoryDreamingWorkflow.Input> inputs,
                            List<String> skippedInputIds) {
        public Selection {
            inputs = List.copyOf(inputs);
            skippedInputIds = List.copyOf(skippedInputIds);
        }
    }

    /** 下一次调用应使用的游标。 */
    public record Transition(long nextSince, boolean successful,
                             List<String> skippedInputIds) {
        public Transition {
            skippedInputIds = List.copyOf(skippedInputIds);
        }
    }

    /** 选择 (since, until] 内已结束的输入；运行中的同窗口输入单独列出。 */
    public static Selection select(long sinceMs, long untilMs, List<Input> candidates) {
        List<MemoryDreamingWorkflow.Input> selected = new java.util.ArrayList<>();
        List<String> skipped = new java.util.ArrayList<>();
        for (Input candidate : candidates) {
            if (candidate.endedAtMs() <= sinceMs || candidate.endedAtMs() > untilMs) {
                continue;
            }
            if (candidate.running()) {
                skipped.add(candidate.inputId());
            } else {
                selected.add(new MemoryDreamingWorkflow.Input(
                        candidate.inputId(), candidate.transcript()));
            }
        }
        return new Selection(sinceMs, untilMs, selected, skipped);
    }

    /** 报告必须覆盖所选输入并完整成功才推进；空窗口与部分失败都保留 since。 */
    public static Transition complete(Selection selection, MemoryDreamingWorkflow.Report report) {
        boolean complete = report.inputsExpected() == selection.inputs().size()
                && report.inputsProcessed() == selection.inputs().size()
                && report.failures().isEmpty() && report.successful();
        return new Transition(complete ? selection.untilMs() : selection.sinceMs(),
                complete, selection.skippedInputIds());
    }
}
