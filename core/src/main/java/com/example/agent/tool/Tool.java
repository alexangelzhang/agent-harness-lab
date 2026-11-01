package com.example.agent.tool;

import java.util.function.Function;

/**
 * Agent 可调用的外部工具。
 *
 * @param name        工具名，LLM 输出的 Action 中引用
 * @param description 工具描述，注入到提示词中帮助模型选择工具
 * @param action      工具实现：输入参数字符串，返回观察结果
 */
public record Tool(String name, String description, Function<String, String> action) {

    public String observe(String input) {
        try {
            return action.apply(input);
        } catch (RuntimeException e) {
            return "工具执行出错: " + e.getMessage();
        }
    }
}
