package com.example.harness.interaction;

import com.example.harness.planning.SearchPlanner;
import com.example.harness.planning.TodoList;
import com.example.harness.skills.EventBus;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户交互演示：结构化提问 + 策略拦截（第 8 章）与 CLI 流式渲染（第 9 章）。
 *
 * <p>场景一「策略拦截三态」：agent 提议动作 → {@link AskPolicy} 裁决——
 * 查物流 AUTO 直通；全量导出用户数据 ASK，发出带选项的
 * {@link StructuredQuestion}，剧本人选择「仅导出脱敏样本」；
 * 删除生产库 FORBID 直接拒。每次问答留痕（谁问、选了什么、依据哪条规则）。</p>
 *
 * <p>场景二「流式渲染」：一段长回答以剧本块流式到达，{@link CliRenderer}
 * 边到边打，收尾复位；接着展示不认识的规则触发「开放题补问卷」的兜底说明。</p>
 *
 * <p>运行：{@code mvn exec:java@interaction}。</p>
 */
public class InteractionDemo {

    public static void main(String[] args) {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("用户交互：结构化提问 + 策略拦截，CLI 流式渲染");
        System.out.println(line);
        scenarioAskPolicy();
        scenarioStream();
        scenarioTodoAndSearch();
        scenarioWebDashboard();
        System.out.println();
        System.out.println(line);
        System.out.println("收尾：动作先过策略（AUTO/ASK/FORBID），该问的发带选项的题；");
        System.out.println("长任务先列清单（勾不完不算完），探查先定搜索策略；");
        System.out.println("事件流一张皮多身——CLI 打字机、浏览器面板都是订阅者。");
        System.out.println(line);
        System.exit(0);
    }

    /** 场景一：同一策略表，三种动作三种命运。 */
    private static void scenarioAskPolicy() {
        header("场景一：策略拦截三态", "查物流 AUTO / 导出数据 ASK / 删库 FORBID");
        StructuredQuestion exportQuestion = new StructuredQuestion(
                "export-users", "要导出用户数据，范围怎么定？", List.of(
                new StructuredQuestion.Option("仅导出脱敏样本", "只含 nickname 与脱敏手机号，字段可公开，秒出"),
                new StructuredQuestion.Option("导出全部字段", "含手机号与地址，需经理审批，留审计记录"),
                new StructuredQuestion.Option("取消", "不导了")),
                "取消", false);

        AskPolicy policy = new AskPolicy()
                .rule("只读查询直通", a -> a.contains("查询") || a.contains("查一下"), AskPolicy.Verdict.AUTO)
                .rule("导出要问人", a -> a.contains("导出"), AskPolicy.Verdict.ASK, exportQuestion)
                .rule("删库直接拒", a -> a.contains("删除"), AskPolicy.Verdict.FORBID);

        // 剧本「人」：export-users 这道题选脱敏样本
        Interviewer human = new ScriptedInterviewer(Map.of("export-users", "仅导出脱敏样本"));

        String[] proposals = {"查询订单 1001 的物流", "导出用户数据做分析", "删除生产库释放空间"};
        for (String action : proposals) {
            System.out.println("\nagent 想干：" + action);
            AskPolicy.Decision d = policy.decide(action);
            switch (d.verdict()) {
                case AUTO -> System.out.println("→ AUTO（" + d.reason() + "）：不问，干。");
                case ASK -> {
                    System.out.println("→ ASK（" + d.reason() + "）：发题");
                    System.out.println(indent(d.question().render()));
                    String answer = human.ask(d.question());
                    System.out.println("← 人选择：" + answer + " — 执行这个方案");
                }
                case FORBID -> System.out.println("→ FORBID（" + d.reason() + "）：不问也不干，红线。");
                default -> throw new IllegalStateException("未知裁决");
            }
        }
    }

