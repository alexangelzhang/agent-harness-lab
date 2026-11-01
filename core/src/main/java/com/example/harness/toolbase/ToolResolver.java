package com.example.harness.toolbase;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 工具解析器：fail-closed 查找——未注册的工具 Phase0 直接拒（尼恩文章「三重门」第一门）。
 *
 * <p>闭包安全/失败即关闭：模型吐出注册表里没有的工具名时，<b>拒绝并留痕</b>，
 * 不猜、不降级、不找近似的——「类型检查」第一关。与
 * {@code agent.tool.ToolRegistry#find} 的区别：那里返回 Optional 靠调用方
 * 自觉，这里把「查不到就是 DENY」做成不可绕过的语义，且每次拒绝进审计。</p>
 */
public final class ToolResolver {

    /** 一次 Phase0 拒绝的留痕。 */
    public record Denied(String tool, String reason, long atMs) {
    }

    /** 未注册工具：查找阶段即被拒，执行从未发生。 */
    public static final class ToolNotFound extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ToolNotFound(String name) {
            super("DENIED: 未注册工具 '" + name + "'（fail-closed，不猜测执行）");
        }
    }

    /** 拒绝留痕的接收方：审计日志/告警，由 wiring 决定。 */
    @FunctionalInterface
    public interface Auditor {
        void onDenied(Denied denied);
    }

    private static final Auditor NO_OP = d -> {
    };

    private final Map<String, GovernedTool> tools = new LinkedHashMap<>();
    private final Auditor auditor;

    public ToolResolver() {
        this(null);
    }

    public ToolResolver(Auditor auditor) {
        this.auditor = auditor == null ? NO_OP : auditor;
    }

    /** 登记治理壳（注册即带治理，裸工具不进解析器）。 */
    public ToolResolver register(GovernedTool tool) {
        tools.put(tool.tool().name(), tool);
        return this;
    }

    /** 只看不解析（渲染工具清单用）。 */
    public Map<String, GovernedTool> all() {
        return Map.copyOf(tools);
    }

    /**
     * Phase0 解析：命中返回工具；未命中留痕并抛 {@link ToolNotFound}——
     * 异常消息就是回 agent 上下文的 DENIED 文本。
     */
    public GovernedTool resolve(String name) {
        GovernedTool t = tools.get(name);
        if (t == null) {
            auditor.onDenied(new Denied(name, "未注册工具（fail-closed）", System.currentTimeMillis()));
            throw new ToolNotFound(name);
        }
        return t;
    }

    /** Optional 形态（确实想自己处理 miss 的场景）；miss 同样留痕。 */
    public Optional<GovernedTool> find(String name) {
        Optional<GovernedTool> hit = Optional.ofNullable(tools.get(name));
        hit.ifPresentOrElse(t -> {
        }, () -> auditor.onDenied(new Denied(name, "未注册工具（find 探查）", System.currentTimeMillis())));
        return hit;
    }
}
