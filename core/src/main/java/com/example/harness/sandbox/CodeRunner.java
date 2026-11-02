package com.example.harness.sandbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 代码执行器：模型写程序，沙箱跑循环，结果一次性交还（文章 #5）。
 *
 * <p>经典 loop 让模型逐条编排调用——跨三个系统处理 500 条记录就是
 * 500 次模型调用，又慢又贵。这个原语把姿势倒过来：<b>模型只写一次
 * 处理逻辑（脚本），循环由沙箱内的解释器跑</b>，LLM 负责推理流程，
 * Harness 负责重复机械的执行。脚本经 {@link Sandbox#exec} 进沙箱边界，
 * 策略拦截、超时、审计一个不少。</p>
 *
 * <p>注意这是「真实执行」原语：配 {@link LocalSandbox} 用，
 * 脚本语言取决于沙箱里有什么解释器（场景 用 python3）。</p>
 */
public final class CodeRunner {

    private final Sandbox sandbox;
    private final Path workdir;

    public CodeRunner(Sandbox sandbox, Path workdir) {
        this.sandbox = sandbox;
        this.workdir = workdir;
    }

    /**
     * 把代码写进沙箱工作目录并执行。
     *
     * @param interpreter 解释器命令，如 python3 / bash
     * @param scriptName  脚本文件名（写在工作目录下）
     * @param code        模型生成的处理逻辑
     * @return 沙箱执行结果（退出码 + 双通道输出）
     */
    public Sandbox.ExecResult run(String interpreter, String scriptName, String code) {
        try {
            Files.createDirectories(workdir);
            Path script = workdir.resolve(scriptName);
            Files.writeString(script, code);
            return sandbox.exec(interpreter + " " + scriptName);
        } catch (IOException e) {
            throw new UncheckedIOException("脚本写入失败：" + scriptName, e);
        }
    }
}
