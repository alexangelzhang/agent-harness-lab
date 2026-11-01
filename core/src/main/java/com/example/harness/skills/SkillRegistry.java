package com.example.harness.skills;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 技能注册表：登记、按需加载、公示当前已加载的技能。
 *
 * <p>{@link #load} 是唯一的加载入口——命中信号才加载，
 * 加载动作经 {@code SkillLoaded} 事件广播出去（订阅者做渲染/审计），
 * 加载后的技能进 {@link #loadedManifest}，拼进系统提示词。</p>
 */
public final class SkillRegistry {

    /** 技能加载事件（事件总线上的负载）。 */
    public record SkillLoaded(String skillName, int toolCount) {
    }

    /** 工具调用事件（事件总线上的负载）。 */
    public record ToolCalled(String toolName, String input, String output) {
    }

    private final List<Skill> skills = new ArrayList<>();
    private final Map<String, Skill> loaded = new LinkedHashMap<>();
    private final EventBus bus;

    public SkillRegistry(EventBus bus) {
        this.bus = bus;
    }

    /** 登记一个技能（启动时做，不代表已加载）。 */
    public SkillRegistry register(Skill skill) {
        skills.add(skill);
        return this;
    }

    /** 热刷新：登记或替换同名技能；若已加载，同步更新已加载清单并重新广播。 */
    public void reload(Skill skill) {
        skills.removeIf(s -> s.name().equals(skill.name()));
        skills.add(skill);
        if (loaded.replace(skill.name(), skill) != null) {
            bus.publish(new SkillLoaded(skill.name(), skill.tools().size()));
        }
    }

    /** 按请求信号找技能（只匹配，不加载）。 */
    public Optional<Skill> find(String request) {
        return skills.stream().filter(s -> s.matches(request)).findFirst();
    }

    /** 加载命中的技能：重复加载是幂等的；加载即广播事件。 */
    public Skill load(String request) {
        Skill skill = find(request).orElseThrow(
                () -> new IllegalStateException("没有技能匹配请求：" + request));
        if (loaded.putIfAbsent(skill.name(), skill) == null) {
            bus.publish(new SkillLoaded(skill.name(), skill.tools().size()));
        }
        return skill;
    }

    /** 当前已加载技能（保持加载顺序）。 */
    public List<Skill> loaded() {
        return List.copyOf(loaded.values());
    }

    /** 已加载技能的提示词清单（工具表 + 用法说明）。 */
    public String loadedManifest() {
        StringBuilder sb = new StringBuilder();
        for (Skill s : loaded.values()) {
            sb.append("【技能 ").append(s.name()).append("】\n")
                    .append(s.toolManifest()).append('\n')
                    .append(s.promptSnippet()).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
