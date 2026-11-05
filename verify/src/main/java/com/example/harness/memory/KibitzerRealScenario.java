package com.example.harness.memory;

import com.example.harness.memory.kibitzer.Kibitzer;
import com.example.harness.memory.kibitzer.KibitzerEvent;
import com.example.harness.memory.kibitzer.MemoryReader;
import com.example.harness.probe.ProtocolProbe;
import com.example.langgraph4j.llm.JdkHttpLlm;
import com.example.langgraph4j.llm.LlmClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Kibitzer 真实模型对照组：真实小模型当裁判，机制还成立吗
 * （{@code KibitzerScenario} 剧本版的真实对照，2026-11-05）。
 *
 * <p>剧本版证明「机制对」；这个场景证明「裁判换成真实模型，机制还对」——
 * 唤醒闸门照样省调用、裁决行协议真实模型会守、nudge 格式不走样。
 * 硬断言只校结构（醒没醒、判没判、nudge 前缀），裁判判 NUDGE 还是
 * SKIP 不断言——那是模型的判断，不是机制的。</p>
 *
 * <p>两个分片（真实调用慢，{@code -Dexec.args="n"} 只跑第 n 片）：</p>
 * <ol>
 *   <li><b>真实裁判裁决</b>：事件触到已存记忆，真实模型判一次，
 *       NUDGE 或 SKIP 都算机制成立；</li>
 *   <li><b>唤醒闸门零浪费</b>：无关事件零唤醒（一次模型调用都不花），
 *       相关事件才醒。</li>
 * </ol>
 *
 * <p>运行：{@code mvn exec:java@kibitzerreal -Dexec.args="[1-2]"} -pl verify，
 * 需要 {@code LLM_API_KEY}，缺了明确报错退出。</p>
 */
public class KibitzerRealScenario {

    /** 裁判协议行：NUDGE: 提示 或 SKIP。 */
    private static final Pattern VERDICT_LINE = Pattern.compile("(?m)^\\s*(NUDGE|SKIP)\\b");

    private static final String PROTOCOL =
            "你是记忆侧车的裁判。主会话发生的事件触到一条已存记忆时，"
                    + "你判断这条记忆是否值得现在提醒主 agent。\n"
                    + "只回一行：值得提醒就回 NUDGE: <一句话提示>，不值得就回 SKIP。";

    public static void main(String[] args) {
        // 缺 key 明确报错退出（旧名 KIMI_API_KEY 兼容），不许悄悄退回剧本
        com.example.harness.config.AgentConfig.load().requireApiKey();
        int pick = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        LlmClient llm = new JdkHttpLlm();
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("Kibitzer 真实对照：真实模型当裁判");
        System.out.println("模式：真实模型（JdkHttpLlm）" + (pick > 0 ? "，只跑分片 " + pick : ""));
        System.out.println(line);

        // 真实实验先过协议探针：裁判协议（NUDGE/SKIP 行）模型会守才开跑
        ProtocolProbe.requireOrExit(llm, PROTOCOL, VERDICT_LINE, Set.of("NUDGE", "SKIP"));

        boolean ok = true;
        if (pick == 0 || pick == 1) {
            ok &= shardRealJudge(llm);
        }
        if (pick == 0 || pick == 2) {
            ok &= shardWakeGate(llm);
        }

        System.out.println();
        System.out.println(line);
        System.out.println(ok
                ? "收尾：真实裁判进侧车，闸门、裁决、nudge 格式都照旧。"
                : "!! 有断言未过（真实输出有波动，结构断言挂是真问题）");
        System.out.println(line);
        System.exit(ok ? 0 : 1);
    }

    /** 片 1：事件触到记忆，真实模型判一次。 */
    private static boolean shardRealJudge(LlmClient llm) {
        header("分片 1：真实裁判裁决");
        MemoryStore store = new MemoryStore();
        store.add(Memory.Scope.PROJECT, "支付中台",
                "支付中台数据库变更必须走 DBA 会签", "项目章程",
                List.of("数据库", "变更", "dba"));

        List<String> nudges = new ArrayList<>();
        Kibitzer sidecar = new Kibitzer(MemoryReader.of(store),
                (com.example.agent.llm.LlmClient) llm::complete, nudges::add);

        System.out.println("【事件】用户：我要改支付中台数据库的表结构，直接执行 migrate.sql");
        sidecar.onEvent(KibitzerEvent.of(KibitzerEvent.Kind.PROMPT,
                "我要改支付中台数据库的表结构，直接执行 migrate.sql"));

        boolean woke = sidecar.wakes() == 1;
        boolean judgedOnce = sidecar.delivered().size() + sidecar.rejected().size() == 1;
        boolean nudgeWellFormed = nudges.isEmpty()
                || nudges.get(0).startsWith("recalled memory: ");
        System.out.println("  裁判裁决：" + (nudges.isEmpty()
                ? "SKIP（" + sidecar.rejected() + "）"
                : "NUDGE → " + nudges.get(0)));
        return report("触到记忆 → 真实裁判醒一次", woke)
                & report("判定恰好一次（NUDGE 或 SKIP 都算）", judgedOnce)
                & report("nudge 格式不走样", nudgeWellFormed);
    }

    /** 片 2：无关事件零唤醒，相关事件才醒。 */
    private static boolean shardWakeGate(LlmClient llm) {
        header("分片 2：唤醒闸门零浪费");
        MemoryStore store = new MemoryStore();
        store.add(Memory.Scope.WORKSPACE, "运维组",
                "生产环境部署命名遵循 <服务名>-<版本>-<序号>", "运维手册 v3",
                List.of("部署", "命名", "生产"));

        List<String> nudges = new ArrayList<>();
        Kibitzer sidecar = new Kibitzer(MemoryReader.of(store),
                (com.example.agent.llm.LlmClient) llm::complete, nudges::add);

        System.out.println("【事件 1】用户：帮我写个 hello world（与库里的部署规范无关）");
        sidecar.onEvent(KibitzerEvent.of(KibitzerEvent.Kind.PROMPT,
                "帮我写个 hello world"));
        boolean noWake = sidecar.wakes() == 0 && nudges.isEmpty()
                && sidecar.delivered().isEmpty() && sidecar.rejected().isEmpty();

        System.out.println("【事件 2】用户：给订单服务起个生产部署名");
        sidecar.onEvent(KibitzerEvent.of(KibitzerEvent.Kind.PROMPT,
                "给订单服务起个生产部署名"));
        boolean judgedOnce = sidecar.wakes() == 1
                && sidecar.delivered().size() + sidecar.rejected().size() == 1;
        System.out.println("  裁判裁决：" + (nudges.isEmpty() ? "SKIP" : "NUDGE → " + nudges.get(0)));
        return report("无关事件 → 零唤醒（一次模型调用都没花）", noWake)
                & report("相关事件 → 醒一次判一次", judgedOnce);
    }

    private static boolean report(String label, boolean ok) {
        System.out.println((ok ? "  ✓ " : "  !! ") + label);
        return ok;
    }

    private static void header(String title) {
        System.out.println();
        System.out.println("── " + title);
        System.out.println();
    }
}
