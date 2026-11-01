package com.example.harness.sandbox;

import com.example.langgraph4j.llm.ScriptedLlm;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * 沙箱执行后端演示（教程第 4 章落地 + 第 7 章内存版快照恢复）。
 *
 * <p>场景一「内存沙箱 + 快照恢复」：剧本模型逐步探查一个内存代码库
 * （cat → grep），然后执行 rm -rf——动手前打快照 v1，删完恢复，
 * 文件原样回来。全程离线、零副作用。</p>
 *
 * <p>场景二「本地沙箱 + 策略拦截」：真实工作目录里执行无害命令，
 * 展示真实退出码 / stderr / 耗时；deny 谓词在进程启动前拦下危险命令
 * （rm -rf），被拦的命令连进程都不会创建。</p>
 *
 * <p>运行：{@code mvn exec:java@sandbox}。</p>
 */
public class SandboxDemo {

    public static void main(String[] args) {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("沙箱执行后端：命令从模型出来，先进沙箱再碰环境");
        System.out.println(line);
        scenarioMemorySnapshot();
        scenarioLocalGuard();
        scenarioDiskSnapshot();
        System.out.println();
        System.out.println(line);
        System.out.println("收尾：执行层抽象落地——审批/策略/审计都挂在沙箱边界上；");
        System.out.println("内存版与本地版都支持快照恢复（本地版落盘 runs/snapshots/）；");
        System.out.println("云端后端是 Sandbox 接口的换实现挂点，不在此处假装。");
        System.out.println(line);
        System.exit(0);
    }

    /** 场景一：内存沙箱——探查代码库、删库、快照恢复。 */
    private static void scenarioMemorySnapshot() {
        header("场景一：内存沙箱 + 快照恢复", "动手前留底，闯祸后回滚（离线、零副作用）");
        Sandbox box = new MemorySandbox("mem-1", Map.of(
                "src/app.ts", """
                        export function main() {
                          // TODO: 接入权限校验
                          console.log('hello');
                        }
                        """,
                "README.md", "# TeensyCode\n"));
        box.create();

        // 剧本模型：探查 → 闯祸 → 确认恢复，命令只回一条或 DONE
        ScriptedLlm llm = new ScriptedLlm()
                .says("cat src/app.ts")
                .says("grep -rn TODO src")
                .says("rm -rf src")
                .says("cat src/app.ts")
                .says("DONE");

        String lastOutput = "";
        for (int turn = 1; ; turn++) {
            String command = llm.complete(
                    "你在探查一个代码库。上一步输出：\n" + lastOutput + "\n下一步只回一条命令，探查完毕回 DONE");
            System.out.println("模型  → " + command);
            if (command.equals("DONE")) {
                break;
            }
            boolean dangerous = command.equals("rm -rf src");
            if (dangerous) {
                box.snapshot("v1");
                System.out.println("沙箱  ── 已打快照 v1（动手前留底）");
            }
            Sandbox.ExecResult r = box.exec(command);
            print("沙箱", r);
            lastOutput = r.stdout().isEmpty() ? r.stderr() : r.stdout();
            if (dangerous) {
                box.restore("v1");
                System.out.println("沙箱  ── 已回滚到 v1：src/app.ts 回来了 = "
                        + ((MemorySandbox) box).exists("src/app.ts"));
            }
        }
        System.out.println("沙箱  命令留痕：" + ((MemorySandbox) box).commandLog());
        box.destroy();
    }

