package com.example.agent.tool;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 工具注册表：按名称注册、查找工具，并能生成注入提示词的工具清单。 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry register(Tool tool) {
        tools.put(tool.name(), tool);
        return this;
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Collection<Tool> all() {
        return tools.values();
    }

    /** 生成适合注入系统提示词的工具清单，例如 "search: 搜索互联网\nCalculator: 计算数学表达式"。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (Tool tool : tools.values()) {
            sb.append(tool.name()).append(": ").append(tool.description()).append('\n');
        }
        return sb.toString();
    }
}
