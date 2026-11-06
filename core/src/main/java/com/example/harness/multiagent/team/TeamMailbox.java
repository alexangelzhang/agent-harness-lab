package com.example.harness.multiagent.team;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 共享信箱：队员之间横向通信的邮局（OmO Team Mode 研读落地，2026-11-06）。
 *
 * <p>对着 OmO 的机制压出来的几条：</p>
 * <ul>
 *   <li><b>fire-and-forget</b>：send 即投递，不等回执——收信方按自己的
 *       节奏 {@link #poll}；</li>
 *   <li><b>广播</b>：发给 {@code *} 全队都收一份（每人信箱各一条）；</li>
 *   <li><b>两个 cap</b>：单条消息 {@code maxPayloadBytes} 超限拒发
 *       （垃圾话没资格进黑板），每轮 {@code maxMessagesPerRun} 预算
 *       耗尽拒发（防「一次诉求传四五棒」的陪跑，对应仓库 Swarm
 *       maxHandoffs 的教训）；</li>
 *   <li><b>全记账</b>：seq 单调递增、每人发出/收到多少可查，
 *       {@link Team#status()} 汇总时要用。</li>
 * </ul>
 */
public final class TeamMailbox {

    /** 一条站内信。 */
    public record Message(int seq, String from, String to, String body) {
    }

    private final Map<String, List<Message>> inboxes = new ConcurrentHashMap<>();
    private final List<Message> all = new ArrayList<>();
    private final int maxPayloadBytes;
    private final int maxMessagesPerRun;
    private int seq;

    /**
     * @param members 全队名册（广播按这个名单扇出）
     */
    public TeamMailbox(java.util.Set<String> members, int maxPayloadBytes, int maxMessagesPerRun) {
        members.forEach(m -> inboxes.put(m, new ArrayList<>()));
        this.maxPayloadBytes = maxPayloadBytes;
        this.maxMessagesPerRun = maxMessagesPerRun;
    }

    /**
     * 投递。to 为 {@code *} 时全队每人收一份。
     *
     * @throws IllegalArgumentException 超 payload cap 或本轮预算耗尽
     */
    public synchronized int send(String from, String to, String body) {
        if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxPayloadBytes) {
            throw new IllegalArgumentException("消息超 " + maxPayloadBytes
                    + " 字节上限（" + body.length() + " 字符），精简后再发");
        }
        if (all.size() >= maxMessagesPerRun) {
            throw new IllegalStateException("本轮消息预算（" + maxMessagesPerRun
                    + " 条）已耗尽——话已经说够了，剩下的走任务清单或收工");
        }
        Message message = new Message(++seq, from, to, body);
        all.add(message);
        if ("*".equals(to)) {
            for (Map.Entry<String, List<Message>> e : inboxes.entrySet()) {
                if (!e.getKey().equals(from)) {
                    e.getValue().add(message);
                }
            }
        } else {
            List<Message> inbox = inboxes.get(to);
            if (inbox == null) {
                throw new IllegalArgumentException("队里没有这个收件人：" + to);
            }
            inbox.add(message);
        }
        return seq;
    }

    /** 收信（取出未读的，全部返回并清空）。 */
    public synchronized List<Message> poll(String member) {
        List<Message> inbox = inboxes.get(member);
        if (inbox == null) {
            throw new IllegalArgumentException("队里没有这个成员：" + member);
        }
        List<Message> unread = List.copyOf(inbox);
        inbox.clear();
        return unread;
    }

    /** 该成员当前未读数。 */
    public synchronized int unreadCount(String member) {
        List<Message> inbox = inboxes.get(member);
        return inbox == null ? 0 : inbox.size();
    }

    /** 全队累计投递数。 */
    public synchronized int totalSent() {
        return all.size();
    }

    /** 本轮消息预算余量。 */
    public synchronized int budgetLeft() {
        return maxMessagesPerRun - all.size();
    }
}
