package com.example.multiagent;

/**
 * 拓扑决策器：把「该拆不该拆、拆成什么拓扑」从拍脑袋变成<b>可执行的决策树</b>。
 *
 * <p>对照文章《多 Agent 不是单 Agent 的升级版》的立场：多 Agent 不是单 Agent
 * 的升级版，而是不同的组织形式——拆错的代价（延迟×N、上下文×N、故障面×N）
 * 远大于不拆。所以决策树的第一原则：<b>没有命中拆分信号就建议不拆</b>，
 * 写密集（多个 Agent 共享写出目标）更是直接劝退。</p>
 *
 * <p>决策顺序：读密集/写密集是分水岭——<b>共享写出目标 &gt; 0 且工序不固定，
 * 直接劝退拆分</b>（顺序写的流水线除外）；然后依次：工序固定 → Workflow；
 * 中央协调 → Supervisor；需分层 → Hierarchical；焦点漂移 → Swarm；
 * 全都不命中 → 单 Agent。</p>
 */
public final class TopologyAdvisor {

    /** 任务画像：用几个布尔信号描述任务的组织形态。 */
    public record TaskProfile(boolean fixedPipeline,      // 工序是否事先固定
                              boolean centralCoordination,  // 是否需要中央拿总
                              boolean needsLayering,        // 是否存在天然汇报层级
                              boolean focusDrift,           // 对话/焦点是否会中途漂移
                              int independentReadSources,   // 独立读源数量
                              int sharedWriteTargets) {     // 共享写出目标数量
    }

    /** 一条推荐：拓扑名 + 说人话的理由 + 是否建议保持单 Agent。 */
    public record Recommendation(String name, String reason, boolean staySingle) {
    }

    private TopologyAdvisor() {
    }

    /**
     * 按决策树出推荐；写密集信号（共享写出且工序不固定）前置劝退。
     *
     * <p>决策顺序就是短路顺序：写密集劝退 → Workflow → Supervisor →
     * Hierarchical → Swarm → 单 Agent，命中即返回。reason 一律说人话，
     * 可直接展示给做决定的人。</p>
     *
     * @param p 任务画像，六个布尔/计数信号描述任务的组织形态
     * @return 推荐拓扑名 + 理由 + 是否建议保持单 Agent
     */
    public static Recommendation recommend(TaskProfile p) {
        if (!p.fixedPipeline() && p.sharedWriteTargets() > 0) {
            return new Recommendation("单 Agent",
                    "多个执行方会写同一处产物，拆开后要么互相覆盖、要么花在锁"
                            + "和对账上的协调成本超过并行收益——写密集任务先不拆，"
                            + "或把写收敛到一道工序里；只有工序固定的流水线"
                            + "（顺序写、代码保证谁后写）例外", true);
        }
        if (p.fixedPipeline()) {
            String reason = "工序固定、依赖清楚——代码写死控制流，模型只管内容，"
                    + "成本最低、最好测、最好审计";
            if (p.sharedWriteTargets() > 0) {
                reason += "；多道工序顺序写同一份产物正是它的主场（谁后写谁覆盖，"
                        + "顺序由代码保证）";
            }
            return new Recommendation("Workflow", reason, false);
        }
        if (p.centralCoordination()) {
            return new Recommendation("Supervisor",
                    "子任务边界要临场判断，需要一个中央协调拿总、拆任务、收结果——"
                            + "注意主管只是协调者，别让专家空转", false);
        }
        if (p.needsLayering()) {
            return new Recommendation("Hierarchical",
                    "存在天然的汇报层级（如 总监→组长→工程师），层层下派、逐级留痕——"
                            + "层级每深一层上下文丢一分，必须配委派护栏", false);
        }
        if (p.focusDrift()) {
            return new Recommendation("Swarm",
                    "对话焦点会中途漂移（客服转接式），去中心化接力比中央调度更顺——"
                            + "handoff 必须留痕，循环交接要靠护栏掐断", false);
        }
        String reason;
        if (p.sharedWriteTargets() > 0) {
            reason = "多个 Agent 要写同一处产物，拆开会互相覆盖、难对账——"
                    + "写密集任务先不拆，或把写收敛到一道工序里";
        } else if (p.independentReadSources() < 2) {
            reason = "没有命中任何拆分信号，读源不到两个，单 Agent 就够——"
                    + "多 Agent 的延迟和成本都是 N 倍，不拆是默认立场";
        } else {
            reason = "读源不少但子任务边界说不准，先单 Agent 跑通，"
                    + "出现明确分工信号再拆";
        }
        return new Recommendation("单 Agent", reason, true);
    }
}
