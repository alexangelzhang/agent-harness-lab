package com.example.harness.multiagent.expert;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * curated 只读专家名册：explore / librarian / plan-reviewer 这一族的
 * 定义与装配处（OmO curated 只读专家研读落地，2026-11-06）。
 *
 * <p>OmO 的 {@code builtin-agents.ts} 是这个意思：每个内置 agent 是
 * 一条工厂定义——职责、模型链、工具禁用表全写死在定义里，spawn 即
 * 装配，运行时不接受临时改口。Team Mode 对只读专家是<b>硬拒</b>：
 * 不能当队友，领班要它们干活只能走 task 委派——只读专家加进
 * 共享黑板只会看不能写，是设计错误不是 feature。</p>
 */
public final class ExpertRegistry {

    /**
     * Team Mode 准入判定。
     *
     * @param eligible 能不能当队友
     * @param reason 判定理由（被拒时给领班指路）
     */
    public record TeamVerdict(boolean eligible, String reason) {
    }

    /** 一条专家定义。 */
    private record Definition(String duty, ExpertSession.Tier tier, ToolPolicy policy) {
    }

    private final Map<String, Definition> definitions = new LinkedHashMap<>();

    /** 出厂名册：三家 deny-list 只读 + 一家 allow-list 只读。 */
    public static ExpertRegistry curated() {
        ExpertRegistry registry = new ExpertRegistry();
        registry.define("explore",
                "代码库检索：定位定义/引用，回答「这段逻辑在哪」", ExpertSession.Tier.FAST,
                ToolPolicy.readOnly());
        registry.define("librarian",
                "外部资料检索：文档/OSS/issue，回答「这库怎么用」", ExpertSession.Tier.FAST,
                ToolPolicy.readOnly());
        registry.define("plan-reviewer",
                "方案审阅：只挑毛病不改稿", ExpertSession.Tier.BALANCED,
                ToolPolicy.readOnly());
        registry.define("vision-looker",
                "看图读 PDF", ExpertSession.Tier.FAST,
                ToolPolicy.readOnlyAllowlist());
        return registry;
    }

    /** 登记一条定义（同名覆盖，重定义即热更新）。 */
    public void define(String name, String duty, ExpertSession.Tier tier, ToolPolicy policy) {
        definitions.put(name, new Definition(duty, tier, policy));
    }

    /**
     * spawn 一个专家：fresh 会话，上下文只有任务卡，工具策略与模型档
     * 按定义装配，与主会话零共享。
     *
     * @param fs 只读虚拟文件系统（read 的实现）
     */
    public ExpertSession spawn(String name, String taskCard, Function<String, String> fs) {
        Definition def = definitions.get(name);
        if (def == null) {
            throw new IllegalArgumentException("名册里没有这个专家：" + name);
        }
        return new ExpertSession(name, def.tier(), def.policy(), taskCard, fs);
    }

    /** Team Mode 准入：只读专家硬拒，领班走 task 委派。 */
    public TeamVerdict teamVerdict(String name) {
        Definition def = definitions.get(name);
        if (def == null) {
            return new TeamVerdict(false, "名册里没有这个专家：" + name);
        }
        if (def.policy().isReadOnly()) {
            return new TeamVerdict(false,
                    name + " 是只读专家，进共享黑板只会看不能写——"
                            + "领班要用它就走 task 委派，结果以报告回收");
        }
        return new TeamVerdict(true, name + " 可以当队友");
    }
}
