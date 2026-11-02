package com.example.harness.sandbox;

/**
 * 沙箱执行后端：编码 Agent Harness 的执行环境抽象（教程第 4 章的缺口补齐）。
 *
 * <p>工具循环里的「执行」有两类：调 LLM（本项目 {@code LlmClient} 已抽象）和
 * 动真实环境（跑命令、改文件）。后者以前散落在 场景 里，没有统一接口——
 * 沙箱补上这一层：模型给出的命令统一经 {@link #exec} 进沙箱，
 * <strong>副作用发生在沙箱边界内</strong>，审批（{@code ApprovalPolicy}）、
 * 策略拦截（{@link #beforeExec}）和审计（{@link #afterExec}）都挂在边界上，
 * 与 ToolGate「越权在进工具之前被拦」同款语义。</p>
 *
 * <p>两种实现：</p>
 * <ul>
 *   <li>{@link MemorySandbox}——内存假环境（内置小型文件系统 + 常用命令解释器），
 *       离线确定性，支持快照/恢复；</li>
 *   <li>{@link LocalSandbox}——真实命令执行（ProcessBuilder，超时强杀），
 *       快照/恢复显式不支持（第 7 章磁盘持久化仍是缺口，不假装做了）。</li>
 * </ul>
 *
 * <p>生命周期：{@link #create} → 任意 {@link #exec} → {@link #destroy}；
 * 钩子 {@link #beforeExec}/{@link #afterExec} 在每个命令前后触发，
 * 抛 {@link ExecDenied} 即拦截（命令不执行，副作用为零）。
 * 快照语义（{@link #snapshot}/{@link #restore}）是恢复能力的挂点：
 * 动手前留底、闯祸后回滚。</p>
 */
public interface Sandbox {

    /** 沙箱实例标识（审计、日志用）。 */
    String id();

    /**
     * 在沙箱内执行一条命令，返回退出码与输出。
     * 实现负责：钩子触发、超时、把环境错误翻译成非零退出码或异常。
     */
    ExecResult exec(String command);

    /** 创建沙箱（准备环境：建目录、预置文件等）。 */
    default void create() {
    }

    /** 销毁沙箱（回收资源；命令是一次一进程的，没有常驻物就不做事）。 */
    default void destroy() {
    }

    /** 打快照：以名字留一份现场，供 {@link #restore} 回滚。不支持的实现显式抛异常。 */
    default void snapshot(String name) {
        throw new UnsupportedOperationException(id() + " 不支持快照");
    }

    /** 恢复快照：把现场回滚到 {@link #snapshot} 时。 */
    default void restore(String name) {
        throw new UnsupportedOperationException(id() + " 不支持快照恢复");
    }

    /** 命令执行前钩子：做策略拦截（抛 {@link ExecDenied}）、审计打点、限流。 */
    default void beforeExec(String command) {
    }

    /** 命令执行后钩子：做日志、指标、留痕。 */
    default void afterExec(String command, ExecResult result) {
    }

    /** 一条命令的执行结果：退出码（约定 -1 为超时/被系统中断）+ 双通道输出 + 耗时。 */
    record ExecResult(int exitCode, String stdout, String stderr, long durationMs) {

        /** 命令是否成功。 */
        public boolean ok() {
            return exitCode == 0;
        }
    }

    /** 命令被策略拦截：未执行、无副作用，消息回到 agent 上下文可改提降级方案。 */
    final class ExecDenied extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ExecDenied(String message) {
            super(message);
        }
    }
}
