package com.example.harness.interaction;

import com.example.harness.skills.EventBus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 待处理操作演示：系统强制的审批载荷，在 Web 面板上长成按钮（文章 #8）。
 *
 * <p>「给客户退 18000 元」命中高危策略——Harness 不等模型说什么，
 * 直接生成 {@link PendingAction} 挂起任务：同一载荷在 Slack 是按钮、
 * 在 Teams 是卡片、在这里是面板上的两个按钮。demo 用 HTTP 客户端
 * 模拟人点了「批准」，决定回传后从挂起状态继续（恢复执行的真实接法
 * 是 langgraph4j resume，见 harness.DiskCheckpointDemo）。</p>
 *
 * <p>运行：{@code mvn exec:java@pending}。</p>
 */
public class PendingActionDemo {

    public static void main(String[] args) throws Exception {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("待处理操作：系统强制的审批载荷，面板上长成按钮");
        System.out.println(line);

        EventBus bus = new EventBus();
        WebDashboard dashboard = new WebDashboard(bus, 8720);
        String url = dashboard.start();

        // Harness 的策略关：高危操作不管模型打算什么都直接挂起
        PendingAction action = new PendingAction(
                "pa-42", "refund_customer",
                Map.of("customer_id", "cust_42", "amount", "18000",
                        "credential", "${SECRET:stripe}"),
                "HIGH", "单笔退款超过 5000 元，策略要求人工批准", System.currentTimeMillis());
        System.out.println("【策略关】模型提议退款 18000 元 → Harness 挂起任务：");
        System.out.println(action.buttonPayload());
        System.out.println("  载荷已发到事件总线，" + url + " 把它渲染成了两个按钮。");
        bus.publish(action);

        // 面板自证：/pending 里有这条，页面里有按钮
        HttpClient client = HttpClient.newHttpClient();
        String pendingBody = get(client, url + "pending");
        String page = get(client, url);
        System.out.println();
        System.out.println("【面板自证】/pending 返回待审批：" + pendingBody.contains("pa-42"));
        System.out.println("【面板自证】页面含批准按钮：" + page.contains("批准"));

        // 模拟人点「批准」
        String decided = get(client, url + "decide?id=pa-42&decision=approved");
        System.out.println("【人工】点下「批准」→ /decide 记录：" + decided);
        System.out.println("【决定回传】dashboard.decisions() = " + dashboard.decisions());
        System.out.println("【决定回传】还在等审批的：" + dashboard.pendingCount() + " 条");
        dashboard.stop();
        System.out.println("（决定已回传，真实接法是从磁盘 checkpoint resume 继续执行——见 checkpoint demo。）");

        boolean ok = dashboard.decisions().containsKey("pa-42")
                && dashboard.pendingCount() == 0
                && page.contains("批准");
        System.out.println();
        System.out.println(line);
        System.out.println(ok
                ? "收尾：批不批是系统说了算；按钮只是同一载荷在不同入口的样子。"
                : "!! 审批闭环没走通");
        System.out.println(line);
        System.exit(0);
    }

    private static String get(HttpClient client, String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }
}
