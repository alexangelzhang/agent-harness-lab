package com.example.harness.context;

import com.example.langgraph4j.llm.LlmClient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 上下文窗口：带 token 预算的对话历史（教程第 5 章「上下文管理与成本控制」）。
 *
 * <p>三件事：</p>
 * <ol>
 *   <li><b>用量记录</b>：每进一条消息、每出一段输出都记账
 *       （{@link #stats}，token 用字符数上界估算——中文 1 字 ≈ 1 token 量级，
 *       宁可高估不留侥幸）；</li>
 *   <li><b>逐步裁剪</b>：超预算时不整体摘要，而是从<b>最老的非受保护消息</b>
 *       开始逐条丢（system 永不动，最近两条不动），每条丢进 {@code trimLog}
 *       可审计——丢了什么必须说得清；</li>
 *   <li><b>输出上限</b>：{@link #recordOutput} 对模型输出封顶截断，
 *       超出的部分进裁剪日志，不 silently 放大窗口。</li>
 * </ol>
 *
 * <p>裁剪是最笨但最可解释的策略；更聪明的摘要压缩是后续工作，
 * 但「丢了什么可审计」这条不能丢。</p>
 */
public final class ContextWindow {

    /** 一条消息。role 约定：system 受保护、user/model 普通、tool 最易被裁。 */
    public record Message(String role, String content) {
    }

    /** 账本：当前消息数 / 估算 token / 累计入窗 / 累计输出 / 被裁条数。 */
    public record Stats(int messages, long windowTokens, long totalInput,
                        long totalOutput, long trimmed) {
    }

    private final long tokenBudget;
    private final long outputCap;
    private final Deque<Message> history = new ArrayDeque<>();
    private final List<String> trimLog = new ArrayList<>();
    private long totalInput;
    private long totalOutput;
    private long trimmed;

    /**
     * @param tokenBudget 窗口预算（估算 token，超出即逐步裁剪）
     * @param outputCap   单段输出上限（超出截断并记账）
     */
    public ContextWindow(long tokenBudget, long outputCap) {
        if (tokenBudget < 1 || outputCap < 1) {
            throw new IllegalArgumentException("预算与输出上限至少为 1");
        }
        this.tokenBudget = tokenBudget;
        this.outputCap = outputCap;
    }

    /** 消息入窗并记账；超预算时逐步裁剪最老的非受保护消息。 */
    public void add(String role, String content) {
        Message m = new Message(role, content);
        history.addLast(m);
        totalInput += estimate(m.content());
        trimToBudget();
    }

    /**
     * 模型输出入账：超 {@code outputCap} 的部分截断记账。
     * @return 实际入账的内容（可能已被截断）
     */
    public String recordOutput(String output) {
        if (estimate(output) <= outputCap) {
            totalOutput += estimate(output);
            return output;
        }
        String kept = truncateByTokens(output, outputCap);
        totalOutput += outputCap;
        trimLog.add("输出截断：保留前 " + kept.length() + " 字，丢弃 "
                + (output.length() - kept.length()) + " 字");
        return kept;
    }

    /** 渲染成提示词（最新在最后）。 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        for (Message m : history) {
            sb.append('[').append(m.role()).append("] ").append(m.content()).append('\n');
        }
        return sb.toString();
    }

    /** 当前账本快照。 */
    public Stats stats() {
        return new Stats(history.size(), windowTokens(), totalInput, totalOutput, trimmed);
    }

    /** 裁剪日志：丢了什么，逐条可审计。 */
    public List<String> trimLog() {
        return List.copyOf(trimLog);
    }

    /**
     * 摘要压缩（2026-10-31 补齐，教程第 5 章余量）：比逐步裁剪聪明一档——
     * 把最老的一批可裁消息交给模型压成一段「摘要」钉进窗口（role=system，
     * 永不再被裁），细节让位给要点，但要点不丢。
     *
     * <p>调用后窗口仍可能超预算（摘要本身也占地方），继续交给
     * {@link #trimToBudget} 兜底；压缩与裁剪的先后关系写进裁剪日志。</p>
     *
     * @param summarizer 压缩用的 LLM（提示词自带被压缩内容）
     * @param batch      每轮压多少条消息
     * @return 生成的摘要文本（未压缩返回 null）
     */
    public String compactWith(LlmClient summarizer, int batch) {
        if (batch < 1) {
            throw new IllegalArgumentException("每轮至少压 1 条");
        }
        List<Message> droppable = new ArrayList<>();
        int size = history.size();
        int index = 0;
        for (Message m : history) {
            boolean protectedMsg = m.role().equals("system") || index >= size - 2;
            if (!protectedMsg) {
                droppable.add(m);
                if (droppable.size() >= batch) {
                    break;
                }
            }
            index++;
        }
        if (droppable.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("把下面的对话记录压成一段不超过 50 字的中文摘要，"
                + "只留要点（谁要什么、结论是什么），不要细节：\n");
        for (Message m : droppable) {
            sb.append('[').append(m.role()).append("] ").append(m.content()).append('\n');
        }
        String summary = summarizer.complete(sb.toString()).strip();
        history.removeAll(droppable);
        trimmed += droppable.size();
        trimLog.add("摘要压缩 " + droppable.size() + " 条消息 → 1 条摘要（" + summary + "）");
        history.addFirst(new Message("system", "【前文摘要】" + summary));
        trimToBudget();
        return summary;
    }

    /** 窗口内估算 token 总量。 */
    private long windowTokens() {
        long sum = 0;
        for (Message m : history) {
            sum += estimate(m.content());
        }
        return sum;
    }

    /** 逐步裁剪：最老的非受保护消息先丢；受保护 = system 或最近两条。 */
    private void trimToBudget() {
        while (windowTokens() > tokenBudget) {
            int index = 0;
            Message victim = null;
            int size = history.size();
            for (Message m : history) {
                boolean protectedMsg = m.role().equals("system") || index >= size - 2;
                if (!protectedMsg) {
                    victim = m;
                    break;
                }
                index++;
            }
            if (victim == null) {
                trimLog.add("窗口只剩受保护消息仍超预算（" + windowTokens() + " > "
                        + tokenBudget + "），停止裁剪——该换摘要压缩或换模型了");
                return;
            }
            history.remove(victim);
            trimmed++;
            trimLog.add("裁剪 1 条 " + victim.role() + " 消息（前 "
                    + Math.min(30, victim.content().length()) + " 字："
                    + victim.content().substring(0, Math.min(30, victim.content().length())) + "…）");
        }
    }

    /** 估算 token：字符数上界。 */
    private static long estimate(String s) {
        return s.length();
    }

    /** 按 token 预算截断文本（从尾部丢）。 */
    private static String truncateByTokens(String s, long cap) {
        return s.length() <= cap ? s : s.substring(0, (int) cap);
    }
}
