package com.example.harness.memory;

import com.example.langgraph4j.llm.JdkHttpLlm;
import com.example.langgraph4j.llm.LlmClient;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆系统真实模型闭环：召回注入 → 模型提取写回 → 写入闸门 → 遗忘在岗
 * （2026-10-31，{@code MemoryGovernanceDemo} 剧本版的真实对照组）。
 *
 * <p>剧本版证明「机制对」；这个 demo 证明「真实模型输出喂进来，
 * 机制还对」——模型说的话进 Consolidator 之前先过写入闸门，
 * 过期记忆靠 Janitor 在召回周期里自动清，不靠人盯。</p>
 *
 * <p>三个场景各一次真实调用（真实调用慢，{@code -Dexec.args="n"} 可
 * 只跑第 n 个）：</p>
 * <ol>
 *   <li><b>带记忆回答</b>：RecallPipeline 召回注入 → 模型基于注入的
 *       项目决定回答——记忆不是档案，是当次任务的生产资料；</li>
 *   <li><b>对话里长出新记忆</b>：模型从对话里提取值得记住的事实、
 *       自己打置信度；低置信（&lt;0.6）的推断被写入闸门挂成
 *       UNCONFIRMED，人工确认才转正；</li>
 *   <li><b>遗忘在岗</b>：8 次召回自动触发 sweep，过期记忆不撞进
 *       模型的上下文。</li>
 * </ol>
 *
 * <p>硬断言只校结构（状态迁移、计数、注入条数），模型措辞不断言——
 * 真实输出有波动，机制行为必须稳定。</p>
 *
 * <p>运行：{@code mvn exec:java@memoryreal -Dexec.args="[1-3]"}，
 * 需要 {@code LLM_API_KEY}，缺了明确报错退出。</p>
 */
public class MemoryRealDemo {

    private static final Pattern CONFIDENCE = Pattern.compile(
            "CONFIDENCE[:：]\\s*([01](?:\\.\\d+)?)");

    public static void main(String[] args) {
        if (System.getenv("LLM_API_KEY") == null) {
            System.err.println("缺少 LLM_API_KEY 环境变量，无法调用真实模型");
            System.exit(1);
        }
        int pick = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        LlmClient llm = new JdkHttpLlm();
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("记忆系统真实闭环：召回注入 / 提取写回 / 写入闸门 / 遗忘在岗");
        System.out.println("模式：真实模型（JdkHttpLlm）" + (pick > 0 ? "，只跑场景 " + pick : ""));
        System.out.println(line);

        boolean ok = true;
        if (pick == 0 || pick == 1) {
            ok &= scenarioRecallAndAnswer(llm);
        }
        if (pick == 0 || pick == 2) {
            ok &= scenarioExtractAndGate(llm);
        }
        if (pick == 0 || pick == 3) {
            ok &= scenarioForgetInLoop(llm);
        }

        System.out.println();
        System.out.println(line);
        System.out.println(ok
                ? "收尾：真实输出喂进来，机制照旧——记忆是生产资料，"
                        + "写回有闸门，遗忘在岗。"
                : "!! 真实闭环结构校验没走通");
        System.out.println(line);
        System.exit(0);
    }

    /** 场景一：召回注入 → 模型带着记忆回答。 */
    private static boolean scenarioRecallAndAnswer(LlmClient llm) {
        header("场景一：带记忆回答", "记忆不是档案，是当次任务的生产资料");
        MemoryStore store = new MemoryStore();
        store.add(Memory.Scope.PROJECT, "支付中台",
                "支付中台接口评审意见统一用中文写", "2026-10 评审会决议",
                List.of("中文写"), 0.95, 0, List.of());
        store.add(Memory.Scope.PROJECT, "支付中台",
                "支付中台对外接口必须先过风控预审", "2026-10 合规整改",
                List.of("评审", "风控"), 0.9, 0, List.of());

        String task = "给支付中台写一份接口评审邀请，正文要点出来";
        RecallPipeline.RecallResult r = new RecallPipeline(new MemoryMetrics())
                .recall(task, List.of(), store, KnowledgeBase.NONE, List.of());
        System.out.println("  召回解释：");
        r.notes().forEach(n -> System.out.println("    - " + n));
        System.out.println("  注入 " + r.contextLines().size() + " 条进提示词。");

        String answer = llm.complete("""
                你是支付中台项目的助手。以下是从项目长期记忆里召回的背景，
                写邀请函时必须遵守；不要编造记忆里没有的要求。

                %s

                任务：%s。只写邀请函正文，100 字以内。
                """.formatted(r.contextBlock(), task));
        System.out.println("  模型成稿：");
        for (String l : answer.lines().toList()) {
            System.out.println("    " + l);
        }
        boolean injected = r.contextLines().size() >= 2;
        System.out.println("  " + (injected
                ? "结构校验：两条项目记忆都进了提示词。"
                : "!! 注入条数不足，记忆没接上任务"));
        return injected;
    }

