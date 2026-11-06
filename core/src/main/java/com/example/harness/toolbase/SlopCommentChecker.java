package com.example.harness.toolbase;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 腔注释检查器：write/edit 落盘前的文案护栏
 * （OmO comment-checker 研读落地，2026-11-06）。
 *
 * <p>OmO 的机制：挂在 write/edit 工具前后，对变更内容跑外部 checker 二进制，
 * 检出 AI 腔注释就<b>把工具调用打回</b>，agent 必须修好再落盘——
 * 拦截点在落盘前，不是提交后review。本类是这条机制的确定性单进程版：
 * 不依赖外部二进制，模式库直接进代码库，离线可跑、可断言。</p>
 *
 * <p>拦四类（对应 OmO blocklist 里可确定性判定的部分）：</p>
 * <ul>
 *   <li>{@link Category#FILLER}——空话副词（obviously/clearly/simply/显然/
 *       不言而喻……）：注释的价值在信息差，副词只填充字数；</li>
 *   <li>{@link Category#DECORATIVE_SEPARATOR}——纯装饰分隔线
 *       （{@code // ==========}）：没有语义的排版噪音；</li>
 *   <li>{@link Category#EMPTY_TODO}——没下文的 TODO/FIXME：
 *       不写清做什么的 TODO 是债务不是备忘；</li>
 *   <li>{@link Category#ECHO_COMMENT}——复述下一行代码的英文注释
 *       （{@code // increment counter} 配 {@code counter.increment()}）：
 *       注释词全部能在下一行标识符里找到 = 零信息增量。
 *       中文复述不做词面比对（分词不可靠），防误伤，这条只校英文。</li>
 * </ul>
 *
 * <p>逃生口（OmO 同款，慎用）：行内 {@code @allow-comment} 放行该行；
 * 文件头 5 行内 {@code comment-checker-disable-file} 整文件免检。
 * 确定性扫描器没有「二进制炸了」的故障面，但原则保留：检查器本身
 * 出任何状况都不许拦死写入链路。</p>
 */
public final class SlopCommentChecker {

    /** 命中的类别。 */
    public enum Category {
        /** 空话副词填充。 */
        FILLER,
        /** 纯装饰分隔线。 */
        DECORATIVE_SEPARATOR,
        /** 没下文的 TODO/FIXME。 */
        EMPTY_TODO,
        /** 复述下一行代码（零信息增量）。 */
        ECHO_COMMENT
    }

    /**
     * 一条命中。
     *
     * @param line 行号（1 起）
     * @param category 类别
     * @param excerpt 注释原文（截断）
     */
    public record Finding(int line, Category category, String excerpt) {
    }

    private static final Pattern FILLER_EN = Pattern.compile(
            "\\b(obviously|clearly|simply|basically|of course)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILLER_ZH = Pattern.compile("显然|很明显|不言而喻|可想而知");
    private static final Pattern DECORATIVE = Pattern.compile("[=\\-*~_#]{5,}");
    private static final Pattern EMPTY_TODO = Pattern.compile(
            "(?i)\\b(TODO|FIXME)\\b\\s*:?\\s*(\\S{0,2})?$");
    private static final Pattern COMMENT_WORDS = Pattern.compile("[a-z]{2,}");
    private static final Pattern IDENTIFIERS = Pattern.compile("[A-Za-z][A-Za-z0-9]*");
    /** 英语功能词：复述判定前剥掉，否则 // increment the counter 里的 the 会搅局。 */
    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "to", "of", "for", "in", "on", "is", "are", "be",
            "this", "that", "these", "here", "we", "it", "and", "or", "with",
            "by", "from", "as", "at", "if", "then", "so", "do", "does");

    /** 逐行扫描，返回全部命中（每行每类别至多一条）。 */
    public List<Finding> scan(String content) {
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < Math.min(5, lines.length); i++) {
            if (lines[i].contains("comment-checker-disable-file")) {
                return List.of();
            }
        }
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String body = commentBody(lines[i]);
            if (body == null || body.contains("@allow-comment")) {
                continue;
            }
            checkLine(lines, i, body, findings);
        }
        return findings;
    }

    private void checkLine(String[] lines, int i, String body, List<Finding> findings) {
        String excerpt = abbreviate(body);
        if (FILLER_EN.matcher(body).find() || FILLER_ZH.matcher(body).find()) {
            findings.add(new Finding(i + 1, Category.FILLER, excerpt));
        }
        if (DECORATIVE.matcher(body.strip()).matches()) {
            findings.add(new Finding(i + 1, Category.DECORATIVE_SEPARATOR, excerpt));
        }
        if (EMPTY_TODO.matcher(body.strip()).find()) {
            findings.add(new Finding(i + 1, Category.EMPTY_TODO, excerpt));
        }
        if (isEcho(lines, i, body)) {
            findings.add(new Finding(i + 1, Category.ECHO_COMMENT, excerpt));
        }
    }

    /** 注释词全部出现在下一行代码的标识符词表里 = 复述（仅校英文注释）。 */
    private static boolean isEcho(String[] lines, int i, String body) {
        if (!body.chars().allMatch(c -> c < 128)) {
            return false;
        }
        Set<String> commentWords = new HashSet<>();
        Matcher m = COMMENT_WORDS.matcher(body.toLowerCase());
        while (m.find()) {
            commentWords.add(m.group());
        }
        commentWords.removeAll(STOPWORDS);
        if (commentWords.size() < 2) {
            return false;
        }
        String next = nextCodeLine(lines, i);
        if (next == null) {
            return false;
        }
        Set<String> codeWords = identifierWords(next);
        return codeWords.containsAll(commentWords);
    }

    private static String nextCodeLine(String[] lines, int i) {
        for (int j = i + 1; j < lines.length; j++) {
            String t = lines[j].strip();
            if (!t.isEmpty() && commentBody(lines[j]) == null) {
                return lines[j];
            }
        }
        return null;
    }

    /** 标识符拆词：camelCase 与 snake_case 都拆，小写归一。 */
    static Set<String> identifierWords(String code) {
        Set<String> words = new HashSet<>();
        Matcher m = IDENTIFIERS.matcher(code);
        while (m.find()) {
            for (String piece : m.group().split("_")) {
                for (String word : piece.split("(?=[A-Z])")) {
                    if (word.length() >= 2) {
                        words.add(word.toLowerCase());
                    }
                }
            }
        }
        return words;
    }

    /** 是注释行则返回注释正文（剥掉标记），否则返回 null。 */
    private static String commentBody(String line) {
        String t = line.strip();
        if (t.startsWith("//")) {
            return t.substring(2);
        }
        if (t.startsWith("#")) {
            return t.substring(1);
        }
        if (t.startsWith("/*") || t.startsWith("*")) {
            return t.replaceFirst("^[/*]+", "").replaceFirst("\\*/$", "");
        }
        return null;
    }

    private static String abbreviate(String s) {
        String one = s.strip();
        return one.length() <= 40 ? one : one.substring(0, 40) + "…";
    }
}
