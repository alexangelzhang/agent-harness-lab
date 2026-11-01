package com.example.harness.skills;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 技能热刷新演示：进程不重启，新技能落盘即被捡起
 * （2026-10-31，尼恩文章「技能注入」链路的单进程版）。
 *
 * <p>技能目录就是 Manager→Worker 的那条管道：启动时目录是空的，
 * 运行中把 {@code git.skill} 写进目录（模拟 Manager 推送），
 * {@link SkillWatcher#scanNow} 比指纹发现新增 → 注册 → 立即可用；
 * 再改文件内容 → 指纹变了 → 热重载，进程全程没重启。</p>
 *
 * <p>运行：{@code mvn exec:java@hotreload}。</p>
 */
public class SkillHotReloadDemo {

    private static final Path SKILL_DIR = Path.of("runs", "hot-skills");

    public static void main(String[] args) {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("技能热刷新：目录是管道，指纹是契约，进程不重启");
        System.out.println(line);

        cleanup();
        try {
            Files.createDirectories(SKILL_DIR);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        EventBus bus = new EventBus();
        bus.on(SkillWatcher.SkillHotLoaded.class,
                e -> System.out.println("  〔事件〕热加载技能「" + e.skillName() + "」（" + e.kind()
                        + "，" + e.toolCount() + " 个工具）"));
        SkillRegistry registry = new SkillRegistry(bus);
        SkillWatcher watcher = new SkillWatcher(SKILL_DIR, registry, bus);

        System.out.println("启动扫描：" + watcher.scanNow() + "（目录是空的）");
        System.out.println("进程运行中……  Manager 推送 git.skill：");
        write("git.skill", """
                trigger: 提交记录
                tools: list_commits=列出本周提交; show_diff=查看指定提交的改动
                ---
                先 list_commits 看本周提交列表，用户点名再看 show_diff。
                """);

        System.out.println("热刷新扫描：" + watcher.scanNow());
        System.out.println("新任务立刻可用——请求「拉一下本周提交记录」：");
        registry.load("拉一下本周提交记录");
        System.out.println("  已加载清单：");
        System.out.println("   " + registry.loadedManifest().replace("\n", "\n   "));

        System.out.println();
        System.out.println("Manager 更新技能（追加 blame 工具）：");
        write("git.skill", """
                trigger: 提交记录
                tools: list_commits=列出本周提交; show_diff=查看指定提交的改动; blame=按行查提交责任人
                ---
                先 list_commits 看本周提交列表，用户点名再看 show_diff，追溯责任人用 blame。
                """);
        System.out.println("热刷新扫描：" + watcher.scanNow());
        registry.load("拉一下本周提交记录");
        long toolCount = registry.loaded().get(0).tools().size();
        System.out.println("重载后技能携带工具数：" + toolCount + "（3 = 热重载生效，进程没重启）");

        boolean ok = toolCount == 3;
        cleanup();
        System.out.println();
        System.out.println(line);
        System.out.println(ok
                ? "收尾：技能的下发收敛只靠目录里的文件，进程只是路过加载。"
                : "!! 热刷新没生效");
        System.out.println(line);
        System.exit(0);
    }

    private static void write(String name, String content) {
        try {
            Files.writeString(SKILL_DIR.resolve(name), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void cleanup() {
        if (!Files.exists(SKILL_DIR)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(SKILL_DIR)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
