package com.example.harness.interaction;

import com.example.harness.skills.EventBus;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Web 交互架构（2026-10-31 补齐，教程第 9 章余量）：事件总线的浏览器皮肤。
 *
 * <p>不是又一个场景 打印器——同一份事件流，CLI 用 {@link CliRenderer} 打字机，
 * Web 用它渲染成页面：内嵌 HTTP 服务（JDK 自带 HttpServer，零第三方依赖），
 * 订阅 {@link EventBus} 收所有事件进环形缓冲，页面每秒拉一次 /events
 * 追加渲染。订阅方身份与 CLI 渲染器完全对等——事件总线的解耦在这里兑现：
 * 加一种皮肤不用动 agent 一行代码。</p>
 *
 * <p>生产对应物是 SSE/WebSocket 长连接 + 真前端框架；这里是「架构挂点
 * 跑通」的最小实现：换传输不换结构。用完 {@link #stop}，场景 绝不留
 * 活着的服务进程。</p>
 */
public final class WebDashboard {

    private final EventBus bus;
    private final int port;
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<PendingAction> pending = new CopyOnWriteArrayList<>();
    private final java.util.Map<String, String> decisions = new java.util.concurrent.ConcurrentHashMap<>();
    private HttpServer server;

    public WebDashboard(EventBus bus, int port) {
        this.bus = bus;
        this.port = port;
    }

    /** 订阅事件并启动 HTTP 服务；返回可访问地址。 */
    public String start() {
        bus.on(Object.class, e -> {
            if (e instanceof PendingAction p) {
                pending.add(p); // 待审批操作单独入列，页面渲染成按钮
            }
            events.add("[" + e.getClass().getSimpleName() + "] " + e);
            if (events.size() > 200) {
                events.remove(0); // 环形缓冲，页面只看最近 200 条
            }
        });
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("Web 面板启动失败（端口 " + port + "）", e);
        }
        server.createContext("/pending", exchange -> {
            byte[] body = pendingJson().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/decide", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String id = param(query, "id");
            String decision = param(query, "decision");
            if (id != null && decision != null) {
                decisions.put(id, decision);
                pending.removeIf(p -> p.id().equals(id));
            }
            byte[] body = decisionsJson().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/events", exchange -> {
            byte[] body = ("{\"events\":" + toJsonArray(events) + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/", exchange -> {
            byte[] body = page().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return "http://localhost:" + port + "/";
    }

    /** 停服务；页面不再更新。 */
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 环形缓冲里的当前事件数（场景 断言用）。 */
    public int eventCount() {
        return events.size();
    }

    /** 已记录的人工决定（场景 断言用）：操作 id → approved/rejected。 */
    public java.util.Map<String, String> decisions() {
        return java.util.Map.copyOf(decisions);
    }

    /** 仍在等审批的操作数（场景 断言用）。 */
    public int pendingCount() {
        return pending.size();
    }

    /** 事件列表拼成 JSON 数组（无第三方依赖的手写版，够用即可）。 */
    private static String toJsonArray(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(list.get(i))).append('"');
        }
        return sb.append(']').toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 单页：每秒拉 /events，追加渲染；待审批操作渲染成按钮。零框架。 */
    private static String page() {
        return """
                <!doctype html><meta charset="utf-8"><title>Agent 事件面板</title>
                <style>body{font-family:monospace;margin:2rem}li{padding:.2rem 0;border-bottom:1px solid #eee;list-style:none}
                .pending{background:#fff8e6;border:1px solid #e0c060;padding:.6rem;margin:.4rem 0;white-space:pre-wrap}
                button{margin-right:.6rem}</style>
                <h1>Agent 事件面板</h1>
                <h2>待审批</h2><div id="pend">（没有）</div>
                <h2>事件流</h2><ul id="log"></ul>
                <script>
                async function tick(){
                  const r = await fetch('/events'); const j = await r.json();
                  const log = document.getElementById('log');
                  log.innerHTML = '';
                  j.events.forEach(e => { const li = document.createElement('li');
                    li.textContent = e; log.appendChild(li); });
                }
                async function tickPending(){
                  const r = await fetch('/pending'); const j = await r.json();
                  const box = document.getElementById('pend');
                  box.innerHTML = '';
                  if (!j.pending.length) { box.textContent = '（没有）'; return; }
                  j.pending.forEach(p => {
                    const div = document.createElement('div');
                    div.className = 'pending'; div.textContent = p.payload;
                    const ok = document.createElement('button');
                    ok.textContent = '批准';
                    ok.onclick = () => fetch('/decide?id=' + p.id + '&decision=approved').then(tickPending);
                    const no = document.createElement('button');
                    no.textContent = '拒绝';
                    no.onclick = () => fetch('/decide?id=' + p.id + '&decision=rejected').then(tickPending);
                    div.appendChild(ok); div.appendChild(no);
                    box.appendChild(div);
                  });
                }
                tick(); tickPending(); setInterval(tick, 1000); setInterval(tickPending, 1000);
                </script>
                """;
    }

    /** 待审批列表的 JSON（页面轮询用）。 */
    private String pendingJson() {
        StringBuilder sb = new StringBuilder("{\"pending\":[");
        for (int i = 0; i < pending.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            PendingAction p = pending.get(i);
            sb.append("{\"id\":\"").append(escape(p.id()))
                    .append("\",\"payload\":\"").append(escape(p.buttonPayload())).append("\"}");
        }
        return sb.append("]}").toString();
    }

    /** 已记录决定的 JSON。 */
    private String decisionsJson() {
        StringBuilder sb = new StringBuilder("{\"decisions\":{");
        var entries = new java.util.ArrayList<>(decisions.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\"").append(escape(entries.get(i).getKey())).append("\":\"")
                    .append(escape(entries.get(i).getValue())).append("\"");
        }
        return sb.append("}}").toString();
    }

    /** 从 query string 里取一个参数。 */
    private static String param(String query, String key) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
