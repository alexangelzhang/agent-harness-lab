package com.example.harness.planning;

import java.util.List;
import java.util.Set;

/**
 * 搜索策略（教程第 8 章「搜索策略」）：探查代码库时先用什么工具、后用什么工具。
 *
 * <p>模型自由发挥时，找文件经常「一把梭全库 grep」——贵且慢。
 * 策略按查询形态给工具排序：指名道姓找文件就先 cat；
 * 只知道关键词就先 grep；要看结构就先 ls。规则是确定性的，
 * 模型只需要按顺序试，不用每次重新决定「该怎么找」。</p>
 */
public final class SearchPlanner {

    /** 一次规划：工具顺序 + 为什么这么排。 */
    public record Plan(List<String> toolOrder, String reason) {
    }

    /**
     * 按查询形态排工具顺序。
     *
     * @param query          想干什么（如 "src/app.ts 里鉴权逻辑" / "哪里用了缓存"）
     * @param availableTools 手头有的工具名（如 cat/grep/ls）；只排存在的
     */
    public static Plan plan(String query, Set<String> availableTools) {
        List<String> order;
        String reason;
        boolean looksLikePath = query.matches(".*[\\w.-]+\\.[a-zA-Z]{1,4}.*");
        boolean wantsStructure = query.contains("结构") || query.contains("目录")
                || query.contains("有哪些") || query.contains("清单");
        if (wantsStructure) {
            order = List.of("ls", "cat");
            reason = "要的是结构不是内容——先 ls 看全貌，再挑重点 cat";
        } else if (looksLikePath) {
            order = List.of("cat", "grep");
            reason = "查询里带了文件路径——直接 cat 指名的文件，不够再 grep 上下文";
        } else {
            order = List.of("grep", "ls");
            reason = "只有关键词没有路径——先 grep 定位，再 ls 确认所在目录";
        }
        List<String> tools = order.stream().filter(availableTools::contains).toList();
        return new Plan(tools, reason);
    }
}
