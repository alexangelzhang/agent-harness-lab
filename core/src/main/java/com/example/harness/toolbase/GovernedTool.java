package com.example.harness.toolbase;

import com.example.harness.ApprovalPolicy;
import com.example.harness.ToolGate;
import com.example.harness.interaction.PendingAction;
import com.example.harness.skills.EventBus;

import java.util.Map;
import java.util.TreeMap;

/**
 * 治理壳：注册即获得治理能力，业务工具里零权限逻辑（尼恩文章核心设计）。
 *
 * <p>{@code PolicyGuardedTool} 的思路——把 check_permissions 和审批闭环
 * 挂在工具壳上，而不是写进每个业务函数。这里：</p>
 * <ul>
 *   <li>权限：声明 {@link ToolGate.Level}，调用自动过网关，DENIED 消息回
 *       agent 上下文且留痕，业务代码一行权限都没有；</li>
 *   <li>审批：声明 {@link ApprovalPolicy.Risk}，NEEDS_APPROVAL 的动作在未
 *       获批准时生成 {@link PendingAction} 挂起（发事件总线），批准凭
 *       {@code approved=true} 一事一议进入执行。</li>
 * </ul>
 *
 * <p>与 ToolGate 分工不变：网关答「这人够不够格」，壳答「这个动作要不要
 * 人批」——两层都在工具外面。</p>
 */
public final class GovernedTool {

    private final SchemaTool tool;
    private final ToolGate.Level level;
    private final ApprovalPolicy.Risk risk;
    private final EventBus bus;

    public GovernedTool(SchemaTool tool, ToolGate.Level level,
                        ApprovalPolicy.Risk risk, EventBus bus) {
        this.tool = tool;
        this.level = level;
        this.risk = risk;
        this.bus = bus;
    }

    public SchemaTool tool() {
        return tool;
    }

    public ToolGate.Level level() {
        return level;
    }

    public ApprovalPolicy.Risk risk() {
        return risk;
    }

    /**
     * 受管调用：权限 → 审批 → 执行，全在壳里。
     *
     * @param gate     当前用户的角色网关
     * @param input    工具入参
     * @param approved 该动作是否已获人工批准（一事一议授权）
     */
    public String call(ToolGate gate, String input, boolean approved) {
        if (!gate.permitted(level)) {
            // ToolGate.callUnchecked 内部留痕 + DENIED 回上下文
            return gate.callUnchecked(tool.name(), level, tool::observe, input);
        }
        if (risk == ApprovalPolicy.Risk.NEEDS_APPROVAL && !approved) {
            Map<String, String> args = new TreeMap<>();
            args.put("input", input);
            PendingAction pa = new PendingAction(
                    "pa-" + tool.name(), tool.name(), args,
                    risk.name(), "策略要求人工批准（" + tool.name() + "）", System.currentTimeMillis());
            bus.publish(pa);
            return "PENDING_APPROVAL: " + tool.name() + " 已挂起待人工批准（" + pa.id() + "）";
        }
        if (risk == ApprovalPolicy.Risk.FORBIDDEN) {
            return "FORBIDDEN: " + tool.name() + " 被策略禁止，不执行";
        }
        return tool.observe(input);
    }
}
