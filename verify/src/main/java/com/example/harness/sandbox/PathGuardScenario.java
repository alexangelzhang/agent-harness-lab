package com.example.harness.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 路径验证流水线演示（Definitive Guide Ch6 缺口补齐）。
 *
 * <p>同一份「agent 想访问的路径清单」，过 PathGuard 三层流水线：
 * 障眼法（{@code work/../secret.txt}）在规范化层剥掉，越出白名单的
 * （{@code ../../etc/passwd}）在层二被拒，白名单内但不存在的在层三
 * 被 runtime 校验拦住——每层拒绝带层与理由，审计可复盘。</p>
 *
 * <p>运行：{@code mvn exec:java@pathguard}（离线，用临时目录当真根）。</p>
 */
public class PathGuardScenario {

    public static void main(String[] args) throws Exception {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("PathGuard：规范化 → 白名单前缀 → 运行时校验");
        System.out.println(line);

        // 真建一个临时工作根，让运行时校验有真东西可查
        Path root = Files.createTempDirectory("pathguard-场景");
        Path work = Files.createDirectories(root.resolve("work"));
        Files.writeString(work.resolve("report.txt"), "季度报表");
        Files.writeString(root.resolve("secret.txt"), "不许读");

        PathGuard guard = new PathGuard(work, work);
        List<String> attempts = List.of(
                "report.txt",           // 正常读取：应放行
                "../secret.txt",        // 向上穿越：规范化后越出白名单，层二拒
                "../../etc/passwd",     // 经典穿透：连跳两级，层二拒
                "ghost.txt",            // 白名单内但不存在：层三拒
                "../work/report.txt");  // 绕一圈回到根内：应放行

        int pass = 0;
        int blocked = 0;
        System.out.println("白名单根：" + guard.roots());
        System.out.println();
        for (String raw : attempts) {
            PathGuard.Verdict v = guard.guard(raw);
            System.out.println("  agent 请求：" + raw);
            if (v.allowed()) {
                pass++;
                System.out.println("    → 放行，真实路径 " + v.normalizedPath());
            } else {
                blocked++;
                System.out.println("    → " + v.trace());
            }
        }

        boolean ok = pass == 2 && blocked == 3;
        System.out.println();
        System.out.println(line);
        System.out.println(ok
                ? "收尾：穿透在进文件系统之前就被拦——fail-closed，"
                        + "每层拒绝都留痕。"
                : "!! 拦截数不对（放行 " + pass + " / 拦截 " + blocked + "）");
        System.out.println(line);
        System.exit(ok ? 0 : 1);
    }
}
