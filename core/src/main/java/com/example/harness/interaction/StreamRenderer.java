package com.example.harness.interaction;

/**
 * 流式渲染器：模型输出按块到达时怎么给人看（教程第 9 章「流式渲染」）。
 *
 * <p>实现只管两块事：{@link #chunk} 到一块渲染一块（打字机效果），
 * {@link #close} 收尾清理（关行、复位光标、留总结行）。
 * CLI 是 {@link CliRenderer}；Web 界面换皮实现同一接口即可。</p>
 */
public interface StreamRenderer extends AutoCloseable {

    /** 渲染一块输出。 */
    void chunk(String text);

    /** 收尾：输出结束后的清理与总结。 */
    void close();
}