    /** 场景二：本地沙箱——真实执行无害命令，策略在进程启动前拦危险命令。 */
    private static void scenarioLocalGuard() {
        header("场景二：本地沙箱 + 策略拦截", "真实退出码/stderr/耗时；deny 谓词先于进程创建");
        Path workspace = Path.of(".").toAbsolutePath().normalize();
        Sandbox box = new LocalSandbox("local-1", workspace, Duration.ofSeconds(5),
                command -> command.contains("rm -rf"));
        box.create();

        ScriptedLlm llm = new ScriptedLlm()
                .says("echo 你好，来自本地沙箱")
                .says("ls pom.xml README.md")
                .says("ls 不存在的文件.txt")
                .says("rm -rf /")
                .says("echo 还活着，继续干活")
                .says("DONE");

        String lastOutput = "";
        for (int turn = 1; ; turn++) {
            String command = llm.complete(
                    "你在本机工作目录里干活。上一步输出：\n" + lastOutput + "\n下一步只回一条命令，干完回 DONE");
            System.out.println("模型  → " + command);
            if (command.equals("DONE")) {
                break;
            }
            try {
                Sandbox.ExecResult r = box.exec(command);
                print("沙箱", r);
                lastOutput = r.stdout().isEmpty() ? r.stderr() : r.stdout();
            } catch (Sandbox.ExecDenied e) {
                System.out.println("沙箱  ✋ " + e.getMessage());
                lastOutput = "命令被策略拦截：" + e.getMessage();
            }
        }
        box.destroy();
    }

    /** 场景三：磁盘快照——真实目录的留底与回滚（教程第 7 章补齐）。 */
    private static void scenarioDiskSnapshot() {
        header("场景三：磁盘快照", "真实目录动手前留底，闯祸后整树拷回");
        Path workdir = Path.of("runs", "sandbox-demo-workdir");
        Sandbox box = new LocalSandbox("disk-1", workdir, Duration.ofSeconds(5),
                command -> command.contains("rm -rf /"));
        box.create();
        try {
            ScriptedLlm llm = new ScriptedLlm()
                    .says("echo v1-方案 > note.txt")
                    .says("cat note.txt")
                    .says("echo v2-覆盖 > note.txt")
                    .says("cat note.txt")
                    .says("cat note.txt")
                    .says("DONE");
            String lastOutput = "";
            int restoreAt = 4; // 第 4 条命令（cat v2）之后回滚
            int step = 0;
            for (; ; ) {
                String command = llm.complete(
                        "你在沙箱工作目录里改文件。上一步输出：\n" + lastOutput
                                + "\n下一步只回一条命令，完事回 DONE");
                System.out.println("模型  → " + command);
                if (command.equals("DONE")) {
                    break;
                }
                step++;
                Sandbox.ExecResult r = box.exec(command);
                print("沙箱", r);
                lastOutput = r.stdout().isEmpty() ? r.stderr() : r.stdout();
                if (step == 2) {
                    box.snapshot("v1");
                }
                if (step == restoreAt) {
                    box.restore("v1");
                    r = box.exec("cat note.txt");
                    System.out.println("沙箱  ── 回滚后重新查看：");
                    print("沙箱", r);
                    lastOutput = r.stdout();
                }
            }
        } finally {
            box.destroy();
            cleanup(workdir);
        }
    }

    /** 演示收尾：删掉沙箱工作目录（快照目录留着，当磁盘留底的实物证据）。 */
    private static void cleanup(Path dir) {
        try (var walk = java.nio.file.Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .map(java.nio.file.Path::toFile)
                    .forEach(java.io.File::delete);
        } catch (java.io.IOException e) {
            System.out.println("（清理工作目录失败，无妨：" + e.getMessage() + "）");
        }
    }

    // ---- 内部工具 ----

    private static void header(String title, String subtitle) {
        System.out.println();
        System.out.println("-".repeat(66));
        System.out.println("【" + title + "】" + subtitle);
    }

    private static void print(String tag, Sandbox.ExecResult r) {
        System.out.printf("%s  exit=%d（%dms）%s%n", tag, r.exitCode(), r.durationMs(),
                r.ok() ? "" : " ← 非零退出");
        if (!r.stdout().isEmpty()) {
            for (String l : r.stdout().split("\n")) {
                System.out.println("        " + l);
            }
        }
        if (!r.stderr().isEmpty()) {
            System.out.println("        stderr: " + r.stderr());
        }
    }
}