    /** 场景二：模型提取事实 + 自评置信 → 写入闸门。 */
    private static boolean scenarioExtractAndGate(LlmClient llm) {
        header("场景二：提取写回 + 写入闸门", "模型推断的，先挂起来，别直接当真事用");
        String dialogue = """
                用户：上次说的评审语言那个事，邮件里也写一下。
                助手：好的，评审意见用中文写这条会在邮件里写明。
                用户：另外我觉得网关超时可能是 3 秒，你们查一下。
                助手：好，我去查一下最近的调用日志。
                """;
        System.out.println("  对话原文：");
        dialogue.lines().forEach(l -> System.out.println("    " + l));

        String extracted = llm.complete("""
                从下面的对话中提取值得长期记住的事实（用户偏好、项目决定），
                每条一行，严格按格式输出，不要输出任何其他文字：
                FACT: <一句话事实> | CONFIDENCE: <0 到 1 的小数>
                规则：
                - 用户明确说过的话可以给 0.8 以上；
                - 只是怀疑/推测的（比如「可能是」），如实给低置信（0.5 以下），
                  不许硬拔高；
                - 两条都要提取，一条都不许漏。

                对话：
                %s
                """.formatted(dialogue));
        System.out.println("  模型提取：");
        extracted.lines().filter(l -> l.strip().startsWith("FACT:"))
                .toList()
                .forEach(l -> System.out.println("    " + l.strip()));

        MemoryStore store = new MemoryStore();
        List<Memory> pending = new ArrayList<>();
        for (String line : extracted.lines().toList()) {
            String s = line.strip();
            if (!s.startsWith("FACT:")) {
                continue;
            }
            Matcher cm = CONFIDENCE.matcher(s);
            double confidence = cm.find() ? Double.parseDouble(cm.group(1)) : 0.5;
            String fact = s.substring("FACT:".length()).split("\\|")[0].strip();
            Memory m = store.add(Memory.Scope.PROJECT, "andy", fact,
                    "模型从对话提取", List.of("提取"), confidence, 0, List.of());
            pending.add(m);
            System.out.println("  落库：「" + fact + "」置信 " + confidence
                    + " → " + m.status());
        }

        long unconfirmed = store.all().stream()
                .filter(m -> m.status() == Memory.Status.UNCONFIRMED).count();
        System.out.println("  闸门结果：" + unconfirmed + " 条挂起（检索不出），"
                + (pending.size() - unconfirmed) + " 条直接生效。");
        for (Memory m : pending) {
            store.confirm(m.id());
        }
        boolean confirmed = store.all().stream().allMatch(m -> m.status()
                == Memory.Status.ACTIVE);
        System.out.println("  人工确认后全部转正：" + (confirmed ? "是" : "!! 有没转正的"));
        return !pending.isEmpty() && unconfirmed >= 1 && confirmed;
    }

    /** 场景三：遗忘在岗——8 次召回自动 sweep，过期记忆不进模型上下文。 */
    private static boolean scenarioForgetInLoop(LlmClient llm) {
        header("场景三：遗忘在岗", "过期记忆别撞进模型的上下文");
        MemoryStore store = new MemoryStore();
        long now = System.currentTimeMillis();
        store.add(Memory.Scope.PROJECT, "支付中台",
                "上周演练改到周五（这条已作废）", "旧演练通知",
                List.of("演练"), 0.9, now - 1, List.of());
        store.add(Memory.Scope.PROJECT, "支付中台",
                "接口评审定在下周一 10 点", "最新会议纪要",
                List.of("评审", "时间"), 0.95, 0, List.of());

        MemoryMetrics metrics = new MemoryMetrics();
        RecallPipeline pipeline = new RecallPipeline(metrics);
        for (int i = 0; i < 8; i++) {
            pipeline.recall("继续上次的接口评审安排", List.of(), store,
                    KnowledgeBase.NONE, List.of());
        }
        MemoryMetrics.Snapshot s = metrics.snapshot();
        System.out.println("  8 次召回后清理计数：到期 " + s.janitorExpired()
                + " / 归档 " + s.janitorArchived());
        boolean expired = store.all().stream().anyMatch(m -> m.status()
                == Memory.Status.EXPIRED);
        System.out.println("  过期演练通知 → " + (expired ? "已置 EXPIRED" : "!! 还挂着"));

        // EXPIRED 记忆检索默认不出——撞不进这次注入的上下文
        RecallPipeline.RecallResult r = pipeline.recall(
                "继续上次的接口评审安排", List.of(), store,
                KnowledgeBase.NONE, List.of());
        System.out.println("  第 9 次召回注入 " + r.contextLines().size()
                + " 条（只含仍有效的记忆）。");

        String answer = llm.complete("""
                以下是从项目长期记忆里召回的背景，只按这些信息回答。

                %s

                问题：接口评审是什么时候？只答时间。
                """.formatted(r.contextBlock()));
        System.out.println("  模型回答：" + answer.lines().findFirst().orElse("（空）"));
        return expired && s.janitorExpired() >= 1 && !r.contextLines().isEmpty();
    }

    private static void header(String title, String subtitle) {
        System.out.println();
        System.out.println("── " + title + " ── " + subtitle);
        System.out.println();
    }
}
