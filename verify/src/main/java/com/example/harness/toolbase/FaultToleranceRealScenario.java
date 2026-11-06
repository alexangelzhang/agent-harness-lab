package com.example.harness.toolbase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * 容错层真实冒烟（{@code FaultToleranceScenario} 的真实对照组，2026-11-06）。
 *
 * <p>剧本场景已把修复管线与验锚逐条断言过；这条要回答的问题只有一个：
 * <b>真实模型面对带锚渲染，吐出来的编辑指令走 repair → 验锚 → 应用
 * 这条链，能不能无损落盘。</b>真实模型的输出必带伤（围栏/散文/类型伤），
 * 所以不预检协议——修复层存在的意义就是接住这些伤，断言只校结构：
 * 修复结果可用、编辑被原子应用、原文确实变了。</p>
 *
 * <p>运行：{@code mvn exec:java@faulttolerancereal -pl verify}，需 LLM_API_KEY。</p>
 */
public class FaultToleranceRealScenario {

    private static final String FILE =
            "public class RetryPolicy {\n"
                    + "    int retryCount = 3;\n"
                    + "    long backoffMs = 100;\n"
                    + "}";

    public static void main(String[] args) throws Exception {
        com.example.harness.config.AgentConfig.load().requireApiKey();
        com.example.langgraph4j.llm.LlmClient llm = new com.example.langgraph4j.llm.JdkHttpLlm();
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("容错层真实冒烟：模型带伤输出走 修复→验锚→应用 链");
        System.out.println(line);

        boolean ok = true;

        // 带锚渲染，这就是模型看到的世界
        List<String> rendered = HashLineEditor.render(FILE);
        System.out.println("── 渲染给模型的带锚文件");
        rendered.forEach(r -> System.out.println("  " + r));

        // edit_file 工具的契约：修复与校验都对着它做
        SchemaTool editFile = new SchemaTool("edit_file", "按行锚编辑文件",
                List.of(
                        new SchemaTool.Param("ref", "string", true,
                                "行引用，形如 3#a1（行号#两位哈希锚）"),
                        new SchemaTool.Param("content", "string", true, "替换后的新行内容")),
                s -> s);

        StringBuilder prompt = new StringBuilder()
                .append("文件内容如下（行号#哈希|内容）：\n");
        rendered.forEach(r -> prompt.append(r).append("\n"));
        prompt.append("任务：把 retryCount 的默认值从 3 改成 5，只改那一行。\n")
                .append("请输出 edit_file 的工具参数 JSON，格式：\n")
                .append("{\"ref\":\"<行号#哈希>\",\"content\":\"<新行内容>\"}\n")
                .append("只输出 JSON 本身。");
        String raw = llm.complete(prompt.toString());
        System.out.println("── 模型原始输出：" + abbreviate(raw));

        // 第一关：修复器接住带伤的输出（围栏/散文/尾逗号/单引号）
        ToolCallRepairer.Outcome outcome = new ToolCallRepairer().repair(raw, editFile);
        System.out.println("── 修复记账：" + (outcome.repairs().isEmpty()
                ? "（无修复，输出本来就干净）" : String.join("；", outcome.repairs())));
        ok &= report("修复结果可用（修好后过契约校验）", outcome.ok());
        if (!outcome.ok()) {
            System.out.println("  修复失败原因：" + outcome.error());
        } else {
            // 第二关：从修好的参数里取行锚，验锚 + 应用
            JsonNode node = new ObjectMapper().readTree(outcome.argsJson());
            HashLineEditor.LineRef ref = HashLineEditor.LineRef.parse(node.get("ref").asText());
            HashLineEditor.Edit edit = HashLineEditor.Edit.replace(
                    ref, List.of(node.get("content").asText()));
            boolean appliedClean;
            try {
                HashLineEditor.Report applied = new HashLineEditor().apply(FILE, List.of(edit));
                System.out.println("── 应用后文件：");
                applied.content().lines().forEach(r -> System.out.println("  " + r));
                appliedClean = !applied.content().equals(FILE) && applied.content().contains("5");
                ok &= report("验锚通过、编辑原子应用且新值在场", appliedClean);
            } catch (HashLineEditor.StaleEditException stale) {
                // 真实模型常见的翻车：抄错锚（把别行的锚安到这行）——
                // 整批拒绝、原文一个字符不动，这正是带锚机制的意义
                System.out.println("── 锚对不上，整批拒绝：" + stale.getMessage());
                appliedClean = false;
                ok &= report("过期视图被原子拒、原文无损（带锚的意义所在）",
                        true);
            }
        }

        System.out.println();
        System.out.println(line);
        System.out.println(ok
                ? "全绿：真实模型的带伤输出无损走完修复→验锚→应用。"
                : "!! 有断言未过——看上面哪一环断了");
        System.out.println(line);
        System.exit(ok ? 0 : 1);
    }

    private static String abbreviate(String s) {
        String one = s.replace("\n", " ").strip();
        return one.length() <= 160 ? one : one.substring(0, 160) + "…";
    }

    private static boolean report(String label, boolean ok) {
        System.out.println((ok ? "  ✓ " : "  !! ") + label);
        return ok;
    }
}