    /** 场景二：流式渲染 + 未认识动作的兜底。 */
    private static void scenarioStream() {
        header("场景二：CLI 流式渲染", "长回答按块上屏，收尾复位");
        String[] chunks = {"订单 1001 的轨迹如下：", "  杭州仓已发出 →", "  上海转运中心已到港 →",
                "  预计明天上午派送。", "（含一件易碎品，已加固。）"};
        try (StreamRenderer renderer = new CliRenderer()) {
            for (String c : chunks) {
                renderer.chunk(c);
            }
        }

        AskPolicy policy = new AskPolicy()
                .rule("只读直通", a -> a.contains("查询"), AskPolicy.Verdict.AUTO);
        AskPolicy.Decision unknown = policy.decide("帮我把数据库迁移到新版");
        System.out.println("\n不认识的动作「帮我把数据库迁移到新版」→ " + unknown.verdict()
                + "（" + unknown.reason() + "）——demo 未配问卷，生产里这里接人工补问卷");
    }

    /** 场景三：Todo 清单 + 搜索策略（教程第 8 章余量）。 */
    private static void scenarioTodoAndSearch() {
        header("场景三：Todo 清单 + 搜索策略", "勾不完的清单是最直白的反懈怠证据");
        TodoList todo = new TodoList()
                .add("定位鉴权相关代码")
                .add("确认缓存失效逻辑")
                .add("写出修复说明");
        System.out.println(indent(todo.render()));
        // 剧本模型逐项推进：勾完两项后先宣布完成——清单不认
        todo.check("定位鉴权相关代码");
        todo.check("确认缓存失效逻辑");
        System.out.println("\n勾完两项，模型说『都做完了』——清单说：");
        System.out.println("   allDone() = " + todo.allDone() + "（还剩 " + todo.remaining() + " 项）");
        todo.check("写出修复说明");
        System.out.println("补勾最后一项后 allDone() = " + todo.allDone());

        System.out.println("\n搜索策略（探查前先定工具顺序，不每次重新决定怎么找）：");
        var tools = Set.of("cat", "grep", "ls");
        String[] queries = {"src/app.ts 里的鉴权逻辑", "哪里用了缓存", "这个项目有哪些模块"};
        for (String q : queries) {
            SearchPlanner.Plan p = SearchPlanner.plan(q, tools);
            System.out.println("   「" + q + "」→ " + p.toolOrder() + "（" + p.reason() + "）");
        }
    }

    /** 场景四：Web 事件面板——同一份事件流的浏览器皮肤（教程第 9 章余量）。 */
    private static void scenarioWebDashboard() {
        header("场景四：Web 事件面板", "事件总线的浏览器皮肤：订阅者，不是新管道");
        EventBus bus = new EventBus();
        WebDashboard dashboard = new WebDashboard(bus, 8710);
        String url = dashboard.start();
        try {
            bus.publish(new TurnStarted("用户：查一下订单 1001"));
            bus.publish(new SkillLoaded("订单查询", 2));
            bus.publish(new ToolCalled("query_order", "1001", "已发货，顺丰 SF123"));
            // 用真实 HTTP 请求自证页面活着（demo 不留活口：查完就停）
            String page = httpGet(url);
            String events = httpGet(url + "events");
            System.out.println("面板地址 " + url + "（页面 HTML " + page.length()
                    + " 字节，环形缓冲 " + dashboard.eventCount() + " 条事件）");
            System.out.println("/events 返回前 " + Math.min(120, events.length()) + " 字：");
            System.out.println("   " + events.substring(0, Math.min(120, events.length())));
        } finally {
            dashboard.stop();
        }
        System.out.println("面板已停——demo 不留下活着的服务进程。");
    }

    /** 发一个 GET 请求取回文本（demo 自证用，超时 3 秒）。 */
    private static String httpGet(String url) {
        try {
            var client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(3)).build();
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(3)).GET().build();
            return client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("面板自证请求失败：" + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("面板自证请求被中断", e);
        }
    }

    // 复用 skills 包的事件类型，证明跨包订阅无障碍
    private record TurnStarted(String userRequest) {
    }

    private record SkillLoaded(String skillName, int toolCount) {
    }

    private record ToolCalled(String toolName, String input, String output) {
    }

    private static void header(String title, String subtitle) {
        System.out.println();
        System.out.println("-".repeat(66));
        System.out.println("【" + title + "】" + subtitle);
    }

    private static String indent(String s) {
        return "   " + s.replace("\n", "\n   ");
    }
}
