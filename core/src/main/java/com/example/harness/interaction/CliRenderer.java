package com.example.harness.interaction;

/**
 * CLI 流式渲染器：块到块打印，最后补耗时统计行并复位。
 *
 * <p>刻意保持朴素——演示「输出边到边看」的骨架：
 * 每个 chunk 直接上屏不换行，{@link #close} 时换行、
 * 打印总字节与块数、复位计数（退出清理语义）。</p>
 */
public final class CliRenderer implements StreamRenderer {

    private int chunks;
    private int bytes;

    @Override
    public void chunk(String text) {
        System.out.print(text);
        System.out.flush();
        chunks++;
        bytes += text.length();
    }

    @Override
    public void close() {
        System.out.println();
        System.out.printf("〔渲染器〕%d 块 / %d 字已呈现，行复位，计数清零%n", chunks, bytes);
        chunks = 0;
        bytes = 0;
    }
}
