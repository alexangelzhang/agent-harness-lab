package com.example.harness;

/**
 * 工具权限网关：生产环境所有工具调用的统一入口。
 *
 * <p>工具先声明需要的权限级别（{@link Level}），网关按当前用户角色在执行前
 * 校验——没权限的调用<b>在进工具之前</b>就被拒掉，工具本身的副作用不会发生。
 * 对应 LangChain Deep Agents 的 skills 工具绑定 + 按用户权限解析：
 * Skill 选出任务需要的工具组，解析函数再按运行环境返回当前用户有权使用的工具；
 * 这里把「第二道收紧」独立成一个可测试的网关。</p>
 *
 * <p>拒绝不是静默的：被拒的消息作为工具返回交给 agent（它可以改提建议），
 * 同时通过 {@link Auditor} 回调记档——审计要能看到「谁、试图用什么工具、
 * 需要什么权限、实际是什么角色」。权限不足的调用和合法调用走同一条轨迹，
 * 这正是生产环境的底线：越权尝试留痕。</p>
 */
public final class ToolGate {

    /** 权限级别，按序升级：读 < 写 < 管理。角色级别 ≥ 工具要求才放行。 */
    public enum Level { READ, WRITE, ADMIN }

    /** 一个受管工具：名字和所需权限在调用点声明，实现就是工具本身。 */
    @FunctionalInterface
    public interface Tool {
        String run(String input) throws Exception;
    }

    /** 一次越权尝试的留痕。 */
    public record Denied(String tool, String user, Level required, Level actual) {
    }

    /** 越权留痕的接收方：记日志、写档案、触发告警，由 wiring 决定。 */
    @FunctionalInterface
    public interface Auditor {
        void onDenied(Denied denied);
    }

    private static final Auditor NO_OP = d -> {
    };

    private final String user;
    private final Level role;
    private final Auditor auditor;

    public ToolGate(String user, Level role) {
        this(user, role, NO_OP);
    }

    public ToolGate(String user, Level role, Auditor auditor) {
        this.user = user;
        this.role = role;
        this.auditor = auditor == null ? NO_OP : auditor;
    }

    public String user() {
        return user;
    }

    public Level role() {
        return role;
    }

    /** 角色是否够格用这个级别的工具。 */
    public boolean permitted(Level required) {
        return role.ordinal() >= required.ordinal();
    }

    /**
     * 受管调用：权限够 → 执行工具；不够 → 留痕并返回 DENIED 消息（不进工具）。
     * DENIED 消息会回到 agent 的上下文里，轨迹上这次尝试可见。
     */
    public String call(String name, Level required, Tool tool, String input) throws Exception {
        if (!permitted(required)) {
            auditor.onDenied(new Denied(name, user, required, role));
            return "DENIED: 用户 " + user + "（" + role + "）无权使用工具 " + name
                    + "（需要 " + required + " 权限）";
        }
        return tool.run(input);
    }

    /**
     * 不带受检异常的受管调用：工具抛错包成 RuntimeException。
     * 供 {@code AgentLoop.Actor} / 图节点这类不处理受检异常的实现使用。
     */
    public String callUnchecked(String name, Level required, Tool tool, String input) {
        try {
            return call(name, required, tool, input);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("工具 " + name + " 执行失败", e);
        }
    }

    /** 无入参的受管调用简写（工具输出固定时）。 */
    public String callUnchecked(String name, Level required, String fixedOutput) {
        return callUnchecked(name, required, in -> fixedOutput, "");
    }
}
