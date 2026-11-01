package com.example.harness.toolbase;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 渠道准入：被拉进群的 Bot 不等于把整台机器交出去（尼恩文章「三重门」第三门）。
 *
 * <p>命令无害但发起者无权仍会酿祸——所以查找工具之前先验<b>人</b>：
 * 白名单直通、黑名单拒绝、陌生人进 pending 队列等管理员放行
 * （好友申请体验：bot 回一句「已提交管理员审核」）。渠道 × 用户 两级，
 * 任何一级不在白名单都不放行。</p>
 */
public final class ChannelAcl {

    /** 准入裁决。 */
    public enum Verdict { PASS, REJECT, PENDING }

    /** 一条待审申请。 */
    public record PendingEntry(String channel, String user, String firstMessage, long atMs) {
    }

    /** 裁决结果：结论 + 理由 +（PENDING 时）入队条目。 */
    public record Admission(Verdict verdict, String reason, PendingEntry pending) {
    }

    private final Set<String> whitelist = new HashSet<>();
    private final Set<String> blacklist = new HashSet<>();
    private final List<PendingEntry> pendingQueue = new ArrayList<>();

    /** 白名单：channel:user，直通。 */
    public ChannelAcl allow(String channel, String user) {
        whitelist.add(key(channel, user));
        return this;
    }

    /** 黑名单：channel:user，直接拒。 */
    public ChannelAcl deny(String channel, String user) {
        blacklist.add(key(channel, user));
        return this;
    }

    /** 渠道级白名单：该渠道所有人直通（如内部群）。 */
    public ChannelAcl allowChannel(String channel) {
        whitelist.add(key(channel, "*"));
        return this;
    }

    /** 准入裁决：白名单 → 黑名单 → pending（默认不直通）。 */
    public Admission admit(String channel, String user, String firstMessage) {
        if (whitelist.contains(key(channel, "*")) || whitelist.contains(key(channel, user))) {
            return new Admission(Verdict.PASS, "白名单用户，直通", null);
        }
        if (blacklist.contains(key(channel, user))) {
            return new Admission(Verdict.REJECT, "黑名单用户，拒绝", null);
        }
        PendingEntry entry = new PendingEntry(channel, user, firstMessage, System.currentTimeMillis());
        pendingQueue.add(entry);
        return new Admission(Verdict.PENDING,
                "陌生人进 pending 队列（第 " + pendingQueue.size() + " 位），已提示等管理员审核", entry);
    }

    /** 管理员放行 pending 里的某人（之后进白名单）。 */
    public void approvePending(String channel, String user) {
        pendingQueue.removeIf(e -> e.channel().equals(channel) && e.user().equals(user));
        allow(channel, user);
    }

    /** 当前 pending 队列（管理界面用）。 */
    public List<PendingEntry> pending() {
        return List.copyOf(pendingQueue);
    }

    private static String key(String channel, String user) {
        return channel + ":" + user;
    }
}
