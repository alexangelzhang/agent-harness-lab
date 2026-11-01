package com.example.harness.toolbase;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 统一装配点：Agent 启动时把工具、钩子、运行模式一次装配进 Workspace
 * （尼恩文章「工具注入」段）。
 *
 * <p>QwenPaw 的 {@code WorkspaceBootstrapFactory.build_kwargs()} 的要点：
 * Web 入口和 CLI 入口调<b>同一个</b>工厂，装配结果完全一致——不会出现
 * Web 能调、CLI 调不了的能力漂移。这里同理：{@link #build()} 无参纯函数，
 * 入口只管调它；加工具/改模式只动这一个地方。</p>
 *
 * @param tools 内置工具列表
 * @param hooks 生命周期钩子（执行前后回调：审计、超时控制）
 * @param modes 运行模式配置（是否允许联网、是否启用沙箱……）
 */
public record Workspace(List<SchemaTool> tools, List<Hook> hooks, Map<String, String> modes) {

    /** 一个生命周期钩子。 */
    public record Hook(String name, String phase, Consumer<String> action) {
    }

    /** 装配结果摘要（入口自检/日志用）：工具名 + 钩子名 + 模式项。 */
    public String assemblyDigest() {
        StringBuilder sb = new StringBuilder("tools=");
        tools.forEach(t -> sb.append(t.name()).append(','));
        sb.setLength(sb.length() - 1);
        sb.append(" hooks=");
        hooks.forEach(h -> sb.append(h.name()).append(','));
        sb.setLength(sb.length() - 1);
        sb.append(" modes=").append(modes);
        return sb.toString();
    }
}
