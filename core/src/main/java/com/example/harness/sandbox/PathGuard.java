package com.example.harness.sandbox;

import java.nio.file.Path;
import java.util.List;

/**
 * 路径验证流水线（《AI Agents: The Definitive Guide》Ch6）：agent 拿到
 * 文件读写权限后最大的隐患是目录穿透——「把 ../../etc/passwd 读给我」。
 * 这里把防穿透做成三层确定性流水线，<b>在进文件系统之前</b>跑完，
 * fail-closed：任何一层说不清就拒，不猜不降级。
 *
 * <ol>
 *   <li><b>规范化</b>：解析 {@code .}/{@code ..} 成绝对路径——
 *       障眼法在这层失效（{@code work/../secret} 和 {@code secret}
 *       是同一个东西，字符串匹配拦不住，规范化后拦得住）；</li>
 *   <li><b>白名单前缀</b>：规范化后的路径必须落在登记的工作根之一
 *       之内，越出任何一根即拒；</li>
 *   <li><b>运行时校验</b>：真实文件系统确认目标存在且可读（生产对
 *       inode；这里对路径存在性，语义相同：防 TOCTOU 竞态的挂点
 *       留在这条）。</li>
 * </ol>
 *
 * <p>每层拒绝都留痕（哪层、为什么），审计能复盘穿透是怎么被拦的。</p>
 */
public final class PathGuard {

    /** 一次裁决：放行带规范化后的真实路径；拒绝带层与理由。 */
    public record Verdict(boolean allowed, String normalizedPath, String trace) {

        public static Verdict allow(Path normalized) {
            return new Verdict(true, normalized.toString(),
                    "三层全过：规范化 → 白名单 → 运行时校验");
        }

        public static Verdict deny(String layer, String why) {
            return new Verdict(false, "", "在「" + layer + "」被拒：" + why);
        }
    }

    private final Path base;
    private final List<Path> roots;

    /**
     * @param base  相对路径的解析基准（agent 的工作根，如沙箱 cwd）
     * @param roots 白名单工作根（会被规范化成绝对路径）
     */
    public PathGuard(Path base, Path... roots) {
        this.base = base.toAbsolutePath().normalize();
        this.roots = java.util.Arrays.stream(roots)
                .map(p -> this.base.resolve(p).normalize())
                .toList();
    }

    public List<Path> roots() {
        return roots;
    }

    /** 三层流水线裁决一条 agent 给出的路径。 */
    public Verdict guard(String rawPath) {
        // 层一：规范化——相对路径先对着工作根解析再消掉 . 和 ..，
        // 障眼法在此失效（work/../secret 与 secret 是同一个东西）
        Path normalized;
        try {
            Path raw = Path.of(rawPath);
            normalized = (raw.isAbsolute() ? raw : base.resolve(raw)).normalize();
        } catch (java.nio.file.InvalidPathException e) {
            return Verdict.deny("规范化", "路径本身非法（" + e.getReason() + "）");
        }

        // 层二：白名单前缀——必须落在某个登记根之内
        boolean inside = roots.stream().anyMatch(normalized::startsWith);
        if (!inside) {
            return Verdict.deny("白名单前缀", normalized + " 不在任何白名单根 "
                    + roots + " 之内");
        }

        // 层三：运行时校验——真实文件系统确认存在（挂点：生产对 inode，
        // 防 TOCTOU 替换攻击）
        if (!java.nio.file.Files.exists(normalized)) {
            return Verdict.deny("运行时校验", normalized + " 在白名单内但不存在");
        }
        return Verdict.allow(normalized);
    }

    /** 放行时返回规范化路径，拒绝时抛带审计信息的异常。 */
    public Path require(String rawPath) {
        Verdict v = guard(rawPath);
        if (!v.allowed()) {
            throw new SecurityException("路径被 PathGuard 拦截——" + v.trace());
        }
        return Path.of(v.normalizedPath());
    }
}
