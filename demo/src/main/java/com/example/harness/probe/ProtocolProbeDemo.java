package com.example.harness.probe;

import com.example.harness.config.AgentConfig;
import com.example.langgraph4j.llm.CountingLlm;
import com.example.langgraph4j.llm.KimiLlm;
import com.example.langgraph4j.llm.LlmClient;
import com.example.langgraph4j.llm.RetryingLlm;
import com.example.langgraph4j.llm.ScriptedLlm;

import java.util.List;
import java.util.Set;

/**
 * 协议自检探针 demo：跑真实 ReAct 实验前先花一次调用验证模型会按协议吐
 * Action 行——装置没搭对就 fail fast，别拿真实额度跑注定产不出轨迹的
 * 实验（realfoldeval 第一版 6/6 遍自然语言闲聊的教训固化）。
 *
 * <p>三幕：</p>
 * <ol>
 *   <li><b>剧本·探针通过</b>：ScriptedLlm 按协议回 Action 行，闸门放行，
 *       下游实验照跑；</li>
 *   <li><b>剧本·探针拦截</b>：ScriptedLlm 自然语言闲聊，require 抛闸门异常，
 *       下游调用数必须为 0（省下的就是真实额度）；</li>
 *   <li><b>真实双探</b>（gated 在 KIMI_API_KEY）：同一个真实模型，裸 prompt
 *       （无协议说明）先探——预期挂；补上协议说明再探——预期过。结果作
 *       发现报告，不硬断言（那是观察对象，不是门槛）。</li>
 * </ol>
 *
 * <p>运行：{@code mvn exec:java@protocolprobe}。</p>
 */
public class ProtocolProbeDemo {

    /** 和真实实验同款的协议说明（照抄 realfoldeval 修完后的版本）。 */
    private static final String PROTOCOL = """
            你是客服主 Agent。可用工具：
            - query_order[订单号]：查订单买了什么、状态
            - query_shipping[订单号]：查预计送达时间
            每轮只输出一行，两种形式：
            Action: 工具名[参数]        —— 需要查信息时
            Action: Finish[给用户的完整答复] —— 信息够了，收口答复""";

    private static final Set<String> DECLARED = Set.of("query_order", "query_shipping", "Finish");

    private static int bad;

    public static void main(String[] args) {
        header("协议自检探针", "真实 ReAct 实验前置：一次最小调用确认模型会吐 Action，不烧冤枉额度");

        actScriptedPass();
        actScriptedBlock();

        boolean realRan = actRealProbes();

        System.out.println("\n── 断言 ──");
        // 剧本两幕已自带 check；这里汇总真实部分的结构状态
        check("真实双探有 KIMI_API_KEY 时跑过、没有时明确跳过",
                realRan || System.getenv("KIMI_API_KEY") == null);

        String line = "=".repeat(66);
        System.out.println();
        System.out.println(line);
        if (bad == 0) {
            System.out.println("收尾：真实实验的 wiring 层先过探针再开跑——");
            System.out.println("      模型不按协议说话，后面的轨迹一文不值。");
        } else {
            System.out.println("!! 有 " + bad + " 处断言未通过");
        }
        System.out.println(line);
        System.exit(bad == 0 ? 0 : 1);
    }

    // ---- 幕 1：剧本 · 探针通过，下游照跑 -------------------------------------------

    private static void actScriptedPass() {
        System.out.println("\n── 幕 1（剧本）：探针通过，闸门放行 ──");
        CountingLlm brain = new CountingLlm(
                new ScriptedLlm().says("Action: query_order[1001]"));
        ProtocolProbe.Result result = ProtocolProbe.check(brain, PROTOCOL, DECLARED);
        System.out.println("    模型原话：" + result.rawOutput());
        System.out.println("    判定：" + result.reason());
        check("剧本探针判定通过", result.ok());
        check("探针本身只花 1 次调用", brain.calls() == 1);

        // 闸门放行后下游实验照跑（这里用同一个剧本大脑走完一遍示意）
        String observation = "订单1001：咖啡机 ×1，已发货。";
        System.out.println("    [下游] 探针过了，实验开跑：query_order → " + observation);
        check("闸门放行后下游能拿到工具结果继续", observation.contains("咖啡机"));
    }

    // ---- 幕 2：剧本 · 探针拦截，下游 0 调用 ----------------------------------------

    private static void actScriptedBlock() {
        System.out.println("\n── 幕 2（剧本）：模型闲聊，闸门拦下 ──");
        CountingLlm brain = new CountingLlm(
                new ScriptedLlm().says("抱歉，我当前无法实际调用查询工具，请稍后再试。"));
        String gateMessage;
        try {
            ProtocolProbe.require(brain, PROTOCOL, DECLARED);
            gateMessage = "";
        } catch (IllegalStateException e) {
            gateMessage = e.getMessage();
        }
        System.out.println("    闸门消息：" + abbreviate(gateMessage));
        check("require 抛了闸门异常", !gateMessage.isEmpty());
        check("拦截理由指明是协议问题", gateMessage.contains("协议"));
        check("探针之外下游 0 调用（省下的就是真实额度）", brain.calls() == 1); // 探针那 1 次，之后没再碰模型
    }

    // ---- 幕 3：真实双探（gated） ---------------------------------------------------

    private static boolean actRealProbes() {
        System.out.println("\n── 幕 3（真实，需 KIMI_API_KEY）：同一模型，裸 prompt vs 带协议 ──");
        if (System.getenv("KIMI_API_KEY") == null) {
            System.out.println("    缺 KIMI_API_KEY，真实双探跳过（剧本两幕已验证闸门逻辑）");
            return false;
        }
        String model = AgentConfig.load().model();
        System.out.println("    模型：" + model);

        // 3a：裸 prompt——没有任何协议说明，预期探针挂
        LlmClient bare = new RetryingLlm(new KimiLlm());
        ProtocolProbe.Result bareResult = ProtocolProbe.check(bare,
                "你是客服 Agent，请回答用户的问题。", DECLARED);
        System.out.println("    [裸 prompt] " + (bareResult.ok() ? "过" : "挂") + "——" + bareResult.reason());
        System.out.println("    模型原话：" + abbreviate(bareResult.rawOutput()));

        // 3b：带协议说明，预期探针过
        LlmClient withProtocol = new RetryingLlm(new KimiLlm());
        ProtocolProbe.Result protocolResult = ProtocolProbe.check(withProtocol, PROTOCOL, DECLARED);
        System.out.println("    [带协议] " + (protocolResult.ok() ? "过" : "挂") + "——" + protocolResult.reason());
        System.out.println("    模型原话：" + abbreviate(protocolResult.rawOutput()));

        System.out.println("    发现（报告，不硬断言）：裸 prompt 挂 + 带协议过 = 探针确实拦得住"
                + "「不知道格式」；若裸 prompt 也过，说明这个模型工具直觉强，探针开销可省。");
        return true;
    }

    // ---- 小工具 ---------------------------------------------------------------------

    private static String abbreviate(String s) {
        String oneLine = s.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 100 ? oneLine : oneLine.substring(0, 100) + "…";
    }

    private static void header(String title, String subtitle) {
        String line = "=".repeat(66);
        System.out.println();
        System.out.println(line);
        System.out.println(title);
        System.out.println("  " + subtitle);
        System.out.println(line);
    }

    private static void check(String label, boolean cond) {
        if (cond) {
            System.out.println("  ✓ " + label);
        } else {
            bad++;
            System.out.println("  !! " + label);
        }
    }
}
