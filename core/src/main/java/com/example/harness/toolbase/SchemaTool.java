package com.example.harness.toolbase;

import java.util.List;
import java.util.function.Function;

/**
 * 带契约的工具：schema 是给 LLM 看的入参出参说明，不是散文。
 *
 * <p>{@code Tool} 的 description 是自由文本，模型靠「读懂一段话」知道怎么调；
 * 这里的 params 是结构化参数表（名字/类型/必填/说明），渲染进提示词的是
 * 接口契约。注册的语义也因此完整：叫什么（name）、能干什么（description）、
 * 怎么执行（action）、<b>按什么契约调（params）</b>。</p>
 *
 * @param name        工具名，agent 决策输出里引用
 * @param description 功能一句话
 * @param params      参数契约表
 * @param action      真实执行逻辑
 */
public record SchemaTool(String name, String description, List<Param> params,
                         Function<String, String> action) {

    /** 一个参数的契约。 */
    public record Param(String name, String type, boolean required, String description) {
    }

    /** 契约清单（进系统提示词）。 */
    public String schemaManifest() {
        StringBuilder sb = new StringBuilder("- ").append(name).append("：").append(description);
        for (Param p : params) {
            sb.append("\n    ").append(p.name()).append('(').append(p.type())
                    .append(p.required() ? "，必填" : "，可选").append(")：").append(p.description());
        }
        return sb.toString();
    }

    /** 执行（错误兜底同 Tool.observe）。 */
    public String observe(String input) {
        try {
            return action.apply(input);
        } catch (RuntimeException e) {
            return "工具执行出错: " + e.getMessage();
        }
    }
}
