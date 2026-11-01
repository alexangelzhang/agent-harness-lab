package com.example.harness.skills;

import com.example.agent.tool.Tool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 技能热刷新：进程不重启，新技能落盘即被捡起（尼恩文章「技能注入」链路）。
 *
 * <p>QwenPaw 的做法：Manager 把技能目录推 MinIO，Worker 比 ETag 指纹
 * 热加载——控制面与执行面只靠<b>文件契约</b>解耦，中间没有直连通道。
 * 单进程教学版等价物：本地目录就是那条管道，{@link #scanNow} 比对
 * 「文件名 → 修改时间+大小」指纹（MinIO ETag 的本地替身），发现新增
 * 或变更就解析注册。<b>调用方决定轮询节奏</b>（生产是定时任务），
 * scanNow 本身无状态、随时可跑——和 Worker 的 update.py 同构。</p>
 *
 * <p>技能文件格式（{@code <名字>.skill}，行式、够读即可）：</p>
 * <pre>
 * trigger: 订单
 * tools: query_order=按订单号查状态; track_logistics=按订单号查物流轨迹
 * ---
 * 先 query_order 拿状态，追问轨迹再 track_logistics。
 * </pre>
 */
public final class SkillWatcher {

    /** 一次热刷新检测到的新增/变更。 */
    public record Change(String skillName, String kind, Path file) {
    }

    /** 热刷新事件（事件总线负载）。 */
    public record SkillHotLoaded(String skillName, int toolCount, String kind) {
    }

    private final Path directory;
    private final SkillRegistry registry;
    private final EventBus bus;
    private final Map<String, String> fingerprints = new LinkedHashMap<>();

    public SkillWatcher(Path directory, SkillRegistry registry, EventBus bus) {
        this.directory = directory;
        this.registry = registry;
        this.bus = bus;
    }

    /**
     * 扫一遍技能目录：新文件注册、指纹变了重载、删了不处理（保守——
     * 运行中卸载技能比加载更需要人来按按钮）。
     *
     * @return 本次变更列表（无变更为空表）
     */
    public List<Change> scanNow() {
        List<Change> changes = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".skill")).toList()) {
                String fp = fingerprint(file);
                String previous = fingerprints.put(file.getFileName().toString(), fp);
                if (previous != null && previous.equals(fp)) {
                    continue; // 指纹没变，跳过
                }
                Skill skill = parse(file);
                boolean isNew = previous == null;
                registry.reload(skill);
                changes.add(new Change(skill.name(), isNew ? "新增" : "变更", file));
                bus.publish(new SkillHotLoaded(skill.name(), skill.tools().size(),
                        isNew ? "新增" : "变更"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("技能目录扫描失败：" + directory, e);
        }
        return changes;
    }

    /** 文件名 → mtime+size，ETag 的本地替身。 */
    private static String fingerprint(Path file) throws IOException {
        var attrs = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
        return attrs.lastModifiedTime().toMillis() + ":" + attrs.size();
    }

    /** 解析 .skill 文件。 */
    static Skill parse(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        String trigger = "";
        List<Tool> tools = new ArrayList<>();
        List<String> snippetLines = new ArrayList<>();
        boolean inSnippet = false;
        for (String line : lines) {
            if (line.equals("---")) {
                inSnippet = true;
                continue;
            }
            if (inSnippet) {
                snippetLines.add(line);
            } else if (line.startsWith("trigger:")) {
                trigger = line.substring("trigger:".length()).trim();
            } else if (line.startsWith("tools:")) {
                for (String pair : line.substring("tools:".length()).split(";")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length == 2) {
                        String name = kv[0].trim();
                        tools.add(new Tool(name, kv[1].trim(),
                                input -> "（" + name + "）输入=" + input));
                    }
                }
            }
        }
        String name = file.getFileName().toString().replace(".skill", "");
        if (trigger.isEmpty() || tools.isEmpty()) {
            throw new IllegalStateException("技能文件缺 trigger 或 tools：" + file);
        }
        return new Skill(name, trigger, tools, String.join("\n", snippetLines).strip());
    }
}
