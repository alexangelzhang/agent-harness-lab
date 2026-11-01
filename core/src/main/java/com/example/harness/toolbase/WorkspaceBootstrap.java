package com.example.harness.toolbase;

import java.util.List;
import java.util.Map;

/**
 * 装配工厂：所有入口共用的 Workspace 构造点（尼恩文章「工具注入」段）。
 *
 * <p>{@link #build()} 是唯一装配入口——CLI 入口、Web 入口、测试入口
 * 全部调它，拿到的东西一字不差。想给 Agent 加内置工具、挂审计钩子、
 * 开关运行模式，只改这个类。</p>
 */
public final class WorkspaceBootstrap {

    private WorkspaceBootstrap() {
    }

    /** 装配一个 Workspace：内置工具 + 生命周期钩子 + 运行模式。 */
    public static Workspace build() {
        List<SchemaTool> tools = List.of(
                new SchemaTool("read_file", "按路径读文件", List.of(
                        new SchemaTool.Param("path", "string", true, "文件路径")),
                        in -> "（读取）" + in),
                new SchemaTool("run_shell", "在沙箱里执行命令", List.of(
                        new SchemaTool.Param("command", "string", true, "要执行的命令")),
                        in -> "（执行）" + in),
                new SchemaTool("send_message", "给指定渠道发消息", List.of(
                        new SchemaTool.Param("channel", "string", true, "目标渠道"),
                        new SchemaTool.Param("text", "string", true, "消息内容")),
                        in -> "（已发送）" + in));
        List<Workspace.Hook> hooks = List.of(
                new Workspace.Hook("audit-log", "before", cmd ->
                        System.out.println("    〔audit〕before: " + cmd)),
                new Workspace.Hook("timeout-guard", "after", cmd ->
                        System.out.println("    〔guard〕after: " + cmd + "（耗时在阈值内）")));
        Map<String, String> modes = Map.of(
                "network", "allowed",
                "sandbox", "enabled");
        return new Workspace(tools, hooks, modes);
    }
}
