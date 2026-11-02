package com.example.harness.template;

import com.example.harness.sensor.CodeChange;
import com.example.harness.sensor.SensorSuite;

import java.util.List;

/**
 * Harness 模板演示：拓扑拴束 → 实例化 → 漂移检查 → Ashby 量化
 * （2026-10-31，Fowler Harness 工程对标最后一个缺口）。
 *
 * <p>场景一「拓扑拴束」：同一份毛病代码（无超时、无 requestId、无
 * 校验、带 TODO）分别过三个拓扑模板——API 服务拦 4 条、事件处理拦
 * 3 条、看板只拦 1 条。约定跟着拓扑走，不是一套检查打天下。</p>
 *
 * <p>场景二「实例化」：模板变成工作区——guides 渲染成 AGENTS.md
 * 片段，sensor 套件配好撞车标注，来源戳落盘。</p>
 *
 * <p>场景三「漂移检查」：上游模板升版，实例化的工作区还拴在旧版——
 * 文章点名的头号挑战（实例化后与上游脱节）显形。</p>
 *
 * <p>场景四「Ashby 量化」：约定数 vs sensor 覆盖数摆出来——承诺拓扑
 * 就是削减模型可产生空间的多样性，约定越少可跑偏的方向越多。</p>
 *
 * <p>运行：{@code mvn exec:java@template}（离线剧本）。</p>
 */
public class TemplateScenario {

    /** 同一份毛病代码：无超时/无 requestId/无校验/带 TODO。 */
    private static final CodeChange SLOPPY = new CodeChange("OrderController.java", """
            public class OrderController {
                // TODO: 补参数校验
                public Object createOrder(Object req) {
                    return service.create(req);
                }
            }
            """);

    public static void main(String[] args) {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("Harness 模板：拓扑拴束 / 实例化 / 漂移检查 / Ashby 量化");
        System.out.println(line);

        boolean s1 = scenarioTether();
        boolean s2 = scenarioInstantiate();
        boolean s3 = scenarioDrift();
        boolean s4 = scenarioAshby();

        System.out.println();
        System.out.println(line);
        System.out.println(s1 && s2 && s3 && s4
                ? "收尾：模板把「企业里少数几种拓扑」固化成 guides+sensors——"
                        + "承诺拓扑，才拴得住 agent。"
                : "!! 模板链路没走通");
        System.out.println(line);
        System.exit(0);
    }

    /** 场景一：同一份代码，三个拓扑各拦各的。 */
    private static boolean scenarioTether() {
        header("场景一：拓扑拴束", "一套检查不打天下，约定跟着拓扑走");
        List<HarnessTemplate> templates = List.of(
                HarnessTemplate.apiService(),
                HarnessTemplate.eventProcessor(),
                HarnessTemplate.dashboard());
        for (HarnessTemplate t : templates) {
            SensorSuite.Report r = t.instantiate("scenario-ws").inspect(SLOPPY, 1);
            System.out.println("  「" + t.name() + "」模板 → " + r.findings().size()
                    + " 条发现：");
            r.findings().forEach(f -> System.out.println("    [" + f.rule() + "] "
                    + f.problem().lines().findFirst().orElse("")));
        }
        return true; // 只展示不断言：发现数本身随 sensor 实现演进
    }

    /** 场景二：实例化——模板变成带戳的工作区。 */
    private static boolean scenarioInstantiate() {
        header("场景二：实例化", "guides 渲染 + sensor 配好 + 来源戳");
        TetheredWorkspace ws = HarnessTemplate.apiService().instantiate("pay-svc");
        System.out.println("  工作区：" + ws.originTag());
        System.out.println("  生成的 guides 片段：");
        ws.guides().lines().forEach(l -> System.out.println("    " + l));
        SensorSuite.Report r = ws.inspect(SLOPPY, 1);
        boolean clash = r.findings().stream().anyMatch(f -> f.guidesNote().contains("guides"));
        System.out.println("  guides 撞车标注（约定已写、产出仍违反）："
                + (clash ? "有——TODO 那条撞上了" : "!! 没有"));
        return clash;
    }

    /** 场景三：上游升版，工作区漂移显形。 */
    private static boolean scenarioDrift() {
        header("场景三：漂移检查", "实例化后与上游脱节，不靠翻 changelog 发现");
        TemplateRegistry registry = new TemplateRegistry();
        registry.register(HarnessTemplate.apiService());
        TetheredWorkspace ws = registry.latest("api-service").orElseThrow()
                .instantiate("pay-svc");
        System.out.println("  实例化时：" + ws.originTag() + "（上游最新 v1，没漂）");
        System.out.println("  → " + registry.driftCheck(ws)
                .orElse("无漂移"));

        // 上游升版：补一条「所有写操作必须记审计日志」
        List<String> v2Conventions = new java.util.ArrayList<>(
                HarnessTemplate.apiService().conventions());
        v2Conventions.add("所有写操作必须记审计日志");
        HarnessTemplate v2 = new HarnessTemplate("api-service", 2,
                v2Conventions,
                List.of(new com.example.harness.sensor.PlaceholderSensor(),
                        com.example.harness.sensor.FitnessSensor.standard()));
        registry.register(v2);
        String drift = registry.driftCheck(ws).orElse("");
        System.out.println("  上游发布 v2（新增审计日志约定）后再查：");
        System.out.println("  → " + (drift.isEmpty() ? "!! 漂移没被发现" : drift));
        return !drift.isEmpty() && drift.contains("v2");
    }

    /** 场景四：Ashby 量化——约定数与可跑偏空间。 */
    private static boolean scenarioAshby() {
        header("场景四：Ashby 量化", "承诺拓扑 = 削减模型可产生空间的多样性");
        System.out.println("  三种拓扑的约定与 sensor 覆盖：");
        List<HarnessTemplate> templates = List.of(
                new HarnessTemplate("generic-无约定", 1, List.of(), List.of()),
                HarnessTemplate.dashboard(),
                HarnessTemplate.eventProcessor(),
                HarnessTemplate.apiService());
        for (HarnessTemplate t : templates) {
            SensorSuite suite = t.instantiate("ws").suite();
            System.out.printf("    %-22s 约定 %d 条，sensor %d 个%n",
                    t.name(), t.conventions().size(), suite.sensorNames().size());
        }
        System.out.println("  读法：「generic-无约定」约 0 条——模型想怎么写都行，");
        System.out.println("        对应地没有任何 sensor 管得到它，跑歪了也没人知道；");
        System.out.println("        约定每多一条，可跑偏的方向少一条，反馈层才覆盖得过来。");
        return true; // 展示幕
    }

    private static void header(String title, String subtitle) {
        System.out.println();
        System.out.println("── " + title + " ── " + subtitle);
        System.out.println();
    }
}
