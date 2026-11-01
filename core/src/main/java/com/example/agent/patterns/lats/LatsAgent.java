package com.example.agent.patterns.lats;

import com.example.agent.llm.LlmClient;
import com.example.agent.patterns.react.ReActAgentSupport;
import com.example.agent.tool.Tool;
import com.example.agent.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模式 7：LATS（Language Agent Tree Search）
 *
 * <p>论文《Language Agent Tree Search Unifies Reasoning Acting and Planning
 * in Language Models》，即 Tree Search + ReAct + Plan&amp;Solve + Reflection 的融合体。
 * 相比 ReAct 的单一路径「一条道走到黑」，LATS 把推理过程组织成一棵树：</p>
 * <ol>
 *   <li>Expand：在当前节点让 LLM 生成 k 个候选 Thought+Action；</li>
 *   <li>Execute：对每个候选执行工具，得到 Observation（树的新一层）；</li>
 *   <li>Evaluate：让 LLM 对每个节点打分（Reward，强化学习思路）；</li>
 *   <li>Descend：选择得分最高的分支深入；若走入死路则 Reflect，
 *       把语言反思作为反馈回溯（backtrack）并探索其他分支；</li>
 *   <li>遇到 Finish 动作即得到最终答案。</li>
 * </ol>
 * <p>本实现是教学版：用「宽度受限的最优先搜索 + 反思回溯」模拟 MCTS 的核心思想，
 * 省略了严格的模拟退火与 UCT 公式。</p>
 */
public class LatsAgent {

    private static final Pattern SCORE_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    /** 搜索树节点：记录从根到当前节点的轨迹。 */
    static final class Node {
        final String thought;
        final String actionName;
        final String actionInput;
        final String observation;
        final Node parent;
        double reward;
        boolean deadEnd;

        Node(String thought, String actionName, String actionInput, String observation, Node parent) {
            this.thought = thought;
            this.actionName = actionName;
            this.actionInput = actionInput;
            this.observation = observation;
            this.parent = parent;
        }

        boolean isFinish() {
            return "Finish".equals(actionName);
        }

        /** 从根到本节点的完整轨迹描述，注入提示词作为上下文。 */
        String trace() {
            StringBuilder sb = new StringBuilder();
            for (Node n = this; n != null; n = n.parent) {
                if (n.thought != null) {
                    sb.insert(0, "Thought: " + n.thought + "\nAction: " + n.actionName
                            + "[" + n.actionInput + "]\nObservation: " + n.observation + "\n");
                }
            }
            return sb.toString();
        }
    }

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final int maxDepth;
    private final int maxExpansions;

    public LatsAgent(LlmClient llm, ToolRegistry tools, int maxDepth, int maxExpansions) {
        this.llm = llm;
        this.tools = tools;
        this.maxDepth = maxDepth;
        this.maxExpansions = maxExpansions;
    }

    public String run(String question) {
        Node root = new Node(null, null, null, null, null);
        Node current = root;

        for (int depth = 0; depth < maxDepth; depth++) {
            log("\n===== 深度 " + depth + "：扩展 " + maxExpansions + " 个候选动作 =====");

            // ---- Expand：生成候选动作 ----
            List<ReActAgentSupport.Action> candidates = expand(question, current);
            if (candidates.isEmpty()) {
                break;
            }

            // ---- Execute + Evaluate：执行并打分 ----
            List<Node> children = new ArrayList<>();
            for (ReActAgentSupport.Action action : candidates) {
                Node child;
                if (action.name().equals("Finish")) {
                    child = new Node(null, "Finish", action.input(), "任务完成", current);
                } else {
                    String observation = tools.find(action.name())
                            .map(tool -> tool.observe(action.input()))
                            .orElse("工具未注册: " + action.name());
                    child = new Node("分支尝试 " + action.name(), action.name(), action.input(), observation, current);
                }
                child.reward = evaluate(question, child);
                log(String.format("  候选 Action: %s[%s] -> reward=%.1f", action.name(), action.input(), child.reward));
                children.add(child);
            }

            // ---- Descend：选最优分支；死路则反思回溯 ----
            Node best = children.stream().max(Comparator.comparingDouble(n -> n.reward)).orElseThrow();
            if (best.reward < 4.0) {
                String reflection = reflect(question, best);
                log("[Reflection] 最优分支仍是死路，反思后回溯: " + reflection);
                best.deadEnd = true;
                // 回溯到父节点继续（教学版简化为退回根节点重新扩展其他分支）
                current = current.parent == null ? root : current.parent;
                continue;
            }
            current = best;
            log("  -> 选择分支: " + best.actionName + "[" + best.actionInput + "]");

            if (current.isFinish()) {
                log("\n[Finish] " + current.actionInput);
                return current.actionInput;
            }
        }
        throw new IllegalStateException("达到最大深度仍未找到 Finish 分支");
    }

    /** Expand：LLM 基于当前轨迹生成候选动作，每行一个 Action。 */
    private List<ReActAgentSupport.Action> expand(String question, Node current) {
        String output = llm.complete("""
                你在用树搜索解决下面的问题。基于当前已走过的轨迹，请给出若干个不同的下一步候选动作（每行一个）：
                Action: 工具名[参数]  或  Action: Finish[最终答案]
                可用工具：
                %s
                问题：%s
                当前轨迹：
                %s
                候选动作：""".formatted(tools.describe(), question, current.trace()));
        log("[Expand 候选]\n" + output);
        List<ReActAgentSupport.Action> actions = new ArrayList<>();
        for (String line : output.split("\n")) {
            ReActAgentSupport.tryParseAction(line).ifPresent(actions::add);
        }
        return actions;
    }

    /** Evaluate：LLM 对节点打 1~10 分。 */
    private double evaluate(String question, Node node) {
        String output = llm.complete("""
                请评估以下这条解决路径的当前状态，只输出 1~10 的数字评分
                （10 表示已解决问题或非常接近解决，1 表示完全错误/死路）。

                问题：%s
                轨迹：
                %s
                评分：""".formatted(question, node.trace()));
        Matcher matcher = SCORE_PATTERN.matcher(output);
        if (matcher.find()) {
            return Double.parseDouble(matcher.group(1));
        }
        return 1.0;
    }

    /** Reflect：对失败分支生成语言反思。 */
    private String reflect(String question, Node deadEnd) {
        return llm.complete("""
                以下这条解决路径走进了死路（评分过低），请用一段话总结失败原因，
                作为后续搜索分支时的警示。
                问题：%s
                失败轨迹：
                %s
                反思：""".formatted(question, deadEnd.trace()));
    }

    private static void log(String message) {
        System.out.println(message);
    }
}
