package com.example.harness.sandbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * 本地沙箱：在工作目录里真实执行命令（bash -c，一次一进程，超时强杀）。
 *
 * <p>给「剧本之外、真要动手」的场景用：输出是真实环境的回响，退出码、
 * stderr、耗时都真实。策略拦截由构造传入的 {@code deny} 谓词在
 * <strong>进程启动之前</strong>执行——被拦的命令连进程都不会创建，
 * 副作用为零（同 ToolGate 语义）。</p>
 *
 * <p>磁盘快照（2026-10-31 补齐，教程第 7 章）：{@link #snapshot} 把
 * 工作目录整树拷贝到快照目录（{@code runs/snapshots/<沙箱id>/<名字>}），
 * {@link #restore} 原样拷回——「动手前留底、闯祸后回滚」对真实目录同样成立。
 * 拷贝跳过构建产物与版本库（{@code target/}、{@code .git/}）和快照目录自身，
 * 避免自引用爆炸。云端后端不在这里假装：{@link Sandbox} 接口本身就是
 * 换实现的挂点（远程执行后端实现同一接口即可接入）。</p>
 */
public final class LocalSandbox implements Sandbox {

    private final String id;
    private final Path cwd;
    private final Duration timeout;
    private final Predicate<String> deny;
    private final Path snapshotRoot;

    /**
     * @param deny 策略谓词：对命令返回 true 即拦（ExecDenied），null 不拦
     * @param snapshotRoot 快照存放目录；null 用默认 {@code runs/snapshots/<id>}
     */
    public LocalSandbox(String id, Path cwd, Duration timeout, Predicate<String> deny,
                        Path snapshotRoot) {
        this.id = id;
        this.cwd = cwd;
        this.timeout = timeout;
        this.deny = deny;
        this.snapshotRoot = snapshotRoot != null ? snapshotRoot
                : Path.of("runs", "snapshots", id);
    }

    /** 无策略拦截的便捷构造。 */
    public LocalSandbox(String id, Path cwd, Duration timeout) {
        this(id, cwd, timeout, null, null);
    }

    /** 带策略拦截的便捷构造。 */
    public LocalSandbox(String id, Path cwd, Duration timeout, Predicate<String> deny) {
        this(id, cwd, timeout, deny, null);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public void create() {
        try {
            Files.createDirectories(cwd);
        } catch (IOException e) {
            throw new UncheckedIOException("沙箱工作目录创建失败：" + cwd, e);
        }
    }

    @Override
    public ExecResult exec(String command) {
        if (deny != null && deny.test(command)) {
            throw new ExecDenied("策略拦截：'" + command + "' 被沙箱策略拒绝，未执行");
        }
        beforeExec(command);
        long t0 = System.nanoTime();
        Process process;
        try {
            process = new ProcessBuilder("bash", "-c", command)
                    .directory(cwd.toFile())
                    .redirectErrorStream(false)
                    .start();
        } catch (IOException e) {
            throw new UncheckedIOException("命令启动失败：" + command, e);
        }
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("等待命令执行被中断", e);
        }
        ExecResult result;
        try {
            if (!finished) {
                process.destroyForcibly();
                result = new ExecResult(-1, "", "命令执行超时（>" + timeout.toMillis() + "ms），已强杀",
                        (System.nanoTime() - t0) / 1_000_000);
            } else {
                String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                result = new ExecResult(process.exitValue(), stdout.stripTrailing(),
                        stderr.stripTrailing(), (System.nanoTime() - t0) / 1_000_000);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取命令输出失败：" + command, e);
        }
        afterExec(command, result);
        return result;
    }

    @Override
    public void snapshot(String name) {
        Path target = snapshotRoot.resolve(name);
        copyTree(cwd, target, true);
        System.out.println("   〔沙箱〕快照 " + name + " 已存到 " + target);
    }

    @Override
    public void restore(String name) {
        Path source = snapshotRoot.resolve(name);
        if (!Files.isDirectory(source)) {
            throw new IllegalStateException("没有名为 " + name + " 的快照：" + source);
        }
        copyTree(source, cwd, false);
        System.out.println("   〔沙箱〕已从快照 " + name + " 恢复到 " + cwd);
    }

    /**
     * 整树拷贝；{@code snapshot=true} 时跳过产物/版本库/快照目录本身
     * （快照方向源是工作目录），{@code false} 时从快照拷回（源是快照目录，
     * 本就不含产物，无需排除，但目标里的快照目录仍要跳过防自写）。
     */
    private void copyTree(Path source, Path target, boolean skippingNoise) {
        Path absoluteSnapshotRoot = snapshotRoot.toAbsolutePath().normalize();
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    Path normalized = dir.toAbsolutePath().normalize();
                    // 只在「存快照」方向排除快照目录（目标是它、源是工作目录）；
                    // 「恢复快照」方向源就是快照目录本身，不能排除
                    if (skippingNoise && (isNoise(normalized)
                            || normalized.startsWith(absoluteSnapshotRoot))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Path normalized = file.toAbsolutePath().normalize();
                    if (skippingNoise && (isNoise(normalized)
                            || normalized.startsWith(absoluteSnapshotRoot))) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path dest = target.resolve(source.relativize(file).toString());
                    Files.createDirectories(dest.getParent());
                    Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("快照拷贝失败（" + source + " → " + target + "）", e);
        }
    }

    /** 构建产物与版本库不进快照。 */
    private static boolean isNoise(Path path) {
        String name = path.getFileName() != null ? path.getFileName().toString() : "";
        return name.equals("target") || name.equals(".git") || name.equals("node_modules")
                || name.equals(".idea");
    }
}
