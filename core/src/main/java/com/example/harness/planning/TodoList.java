package com.example.harness.planning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务清单（教程第 8 章「Todo」）：长任务开工先列单子，干一项勾一项。
 *
 * <p>作用不是记账好看，是给模型一个「全局进度」的外部锚点——
 * 长任务里模型容易懈怠（做了三件宣布全做完），勾不掉的单子
 * 是最直白的反懈怠证据。全部勾完 {@link #allDone()} 才为真，
 * 没勾完谁说完成都不算。</p>
 *
 * <p>渲染进提示词（{@link #render}），模型每轮都能看到还剩什么。</p>
 */
public final class TodoList {

    /** 一条任务：done 显式记录，不靠事后猜。 */
    public record Item(String text, boolean done) {
    }

    private final Map<String, Boolean> items = new LinkedHashMap<>();

    /** 加任务（重复的合并，不报错）。 */
    public TodoList add(String task) {
        items.putIfAbsent(task, false);
        return this;
    }

    /** 勾掉一项；勾不在单子上的任务直接拒——清单是合同，不是许愿池。 */
    public TodoList check(String task) {
        if (!items.containsKey(task)) {
            throw new IllegalArgumentException("清单上没有这个任务：" + task);
        }
        items.put(task, true);
        return this;
    }

    /** 是否全部完成。 */
    public boolean allDone() {
        return !items.isEmpty() && items.values().stream().allMatch(Boolean::booleanValue);
    }

    /** 剩余任务数。 */
    public long remaining() {
        return items.values().stream().filter(d -> !d).count();
    }

    /** 快照（审计、演示打印用）。 */
    public List<Item> items() {
        List<Item> result = new ArrayList<>(items.size());
        items.forEach((k, v) -> result.add(new Item(k, v)));
        return List.copyOf(result);
    }

    /** 渲染成提示词：完成的打勾沉底，未完成的置顶。 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 任务清单（未完成的在前）\n");
        items.forEach((k, v) -> sb.append(v ? "  [x] " : "  [ ] ").append(k).append('\n'));
        return sb.toString().stripTrailing();
    }
}
