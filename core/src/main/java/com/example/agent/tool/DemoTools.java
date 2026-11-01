package com.example.agent.tool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 演示用的内置工具集：搜索（模拟）、计算器（真实现）、订单查询（模拟）。 */
public final class DemoTools {

    private DemoTools() {
    }

    /** 模拟搜索工具：内置几个关键字的固定答案，未命中时返回通用兜底结果。 */
    public static Tool search() {
        Map<String, String> knowledge = new ConcurrentHashMap<>();
        knowledge.put("勒布朗·詹姆斯", "勒布朗·詹姆斯（LeBron James），1984年12月30日出生，NBA 球星，效力于洛杉矶湖人。");
        knowledge.put("google", "Google 由拉里·佩奇和谢尔盖·布林于 1998 年创立。");
        knowledge.put("Python", "Python 由 Guido van Rossum 于 1991 年发布，是一门解释型高级语言。");
        knowledge.put("Java", "Java 由 Sun 公司于 1995 年发布，2014 年随 Java 8 引入 Lambda，现为 Oracle 维护的开源语言。");
        knowledge.put("黄金价格", "今日黄金价格约 612 元/克（模拟数据）。");
        knowledge.put("张译", "张译，1978 年 2 月 17 日出生，中国内地男演员。");
        knowledge.put("吴京", "吴京，1974 年 4 月 3 日出生，中国内地男演员、导演。");
        knowledge.put("虚拟线程", "Java 21 正式引入虚拟线程（Virtual Threads, JEP 444），大幅简化高并发编程。");
        knowledge.put("模式", "Agent 九大设计模式：ReAct、Plan&Solve、REWOO、LLMCompiler、Basic Reflection、Reflexion、LATS、Self-Discover、STORM。");
        return new Tool("Search", "搜索互联网获取事实信息", query -> {
            String lower = query.toLowerCase();
            for (Map.Entry<String, String> e : knowledge.entrySet()) {
                if (lower.contains(e.getKey().toLowerCase())) {
                    return e.getValue();
                }
            }
            return "未找到与「" + query + "」相关的信息。";
        });
    }

    /** 真计算器：支持四则运算，安全地用 Java 语法解析，不引入依赖。 */
    public static Tool calculator() {
        return new Tool("Calculator", "计算数学表达式，如 1+2*3", expr -> {
            try {
                double value = Evaluator.eval(expr);
                return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
            } catch (RuntimeException e) {
                return "表达式无法解析: " + expr;
            }
        });
    }

    /** 模拟订单查询。 */
    public static Tool orderLookup() {
        Map<String, String> orders = Map.of(
                "12345", "订单 12345：状态=已发货，商品=《深入理解Java虚拟机》，金额=99.00 元",
                "67890", "订单 67890：状态=待支付，商品=机械键盘，金额=399.00 元");
        return new Tool("QueryOrder", "查询订单状态，参数为订单号", orderId ->
                orders.getOrDefault(orderId.trim(),
                        "订单 " + orderId + " 不存在"));
    }

    /** 模拟网页抓取。 */
    public static Tool fetchUrl() {
        Map<String, String> pages = Map.of(
                "susan.net.cn", "Java突击队：专注 Java 技术分享的社区站点（模拟页面）",
                "wikipedia.org", "维基百科：自由百科全书（模拟页面）");
        return new Tool("FetchURL", "抓取指定 URL 的页面内容", url -> {
            for (Map.Entry<String, String> e : pages.entrySet()) {
                if (url.contains(e.getKey())) {
                    return e.getValue();
                }
            }
            return "抓取失败：" + url + "（模拟环境仅支持少量页面）";
        });
    }

    public static List<Tool> all() {
        return List.of(search(), calculator(), orderLookup(), fetchUrl());
    }
}
