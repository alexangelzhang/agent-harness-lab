package com.example.multiagent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * 委派护栏：多 Agent 系统里 <b>handoff 不是免费的</b>——每一跳都是
 * 延迟、成本和上下文丢失，不加约束就会层级失控、A→B→A 空转。
 *
 * <p>文章（智能体AI）把护栏列为「不是可选项」：最大层数、最大 handoff
 * 次数、循环检测。这里做成一个可注入的小对象：Supervisor / Hierarchical /
 * Swarm 每次交接前都过一遍 {@link #allow}，拒绝时给一句<b>说人话</b>的理由，
 * 理由直接进 trace 和最终答复，审计能复盘「为什么这次交接没发生」。</p>
 */
public final class DelegationGuard {

    /** 护栏配置：最多几层、最多交接几次。 */
    public record Config(int maxDepth, int maxHandoffs) {
    }

    /** 一次拒绝：理由说人话，直接可展示。 */
    public record Violation(String rule, String reason) {
    }

    private final Config config;
    private final Deque<String> chain = new ArrayDeque<>();
    private int handoffs;

    /**
     * 从 root（发起者）开始一条新的委派链。
     *
     * @param config 护栏上限（maxDepth 含 root 所在层）
     * @param root 链的起点，通常是发起委派的 Agent 名
     */
    public DelegationGuard(Config config, String root) {
        this.config = config;
        this.chain.push(root);
    }

    /** 本次运行生效的护栏配置（构造时定死，运行期不改）。 */
    public Config config() {
        return config;
    }

    /**
     * 纯检查，不改状态：能否从 from 交到 to。返回空 = 放行。
     *
     * <p>检查顺序：交接次数 → 层级深度 → 循环，命中即返回，理由说人话。
     * 注意 {@code from} 不参与判定（链上已有它），循环检测只看 {@code to}
     * 是否已在这条链上。</p>
     *
     * @param from 当前持有任务的 Agent 名（仅用于展示，不影响判定）
     * @param to 拟交接的对象名
     * @return 空表示放行；否则是一次拒绝（规则名 + 可直接展示的理由）
     */
    public Optional<Violation> allow(String from, String to) {
        if (handoffs >= config.maxHandoffs()) {
            return Optional.of(new Violation("max-handoffs", "交接次数已经到顶（"
                    + config.maxHandoffs() + " 次），再传下去既慢又贵，到此为止"));
        }
        if (chain.size() >= config.maxDepth()) {
            return Optional.of(new Violation("max-depth", "已经套了 "
                    + chain.size() + " 层，接近或达到上限 " + config.maxDepth()
                    + " 层——层级越深上下文丢得越多，这一跳被拦下"));
        }
        if (chain.contains(to)) {
            return Optional.of(new Violation("cycle", to + " 已经在这条链上"
                    + "（" + chainView() + "），再交回去就绕圈了，两个 Agent 会互相踢皮球"));
        }
        return Optional.empty();
    }

    /** 记录一次实际发生的交接（先 {@link #allow} 放行再调用）。 */
    public void record(String from, String to) {
        chain.push(to);
        handoffs++;
    }

    /** 已发生的交接次数（含被 {@link #allow} 拦下的不算，只记放行的）。 */
    public int handoffs() {
        return handoffs;
    }

    /** 当前链路，从最早到最近，如 {@code 接入 → 物流 → 退款}。 */
    public String chainView() {
        StringBuilder sb = new StringBuilder();
        for (String name : chain) {
            if (sb.length() > 0) {
                sb.insert(0, " → ");
            }
            sb.insert(0, name);
        }
        return sb.toString();
    }
}
