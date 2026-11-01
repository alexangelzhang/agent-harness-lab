package com.example.harness.sandbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 内存沙箱：离线确定性的假执行环境。
 *
 * <p>内置一个小型文件系统（路径 → 内容）和常用命令解释器
 * （cat / ls / grep / echo / pwd / rm），模型探查代码库、跑命令的
 * 剧本可以完全离线跑通，没有任何真实副作用。命令历史全程留痕，
 * {@link #snapshot}/{@link #restore} 用深拷贝实现快照/恢复——
 * 「动手前留底、闯祸后回滚」的最小完整演示（教程第 7 章的内存版）。</p>
 */
public final class MemorySandbox implements Sandbox {

    private final String id;
    private final Map<String, String> files;
    private final Map<String, Snapshot> snapshots = new TreeMap<>();
    private final List<String> commandLog = new ArrayList<>();

    /** @param files 初始文件系统（路径 → 内容），如 {@code Map.of("src/app.ts", "...")} */
    public MemorySandbox(String id, Map<String, String> files) {
        this.id = id;
        this.files = new TreeMap<>(files);
    }

    @Override
    public String id() {
        return id;
    }

    /** 文件是否存在（演示断言用）。 */
    public boolean exists(String path) {
        return files.containsKey(path);
    }

    /** 执行过的命令历史（审计用）。 */
    public List<String> commandLog() {
        return List.copyOf(commandLog);
    }

    @Override
    public ExecResult exec(String command) {
        beforeExec(command);
        long t0 = System.nanoTime();
        ExecResult result = run(command.trim().split("\\s+"));
        commandLog.add(command);
        afterExec(command, result);
        // 记录耗时真实流逝时间；内存解释纳秒级，对调用方展示 0ms 即可
        return new ExecResult(result.exitCode(), result.stdout(), result.stderr(),
                (System.nanoTime() - t0) / 1_000_000);
    }

    @Override
    public void snapshot(String name) {
        snapshots.put(name, new Snapshot(Map.copyOf(files)));
    }

    @Override
    public void restore(String name) {
        Snapshot s = snapshots.get(name);
        if (s == null) {
            throw new IllegalStateException("没有名为 " + name + " 的快照");
        }
        files.clear();
        files.putAll(s.files());
        // 注意：commandLog 不回滚——闯祸的记录是审计底账，回滚现场不能抹掉历史
    }

    @Override
    public void destroy() {
        files.clear();
        snapshots.clear();
        commandLog.clear();
    }

    // ---- 命令解释器 ----

    private ExecResult run(String[] tokens) {
        if (tokens.length == 0 || tokens[0].isEmpty()) {
            return err(1, "空命令");
        }
        return switch (tokens[0]) {
            case "cat" -> cat(arg(tokens, 1));
            case "ls" -> ls(arg(tokens, 1));
            case "grep" -> grep(tokens);
            case "echo" -> ok(tokens.length > 1 ? String.join(" ", List.of(tokens).subList(1, tokens.length)) : "");
            case "pwd" -> ok("/workspace");
            case "rm" -> rm(tokens);
            default -> err(127, "bash: " + tokens[0] + ": command not found");
        };
    }

    private ExecResult cat(String path) {
        String content = files.get(path);
        return content == null ? err(1, "cat: " + path + ": No such file or directory") : ok(content);
    }

    private ExecResult ls(String path) {
        if (path == null) {
            path = "";
        }
        if (files.containsKey(path)) { // 文件：原样列出
            return ok(path);
        }
        String prefix = path.isEmpty() ? "" : path + "/";
        TreeSet<String> children = new TreeSet<>();
        for (String key : files.keySet()) {
            if (key.startsWith(prefix)) {
                String rest = key.substring(prefix.length());
                int slash = rest.indexOf('/');
                children.add(slash < 0 ? rest : rest.substring(0, slash));
            }
        }
        return children.isEmpty()
                ? err(2, "ls: cannot access '" + path + "': No such file or directory")
                : ok(String.join("\n", children));
    }

    private ExecResult grep(String[] tokens) {
        // 容忍 -r/-n/-rn 等开关：第一个非开关是模式，再下一个是路径（缺省全盘）
        String pattern = null;
        String path = "";
        for (int i = 1; i < tokens.length; i++) {
            if (tokens[i].startsWith("-")) {
                continue;
            }
            if (pattern == null) {
                pattern = tokens[i];
            } else {
                path = tokens[i];
                break;
            }
        }
        if (pattern == null) {
            return err(2, "grep: 缺少模式");
        }
        String prefix = path.isEmpty() ? "" : path + "/";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (!prefix.isEmpty() && !e.getKey().startsWith(prefix)) {
                continue;
            }
            String[] lines = e.getValue().split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains(pattern)) {
                    sb.append(e.getKey()).append(':').append(i + 1).append(':')
                            .append(lines[i]).append('\n');
                }
            }
        }
        return sb.length() == 0 ? new ExecResult(1, "", "", 0) : ok(sb.toString().stripTrailing());
    }

    private ExecResult rm(String[] tokens) {
        String target = null;
        for (int i = 1; i < tokens.length; i++) {
            if (!tokens[i].startsWith("-")) {
                target = tokens[i];
            }
        }
        if (target == null) {
            return err(2, "rm: 缺少操作数");
        }
        boolean removed = files.remove(target) != null;
        String prefix = target.endsWith("/") ? target : target + "/";
        removed |= files.keySet().removeIf(k -> k.startsWith(prefix));
        return removed ? ok("") : err(1, "rm: cannot remove '" + target + "': No such file or directory");
    }

    private static String arg(String[] tokens, int i) {
        return i < tokens.length ? tokens[i] : null;
    }

    private static ExecResult ok(String stdout) {
        return new ExecResult(0, stdout, "", 0);
    }

    private static ExecResult err(int code, String stderr) {
        return new ExecResult(code, "", stderr, 0);
    }

    /** 快照：文件系统的深拷贝（命令历史不入快照——审计底账不回滚）。 */
    private record Snapshot(Map<String, String> files) {
    }
}
