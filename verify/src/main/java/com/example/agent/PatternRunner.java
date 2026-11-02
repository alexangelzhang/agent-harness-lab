package com.example.agent;

import com.example.agent.llm.ScriptedLlm;
import com.example.agent.patterns.basicreflection.BasicReflectionAgent;
import com.example.agent.patterns.compiler.LlmCompilerAgent;
import com.example.agent.patterns.lats.LatsAgent;
import com.example.agent.patterns.plan.PlanAndSolveAgent;
import com.example.agent.patterns.react.ReActAgent;
import com.example.agent.patterns.reflexion.ReflexionAgent;
import com.example.agent.patterns.rewoo.ReWooAgent;
import com.example.agent.patterns.selfdiscover.SelfDiscoverAgent;
import com.example.agent.patterns.storm.StormAgent;
import com.example.agent.tool.SampleTools;
import com.example.agent.tool.ToolRegistry;

/**
 * 九大 Agent 设计模式演示入口。
 *
 * <p>每个模式用 ScriptedLlm（脚本化假模型）预置对话，无需 API Key 即可
 * 完整跑通各模式的控制流，打印 Thought/Action/Observation 等关键轨迹。
 * 接入真实模型：把 ScriptedLlm 换成 OpenAiCompatibleLlm 即可（需 OPENAI_API_KEY）。</p>
 */
public class PatternRunner {

    public static void main(String[] args) {
        scenario1ReAct();
        scenario2PlanAndSolve();
        scenario3ReWoo();
        scenario4LlmCompiler();
        scenario5BasicReflection();
        scenario6Reflexion();
        scenario7Lats();
        scenario8SelfDiscover();
        scenario9Storm();
        System.out.println("\n全部 9 个模式演示执行完毕。");
    }

    static ToolRegistry defaultTools() {
        ToolRegistry tools = new ToolRegistry();
        SampleTools.all().forEach(tools::register);
        return tools;
    }

    static void banner(String title) {
        System.out.println("\n\n" + "=".repeat(70));
        System.out.println("  " + title);
        System.out.println("=".repeat(70));
    }

    /** 模式 1：ReAct —— Thought -> Action -> Observation 循环。 */
    static void scenario1ReAct() {
        banner("模式 1：ReAct");
        ScriptedLlm llm = new ScriptedLlm()
                .says("""
                        Thought: 这是一个复合运算，先算 25*4，再加 100。用计算器一次完成。
                        Action: Calculator[25*4+100]""")
                .says("""
                        Thought: 计算结果为 200，任务完成。
                        Action: Finish[25*4+100 = 200]""");
        var agent = new ReActAgent(llm, defaultTools(), 6);
        agent.run("25 乘以 4 再加 100 等于多少？");
    }

    /** 模式 2：Plan & Solve —— 先规划，执行中失败由 Replanner 重规划。 */
    static void scenario2PlanAndSolve() {
        banner("模式 2：Plan & Solve");
        ScriptedLlm llm = new ScriptedLlm()
                // Planner：初次计划
                .says("""
                        1. 查询订单 12345 的状态
                        2. 查询订单 12345 的退款政策
                        3. 汇总信息并回答用户""")
                // Executor 步骤 1：查订单（成功）
                .says("Action: QueryOrder[12345]")
                // Executor 步骤 2：查退款政策（失败，触发 Replanner）
                .says("Action: QueryOrder[12345 退款政策]")
                // Replanner：重新规划剩余步骤
                .says("""
                        1. 查询今日黄金价格
                        2. 汇总信息并回答用户""")
                // Executor：查黄金价格（成功）
                .says("Action: Search[黄金价格]")
                // Executor：汇总（直接作答）
                .says("订单 12345 已发货（《深入理解Java虚拟机》，99.00 元），今日黄金价格约 612 元/克。")
                // 最终综合
                .says("订单 12345 已发货，商品为《深入理解Java虚拟机》，金额 99.00 元；今日黄金价格约 612 元/克。");
        var agent = new PlanAndSolveAgent(llm, defaultTools(), 10);
        agent.run("帮我查一下订单 12345 的状态，再告诉我今天黄金价格");
    }

    /** 模式 3：REWOO —— 一次规划 + 变量传递 + Solver 汇总。 */
    static void scenario3ReWoo() {
        banner("模式 3：REWOO");
        ScriptedLlm llm = new ScriptedLlm()
                .says("""
                        Plan 1: 搜索 Google 的创立年份
                        #E1 = Search[Google 创立年份]
                        Plan 2: 用当前年份减去创立年份计算年数
                        #E2 = Calculator[2026-#E1]""")
                .says("Google 于 1998 年由拉里·佩奇和谢尔盖·布林创立，距今（2026 年）28 年。");
        var agent = new ReWooAgent(llm, defaultTools());
        agent.run("Google 成立于哪一年？距今多少年了？");
    }

    /** 模式 4：LLMCompiler —— DAG 并行调度 + Joiner 合并。 */
    static void scenario4LlmCompiler() {
        banner("模式 4：LLMCompiler（并行 Function Calling）");
        ScriptedLlm llm = new ScriptedLlm()
                .says("""
                        1 | 搜索张译的出生日期 | Search[张译 出生日期] |
                        2 | 搜索吴京的出生日期 | Search[吴京 出生日期] |
                        3 | 计算两人出生年份之差 | Calculator[1978-1974] | 1,2
                        4 | 合并结果给出结论 | Join[汇总两人出生年份与年龄差] | 3""")
                .says("吴京出生于 1974 年，张译出生于 1978 年，两人相差 4 岁。");
        var agent = new LlmCompilerAgent(llm, defaultTools());
        agent.run("张译和吴京相差几岁？");
    }

