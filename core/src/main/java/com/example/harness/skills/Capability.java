package com.example.harness.skills;

import com.example.agent.tool.Tool;

import java.util.List;

/**
 * 能力：按接入系统划分的能力边界（文章 #1）。
 *
 * <p>{@code aws}、{@code github}、{@code jira} 各是一个 capability——
 * 归组相关工具，也是连接、身份认证和策略管理的单位。
 * 层次关系：capability（能连哪些系统）→ tool（单一动作）→
 * skill（怎么组合用）→ operation（agent 实际要完成的工作，
 * 由前三种的任一种实现）。</p>
 *
 * @param name        系统名，如 aws / github / jira
 * @param description 这个系统能干什么（进能力清单，回答「你能访问什么」）
 * @param tools       该系统暴露的工具
 */
public record Capability(String name, String description, List<Tool> tools) {

    /** 能力清单里的一行。 */
    public String manifestLine() {
        return "- " + name + "：" + description + "（" + tools.size() + " 个工具）";
    }
}
