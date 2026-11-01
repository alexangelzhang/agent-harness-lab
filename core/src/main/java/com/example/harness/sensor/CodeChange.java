package com.example.harness.sensor;

/**
 * 一次待检查的变更：文件名 + 完整内容（演示里是单文件产出，
 * 生产接 diff/多文件是挂点，接口不变）。
 */
public record CodeChange(String fileName, String content) {

    /** 改动行数粗算：非空行计数（没有真 diff，演示够用）。 */
    public int touchedLines() {
        return (int) content.lines().filter(l -> !l.isBlank()).count();
    }
}
