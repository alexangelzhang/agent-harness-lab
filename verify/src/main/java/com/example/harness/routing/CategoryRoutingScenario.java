package com.example.harness.routing;

import com.example.agent.llm.ScriptedLlm;
import com.example.harness.routing.CategoryRouter.Rung;
import com.example.harness.routing.CategoryRouter.RunReport;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Category 路由 + fallback 链验证（OmO 研读落地，
 * 见 docs/omo_orchestration_kibitzer.md）。
 *
 * <p>幕一「按意图选档」：委派写 category 不写模型名；首选 provider
 * 没连接就落下一档；category 没注册直接拒，不静默换工种。</p>
 *
 * <p>幕二「运行中降级」：首选档调用炸了自动降级重试，每一跳记在
 * 尝试史里；链耗尽 fail-closed，报错带完整尝试史。prompt 预设
 * 跟着档走（Claude 家族机制驱动、GPT 家族原则驱动）。</p>
 *
 * <p>运行：{@code mvn exec:java@categoryrouting -pl verify}（根目录）。</p>
 */
public class CategoryRoutingScenario {

    private static int failed;

    public static void main(String[] args) {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("Category 路由：委派写意图，模型链自己降级");
        System.out.println(line);
        pickAct();
        fallbackAct();
        System.out.println();
        System.out.println(line);
        if (failed > 0) {
            System.out.println("!! " + failed + " 项断言未过");
            System.exit(1);
        }
        System.out.println("全部断言通过：意图选档、断供落档、炸了降级、链尽报错。");
        System.out.println(line);
        System.exit(0);
    }

    private static CategoryRouter router() {
        return new CategoryRouter()
                .register("quick", List.of(
                        Rung.of("openai", "gpt-luna-fast",
                                CategoryRouter.PromptStyle.PRINCIPLED),
                        Rung.of("acme", "acme-small",
                                CategoryRouter.PromptStyle.MECHANISTIC)))
                .register("deep", List.of(
                        Rung.of("openai", "gpt-astra",
                                CategoryRouter.PromptStyle.PRINCIPLED),
                        Rung.of("anthropic", "claude-opus",
                                CategoryRouter.PromptStyle.MECHANISTIC),
                        Rung.of("acme", "acme-large",
                                CategoryRouter.PromptStyle.MECHANISTIC)));
    }

    /** 幕一：选档。 */
    private static void pickAct() {
        header("幕一：按意图选档", "写 category 不写模型名");
        CategoryRouter router = router();

        System.out.println("【全连接】openai / anthropic / acme 都在线：");
        Rung r = router.resolve("deep", Set.of("openai", "anthropic", "acme"));
        check("首选 openai/gpt-astra", r.label().equals("openai/gpt-astra"));

        System.out.println("【openai 断供】：");
        r = router.resolve("deep", Set.of("anthropic", "acme"));
        check("落到 anthropic/claude-opus", r.label().equals("anthropic/claude-opus"));
        check("prompt 预设跟着档走（Claude 家族 = 机制驱动）",
                router.promptStyle("deep", Set.of("anthropic", "acme"))
                        == CategoryRouter.PromptStyle.MECHANISTIC);
        check("首选在时预设是原则驱动",
                router.promptStyle("deep", Set.of("openai"))
                        == CategoryRouter.PromptStyle.PRINCIPLED);

        System.out.println("【只剩 acme】：");
        r = router.resolve("deep", Set.of("acme"));
        check("落到第三档 acme-large", r.label().equals("acme/acme-large"));
        check("quick 的链上没有 anthropic → 不受断供影响",
                router.resolve("quick", Set.of("acme")).label().equals("acme/acme-small"));

        System.out.println("【全断】：");
        check("链上没有可用档 → null（不静默换 category）",
                router.resolve("deep", Set.of("非任何家")) == null);

        System.out.println("【未注册的 category】：");
        try {
            router.resolve("不存在的工种", Set.of("openai"));
            check("未注册应被拒", false);
        } catch (IllegalArgumentException e) {
            check("未注册 category 直接拒", true);
            System.out.println("  拒绝：" + e.getMessage());
        }
    }

    /** 幕二：运行中降级。 */
    private static void fallbackAct() {
        header("幕二：运行中降级", "炸了降级重试，尝试史可审计");
        CategoryRouter router = router();
        Set<String> connected = Set.of("openai", "anthropic", "acme");

        Map<String, com.example.agent.llm.LlmClient> clients = Map.of(
                "openai/gpt-astra", new ScriptedLlm(), // 队列空：一调就炸
                "anthropic/claude-opus", new ScriptedLlm().says("深度分析完成"),
                "acme/acme-large", new ScriptedLlm().says("不该用到"));

        System.out.println("【首选炸】openai/gpt-astra 调用即抛异常：");
        RunReport report = router.execute("deep", connected, "分析这段死锁",
                rung -> clients.get(rung.label()));
        check("降级到 claude-opus 出活", report.output().equals("深度分析完成")
                && report.rung().label().equals("anthropic/claude-opus"));
        check("尝试史记了两跳", report.attempts().size() == 2
                && report.attempts().get(0).contains("调用失败")
                && report.attempts().get(1).contains("成功"));
        System.out.println("  尝试史：" + String.join(" → ", report.attempts()));

        System.out.println();
        System.out.println("【链耗尽】三家全炸：");
        Map<String, com.example.agent.llm.LlmClient> allDown = Map.of(
                "openai/gpt-astra", new ScriptedLlm(),
                "anthropic/claude-opus", new ScriptedLlm(),
                "acme/acme-large", new ScriptedLlm());
        try {
            router.execute("deep", connected, "再来一次", rung -> allDown.get(rung.label()));
            check("链耗尽应抛错", false);
        } catch (CategoryRouter.ChainExhaustedException e) {
            check("fail-closed，报错带整条尝试史",
                    e.getMessage().contains("链耗尽")
                            && e.getMessage().contains("acme/acme-large"));
            System.out.println("  报错：" + e.getMessage());
        }
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