    /** 模式 5：Basic Reflection —— Generator 与 Reflector 往复迭代。 */
    static void scenario5BasicReflection() {
        banner("模式 5：Basic Reflection");
        ScriptedLlm llm = new ScriptedLlm()
                // Generator 初稿
                .says("冷萃咖啡液，一口清醒一整天。")
                // Reflector 批语
                .says("建议：没有体现‘低温慢萃’的工艺卖点；句子偏长，不够顺口。评分 6/10。")
                // Generator 修改稿
                .says("十二小时低温慢萃，唤醒每一刻。")
                // Reflector 通过
                .says("语言精炼、卖点清晰，符合要求。ACCEPT");
        var agent = new BasicReflectionAgent(llm, 5);
        agent.run("为一款冷萃咖啡液写一句广告 slogan");
    }

    /** 模式 6：Reflexion —— 环境校验 + 语言反思记忆 + 重试。 */
    static void scenario6Reflexion() {
        banner("模式 6：Reflexion");
        ScriptedLlm llm = new ScriptedLlm()
                // Actor 第 1 次（答错）
                .says("自我批评：直接相加即可。17+25 = 52。")
                // Self-Reflection
                .says("计算错误：个位 7+5=12 需要进位，17+25 的正确结果应为 42。下次先处理进位。")
                // Actor 第 2 次（答对）
                .says("自我批评：注意个位进位，7+5=12 进 1。17+25 = 42。");
        var agent = new ReflexionAgent(llm, answer -> answer.contains("42"),
                "最终答案必须包含正确数值 42", 3);
        agent.run("17 + 25 等于多少？");
    }

    /** 模式 7：LATS —— 树搜索扩展、评分、择优深入。 */
    static void scenario7Lats() {
        banner("模式 7：LATS（Language Agent Tree Search）");
        ScriptedLlm llm = new ScriptedLlm()
                // 第 1 层扩展：两个候选动作
                .says("""
                        Action: Search[Java 虚拟线程]
                        Action: Search[Java 历史]""")
                // 评估两个分支
                .says("9")
                .says("3")
                // 第 2 层扩展：收敛为 Finish
                .says("""
                        Action: Finish[虚拟线程是 JDK 21 通过 JEP 444 引入的轻量级线程，适合 IO 密集型高并发场景，如微服务网关与海量连接服务端程序。]""")
                // 评估 Finish 分支
                .says("10");
        var agent = new LatsAgent(llm, defaultTools(), 5, 2);
        agent.run("帮我了解 Java 的虚拟线程特性，并总结它适合什么场景");
    }

    /** 模式 8：Self-Discover —— 选择推理模块、组装结构、按结构执行。 */
    static void scenario8SelfDiscover() {
        banner("模式 8：Self-Discover");
        ScriptedLlm llm = new ScriptedLlm()
                // Selector
                .says("""
                        分步骤推理
                        拆解为子问题""")
                // Adaptor
                .says("""
                        {"structure": "先拆解数量关系再逐步计算", "steps": [
                          {"step": "弄清 2 打苹果是多少个", "module": "拆解为子问题"},
                          {"step": "把原有苹果与新买的苹果相加", "module": "分步骤推理"}
                        ]}""")
                // Implementor 第 1 步
                .says("2 打 = 2 × 12 = 24 个苹果")
                // Implementor 第 2 步
                .says("3 + 24 = 27 个苹果")
                // 汇总
                .says("小明一共有 27 个苹果。");
        var agent = new SelfDiscoverAgent(llm);
        agent.run("小明有 3 个苹果，又买了 2 打（每打 12 个），他现在一共有多少个苹果？");
    }

    /** 模式 9：STORM —— 大纲 -> 多视角调研 -> 分章撰写 -> 汇总润色。 */
    static void scenario9Storm() {
        banner("模式 9：STORM");
        ScriptedLlm llm = new ScriptedLlm()
                // 大纲
                .says("""
                        概述
                        语言新特性
                        适用场景""")
                // 章节 1：概述 —— 三个视角的提问
                .says("什么是虚拟线程？")
                .says("虚拟线程解决了什么问题？")
                .says("虚拟线程是银弹吗？")
                // 章节 1 正文
                .says("虚拟线程是 JDK 21 正式引入的轻量级并发原语（JEP 444），它把线程的创建成本大幅降低，让 Java 服务端编程回归简单的同步风格。")
                // 章节 2：语言新特性 —— 三个视角的提问
                .says("虚拟线程在 Java 的哪个版本引入？")
                .says("虚拟线程与传统平台线程有何区别？")
                .says("虚拟线程的 API 是什么样的？")
                // 章节 2 正文
                .says("开发者通过 Thread.ofVirtual() 或 Executors.newVirtualThreadPerTaskExecutor() 即可创建虚拟线程，无需学习新的异步 API。")
                // 章节 3：适用场景 —— 三个视角的提问
                .says("虚拟线程适合什么类型的应用？")
                .says("使用虚拟线程有哪些注意事项？")
                .says("虚拟线程对吞吐量的提升有多大？")
                // 章节 3 正文
                .says("虚拟线程尤其适合 IO 密集型、高并发的服务端应用，如网关、聊天服务与数据库中间件；CPU 密集场景则收益有限。")
                // 引言 + 总结
                .says("本文系统介绍 Java 虚拟线程：它是什么、如何上手以及适合哪些场景。")
                .says("虚拟线程以极低的成本缓解了 Java 高并发编程的复杂度，是近年来 JVM 最重要的语言级更新之一。");
        var agent = new StormAgent(llm, SampleTools.search());
        agent.run("Java 虚拟线程");
    }
}
