package com.example.harness.skills;

import com.example.agent.tool.Tool;

import java.util.List;

/**
 * 技能：按需加载的能力包（教程第 10 章「技能」）。
 *
 * <p>一个技能 = 触发条件 + 一组工具 + 一段注入提示词的用法说明。
 * agent 启动时只带基础工具；提示词里出现匹配技能的信号时，
 * {@link SkillRegistry} 才把它加载进来——工具表随任务长出来，
 * 而不是一股脑全塞进系统提示词。</p>
 *
 * @param name         技能名
 * @param trigger      什么信号该加载（关键词/正则，匹配用户请求）
 * @param tools        技能携带的工具
 * @param promptSnippet 注入系统提示词的用法说明（模型读完才知道怎么调）
 */
public record Skill(String name, String trigger, List<Tool> tools, String promptSnippet) {

    /** 信号是否命中本技能。 */
    public boolean matches(String request) {
        return request.contains(trigger);
    }

    /** 工具清单渲染（进提示词）。 */
    public String toolManifest() {
        StringBuilder sb = new StringBuilder();
        for (Tool t : tools) {
            sb.append("- ").append(t.name()).append("：").append(t.description()).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
