package com.example.harness.toolbase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * 畸形 tool_call 的确定性修复器（OmO 容错层研读落地，
 * 见 docs/omo_orchestration_kibitzer.md）。
 *
 * <p>模型吐的工具调用经常带伤：套着 markdown 代码围栏、行尾多逗号、
 * 单引号当 JSON、数字写成字符串。直接炸掉等于把一整轮推理作废——
 * 这里的机制是<b>先修再判</b>：每一步修复都是确定性规则且逐条记账
 * （哪条规则改了什么，可审计），修完仍要过 {@link SchemaTool} 的契约
 * 校验；修不好就 fail-closed 报错，<b>绝不靠猜补齐</b>。</p>
 *
 * <p>修复管线的顺序就是置信度从高到低：去围栏 → 抽出 JSON 体 →
 * 语法级修补（尾逗号/单引号）→ 按契约做类型矫正（"42" → 42）→
 * 契约校验（缺必填、参数表外的键都不放过）。</p>
 */
public final class ToolCallRepairer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 修复结果。 */
    public record Outcome(boolean ok, String argsJson, List<String> repairs,
                          String error) {

        static Outcome ok(String argsJson, List<String> repairs) {
            return new Outcome(true, argsJson, List.copyOf(repairs), null);
        }

        static Outcome fail(String error, List<String> repairs) {
            return new Outcome(false, null, List.copyOf(repairs), error);
        }
    }

    /**
     * 修一段模型输出的工具调用参数。
     *
     * @param raw  模型原始输出（可能带围栏、语法伤、类型伤）
     * @param tool 目标工具（类型矫正与必填校验都按它的契约来）
     */
    public Outcome repair(String raw, SchemaTool tool) {
        List<String> repairs = new ArrayList<>();
        String text = raw.trim();

        // ① 去 markdown 围栏
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "")
                    .replaceFirst("\\s*```\\s*$", "").trim();
            repairs.add("去掉 markdown 代码围栏");
        }

        // ② 抽出最外层 JSON 体（模型常在前后裹散文）
        int from = text.indexOf('{');
        int to = text.lastIndexOf('}');
        if (from < 0 || to <= from) {
            return Outcome.fail("找不到 JSON 对象体，无法修复", repairs);
        }
        if (from > 0 || to < text.length() - 1) {
            text = text.substring(from, to + 1);
            repairs.add("剥掉 JSON 体外的散文");
        }

        // ③ 语法级修补
        String noTrailingComma = text.replaceAll(",(\\s*[}\\]])", "$1");
        if (!noTrailingComma.equals(text)) {
            text = noTrailingComma;
            repairs.add("删掉行尾多余逗号");
        }
        JsonNode tree = tryParse(text);
        if (tree == null) {
            String singleFixed = text.replace('\'', '"');
            tree = tryParse(singleFixed);
            if (tree != null) {
                text = singleFixed;
                repairs.add("单引号改双引号");
            }
        }
        if (tree == null || !tree.isObject()) {
            return Outcome.fail("语法修补后仍不是合法 JSON 对象", repairs);
        }

        // ④ 按契约做类型矫正 + 契约校验
        ObjectNode obj = (ObjectNode) tree;
        List<String> names = new ArrayList<>();
        obj.fieldNames().forEachRemaining(names::add);
        List<String> unknown = new ArrayList<>();
        for (String name : names) {
            JsonNode value = obj.get(name);
            SchemaTool.Param contract = findParam(tool, name);
            if (contract == null) {
                unknown.add(name);
                obj.remove(name);
                continue;
            }
            JsonNode coerced = coerce(value, contract.type());
            if (coerced != value) {
                obj.set(name, coerced);
                repairs.add("参数 " + name + " 按契约矫正为 " + contract.type());
            }
        }
        for (String key : unknown) {
            repairs.add("丢掉契约外的参数 " + key);
        }
        for (SchemaTool.Param p : tool.params()) {
            if (p.required() && !obj.has(p.name())) {
                return Outcome.fail("缺必填参数 " + p.name() + "，不靠猜补齐", repairs);
            }
        }
        return Outcome.ok(obj.toString(), repairs);
    }

    private static JsonNode tryParse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    private static SchemaTool.Param findParam(SchemaTool tool, String name) {
        return tool.params().stream()
                .filter(p -> p.name().equals(name))
                .findFirst().orElse(null);
    }

    /** 类型矫正：契约说数字/布尔，模型给了字符串字面量才换；其余原样。 */
    private static JsonNode coerce(JsonNode value, String type) {
        if (!value.isTextual()) {
            return value;
        }
        String text = value.asText();
        return switch (type) {
            case "int", "long" -> text.matches("-?\\d+")
                    ? MAPPER.getNodeFactory().numberNode(Long.parseLong(text)) : value;
            case "double", "float", "number" -> text.matches("-?\\d+(\\.\\d+)?")
                    ? MAPPER.getNodeFactory().numberNode(Double.parseDouble(text)) : value;
            case "boolean", "bool" -> ("true".equalsIgnoreCase(text)
                    || "false".equalsIgnoreCase(text))
                    ? MAPPER.getNodeFactory().booleanNode(Boolean.parseBoolean(text)) : value;
            default -> value;
        };
    }
}
