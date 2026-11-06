package com.example.harness.multiagent.expert;

import java.util.List;
import java.util.Set;

/**
 * 专家工具策略：写死这个专家能碰什么、不能碰什么
 * （OmO curated 只读专家研读落地，2026-11-06）。
 *
 * <p>OmO 的 {@code agent-tool-restrictions.ts} 给只读专家配的是工具
 * 禁用表：Oracle/Librarian/Explore 禁 write/edit/task/call_omo_agent，
 * Multimodal-Looker 更狠——全部禁掉、只留 read。只读不靠自觉靠配置，
 * 策略是专家定义的一部分，不是运行时的提醒。</p>
 *
 * <p>两种模式：</p>
 * <ul>
 *   <li><b>deny-list</b>：默认放行，清单内点名禁；</li>
 *   <li><b>allow-list</b>：默认全禁，只放行清单内的——「只留 read」用这种。</li>
 * </ul>
 *
 * <p>每次放行/拦下都入账（{@link #allowances()} / {@link #denials()}），
 * 报告回派单方时可审计。</p>
 */
public final class ToolPolicy {

    /** 判定结果。 */
    public enum Decision {
        ALLOW, DENY
    }

    private final boolean allowListMode;
    private final Set<String> tools;
    private final String label;
    private final boolean readOnly;
    private int allowances;
    private int denials;

    private ToolPolicy(boolean allowListMode, Set<String> tools, String label, boolean readOnly) {
        this.allowListMode = allowListMode;
        this.tools = Set.copyOf(tools);
        this.label = label;
        this.readOnly = readOnly;
    }

    /** deny-list 只读：读写任务派发类工具点名禁，其余放行。 */
    public static ToolPolicy readOnly() {
        return new ToolPolicy(false,
                Set.of("write", "edit", "spawn", "send_mail", "call_agent"),
                "deny-list 只读", true);
    }

    /** allow-list 只读：全部禁掉，只留 read（OmO Multimodal-Looker 同款）。 */
    public static ToolPolicy readOnlyAllowlist() {
        return new ToolPolicy(true, Set.of("read"), "allow-list 只留 read", true);
    }

    /** 判定一个工具调用。 */
    public Decision decide(String tool) {
        boolean listed = tools.contains(tool);
        boolean allowed = allowListMode == listed;
        if (allowed) {
            allowances++;
        } else {
            denials++;
        }
        return allowed ? Decision.ALLOW : Decision.DENY;
    }

    /** 这个策略是不是只读（构造时写死，不是运行时状态）。 */
    public boolean isReadOnly() {
        return readOnly;
    }

    public String label() {
        return label;
    }

    public int allowances() {
        return allowances;
    }

    public int denials() {
        return denials;
    }
}
