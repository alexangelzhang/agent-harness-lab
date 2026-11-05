package com.example.harness.toolbase;

import com.example.harness.toolbase.HashLineEditor.Edit;
import com.example.harness.toolbase.HashLineEditor.LineRef;
import java.util.List;

/**
 * 容错层验证（OmO 研读落地，见 docs/omo_orchestration_kibitzer.md）。
 *
 * <p>幕一「哈希锚定编辑」：模型引用 {@code 行号#哈希} 改文件——锚对得上
 * 才落盘；视图过期（锚对不上）整批拒绝、原文一个字符不动；区间重叠
 * 应用前就检出；多处编辑从尾到头应用，行号不失效。</p>
 *
 * <p>幕二「畸形 tool_call 先修再判」：围栏/散文/尾逗号/单引号/类型伤
 * 逐条确定性修复且记账；缺必填参数 fail-closed，不靠猜补齐。</p>
 *
 * <p>运行：{@code mvn exec:java@faulttolerance -pl verify}（根目录）。</p>
 */
public class FaultToleranceScenario {

    private static int failed;

    public static void main(String[] args) {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("容错层：编辑验锚 + 畸形 tool_call 修复");
        System.out.println(line);
        hashlineAct();
        toolRepairAct();
        System.out.println();
        System.out.println(line);
        if (failed > 0) {
            System.out.println("!! " + failed + " 项断言未过");
            System.exit(1);
        }
        System.out.println("全部断言通过：锚不对整批拒、修复逐条记账、修不好不靠猜。");
        System.out.println(line);
        System.exit(0);
    }

    /** 幕一：哈希锚定编辑。 */
    private static void hashlineAct() {
        header("幕一：哈希锚定编辑", "引用 行号#哈希，锚废整批拒");
        String file = "public class App {\n"
                + "    void run() {\n"
                + "        System.out.println(\"v1\");\n"
                + "    }\n"
                + "}";
        HashLineEditor editor = new HashLineEditor();

        System.out.println("【渲染】给模型看的带锚文本：");
        List<String> rendered = HashLineEditor.render(file);
        rendered.forEach(l -> System.out.println("  " + l));

        // 正常编辑：替换第 3 行 + 尾部追加，两处编辑一批下
        HashLineEditor.Report ok = editor.apply(file, List.of(
                Edit.replace(LineRef.parse("3#" + HashLineEditor.hashLine(3,
                        "        System.out.println(\"v1\");")),
                        List.of("        System.out.println(\"v2\");")),
                Edit.append(List.of("// end"))));
        check("锚对得上 → 替换生效", ok.content().contains("\"v2\""));
        check("同一批的追加也落了", ok.content().endsWith("// end"));
        System.out.println("  应用后：");
        System.out.println(ok.content().lines().map(l -> "    " + l)
                .reduce("", (a, b) -> a + "\n" + b));

        // 过期视图：文件已经被别人改成 v2，模型还拿着 v1 的锚改
        System.out.println();
        System.out.println("【过期视图】文件已被改成 v2，模型拿着 v1 的锚来改：");
        String staleHash = HashLineEditor.hashLine(3, "        System.out.println(\"v1\");");
        try {
            editor.apply(ok.content(), List.of(
                    Edit.replace(LineRef.parse("3#" + staleHash), List.of("// hacked"))));
            check("过期锚应被拒绝", false);
        } catch (HashLineEditor.StaleEditException e) {
            check("锚对不上 → 整批拒绝", true);
            System.out.println("  拒绝理由：" + e.getMessage());
        }
        check("拒绝是原子的：原文一个字符没动",
                ok.content().contains("\"v2\"") && !ok.content().contains("// hacked"));

        // 区间重叠
        System.out.println();
        System.out.println("【区间重叠】两笔替换抢同一行：");
        String h3 = HashLineEditor.hashLine(3, "        System.out.println(\"v1\");");
        String h4 = HashLineEditor.hashLine(4, "    }");
        try {
            editor.apply(file, List.of(
                    Edit.replace(LineRef.parse("3#" + h3), LineRef.parse("4#" + h4),
                            List.of("// a")),
                    Edit.replace(LineRef.parse("3#" + h3), List.of("// b"))));
            check("重叠区间应被拒绝", false);
        } catch (HashLineEditor.OverlapException e) {
            check("重叠应用前就检出", true);
            System.out.println("  检出：" + e.getMessage());
        }

        // 空行锚：两处空行锚不同（行号做种子）
        String withBlanks = "a\n\nb\n\nc";
        check("两处空行的锚不同（行号做种子）",
                !HashLineEditor.hashLine(2, "").equals(HashLineEditor.hashLine(4, "")));
        check("空行渲染可定位", HashLineEditor.render(withBlanks).size() == 5);
    }

    /** 幕二：畸形 tool_call 修复。 */
    private static void toolRepairAct() {
        header("幕二：畸形 tool_call 修复", "先修再判，修不好不靠猜");
        SchemaTool deploy = new SchemaTool("deploy", "部署服务",
                List.of(new SchemaTool.Param("service", "string", true, "服务名"),
                        new SchemaTool.Param("replicas", "int", true, "副本数"),
                        new SchemaTool.Param("canary", "boolean", false, "是否灰度")),
                input -> "ok");
        ToolCallRepairer repairer = new ToolCallRepairer();

        System.out.println("【带伤调用】围栏 + 散文 + 尾逗号 + 单引号 + 数字写成字符串：");
        String raw = "好的，我来部署：\n```json\n{'service': 'pay', 'replicas': '3', "
                + "'canary': 'true', 'note': '走一波',}\n```";
        ToolCallRepairer.Outcome fixed = repairer.repair(raw, deploy);
        check("带伤调用被修好了", fixed.ok());
        System.out.println("  修复记账：");
        fixed.repairs().forEach(r -> System.out.println("    - " + r));
        System.out.println("  修复后参数：" + fixed.argsJson());
        check("类型矫正：replicas 是数字", fixed.argsJson().contains("\"replicas\":3"));
        check("契约外的 note 被丢掉", !fixed.argsJson().contains("note"));

        System.out.println();
        System.out.println("【缺必填】service 缺失：");
        ToolCallRepairer.Outcome missing = repairer.repair("{\"replicas\": 3}", deploy);
        check("缺必填 → fail-closed", !missing.ok());
        System.out.println("  报错：" + missing.error());

        System.out.println();
        System.out.println("【救不回来】纯散文没有 JSON 体：");
        ToolCallRepairer.Outcome hopeless = repairer.repair("部署一下支付服务", deploy);
        check("无 JSON 体 → fail-closed", !hopeless.ok());
        System.out.println("  报错：" + hopeless.error());
    }

    private static void check(String label, boolean ok) {
        System.out.println((ok ? "  ✓ " : "  !! ") + label);
        if (!ok) {
            failed++;
        }
    }

    private static void header(String title, String subtitle) {
        System.out.println();
        System.out.println("── " + title + " ── " + subtitle);
        System.out.println();
    }
}
