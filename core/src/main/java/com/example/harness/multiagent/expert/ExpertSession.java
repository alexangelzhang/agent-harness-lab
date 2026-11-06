package com.example.harness.multiagent.expert;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 只读专家的一次 fresh 会话（OmO curated 只读专家研读落地，2026-11-06）。
 *
 * <p>三个隔离原则，对着 OmO 的机制逐条压：</p>
 * <ul>
 *   <li><b>上下文隔离</b>：专家看不到主会话全文——主会话里有计划、
 *       有机密、有几万 token 的闲聊，全是噪声和泄露面。派单方挑好
 *       「任务卡」传进来，专家的世界里只有这张卡。OmO 的子代理
 *       是独立 session，正是这个意思。</li>
 *   <li><b>工具收窄</b>：工具策略在 spawn 时钉死（{@link ToolPolicy}），
 *       写和再委派一律被拦，每次放行/拦下都入账。</li>
 *   <li><b>模型分档</b>：检索/看图这类便宜活走 FAST 档，专家报告里
 *       记档可审计——贵的干活、便宜的盯着这条原则在派单处兑现。</li>
 * </ul>
 *
 * <p>专家的工具调用由调用方（剧本里是预置的计划）驱动；
 * 接真实模型时替换为模型循环即可，隔离与收窄在会话层不变。</p>
 */
public final class ExpertSession {

    /** 模型档位：与 routing 的三档概念对应，专家活走 FAST。 */
    public enum Tier {
        FAST, BALANCED, FRONTIER
    }

    private final String name;
    private final Tier tier;
    private final ToolPolicy policy;
    private final String taskCard;
    private final Function<String, String> fs;
    private final List<String> reads = new ArrayList<>();
    private final List<String> denials = new ArrayList<>();

    ExpertSession(String name, Tier tier, ToolPolicy policy,
                  String taskCard, Function<String, String> fs) {
        this.name = name;
        this.tier = tier;
        this.policy = policy;
        this.taskCard = taskCard;
        this.fs = fs;
    }

    /** 专家看到的上下文：只有任务卡，不是主会话。 */
    public String contextView() {
        return taskCard;
    }

    public String name() {
        return name;
    }

    public Tier tier() {
        return tier;
    }

    public ToolPolicy policy() {
        return policy;
    }

    /**
     * 专家发起一次工具调用。放行则执行（读结果入账），
     * 拦下则记一条 denial——写不进来，也再派不了别人。
     *
     * @return 执行结果；被拦返回 null，denial 里留了原因
     */
    public String useTool(String tool, String arg) {
        if (policy.decide(tool) == ToolPolicy.Decision.DENY) {
            denials.add(tool + "(" + arg + ")");
            return null;
        }
        String result = fs.apply(arg);
        reads.add(arg);
        return result;
    }

    /** 本次会话被拦下的工具调用。 */
    public List<String> denials() {
        return List.copyOf(denials);
    }

    /** 本次会话读过的东西。 */
    public List<String> reads() {
        return List.copyOf(reads);
    }

    /** 收工：带着结论、读了什么、被拦了什么、烧了哪档模型回派单方。 */
    public ExpertReport finish(String conclusion) {
        return new ExpertReport(name, conclusion,
                List.copyOf(reads), List.copyOf(denials), tier);
    }
}
