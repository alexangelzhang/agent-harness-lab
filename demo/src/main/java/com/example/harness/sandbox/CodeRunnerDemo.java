package com.example.harness.sandbox;

import com.example.langgraph4j.llm.CountingLlm;
import com.example.langgraph4j.llm.ScriptedLlm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 代码执行批处理演示：模型写一次脚本，沙箱跑完全部循环（文章 #5）。
 *
 * <p>任务：跨 CRM 和账单两个系统，找出有逾期账单的客户。逐条编排要
 * 5 个客户 × 2 次调用 = 10 次模型调用；{@link CodeRunner} 的姿势是
 * 模型第 2 轮直接写出处理脚本，循环在沙箱里跑完，第 3 轮只读结论——
 * LLM 调用从 10 次降到 3 次，且脚本执行带策略拦截、超时和审计。</p>
 *
 * <p>演示数据写在 {@code runs/code-runner-demo/}，跑完清理。
 * 运行：{@code mvn exec:java@coderunner}（需要本机有 python3）。</p>
 */
public class CodeRunnerDemo {

    private static final Path WORKDIR = Path.of("runs", "code-runner-demo");

    public static void main(String[] args) throws Exception {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("代码执行批处理：模型写脚本，沙箱跑循环，结论一次交还");
        System.out.println(line);

        seedData();
        LocalSandbox sandbox = new LocalSandbox(
                "code-runner", WORKDIR, Duration.ofSeconds(30),
                cmd -> cmd.contains("rm -rf /"));
        sandbox.create();
        CodeRunner runner = new CodeRunner(sandbox, WORKDIR);

        var llm = new CountingLlm(new ScriptedLlm()
                // 第 1 轮：理解任务 + 定处理思路
                .says("两个数据文件：customers.csv（5 个客户）、invoices.csv（12 张账单，"
                        + "status 有 paid/overdue）。思路：按 customer_id 关联，筛 overdue，按客户聚合。")
                // 第 2 轮：写出处理脚本（循环在脚本里，不在模型里）
                .says("""
                        import csv
                        from collections import defaultdict

                        customers = {}
                        with open('customers.csv') as f:
                            for row in csv.DictReader(f):
                                customers[row['id']] = row['name']

                        overdue = defaultdict(list)
                        with open('invoices.csv') as f:
                            for row in csv.DictReader(f):
                                if row['status'] == 'overdue':
                                    overdue[row['customer_id']].append(row['id'])

                        for cid, ids in sorted(overdue.items()):
                            print(f"{customers[cid]}({cid}): {len(ids)} 张逾期 {ids}")
                        """));
        System.out.println("【第 1 轮】模型理解任务：" + llm.complete("任务：找出有逾期账单的客户"));
        String code = llm.complete("把处理逻辑写成 python 脚本，只输出代码");
        System.out.println("【第 2 轮】模型写出脚本（" + code.strip().lines().count() + " 行，循环在里面）");
        Sandbox.ExecResult result = runner.run("python3", "analyze.py", code);
        System.out.println("【沙箱执行】退出码 " + result.exitCode() + "，耗时 " + result.durationMs() + "ms");
        System.out.println("【第 3 轮】模型只读结论：");
        result.stdout().lines().forEach(l -> System.out.println("    " + l));

        int perRecordCalls = 5 * 2; // 5 客户 × （查账单+判断）
        System.out.println();
        System.out.println("对比：逐条编排要 LLM 调用 " + perRecordCalls + " 次 + 等 10 次往返；");
        System.out.println("      写脚本跑沙箱只用了 " + llm.calls() + " 次（理解任务 → 写脚本），结论随执行直接回来。");
        sandbox.destroy();
        cleanup();
        boolean ok = result.ok() && result.stdout().contains("星海科技")
                && llm.calls() == 2;
        System.out.println();
        System.out.println(line);
        System.out.println(ok
                ? "收尾：重复机械的执行归沙箱，模型的注意力只花在需要推理的地方。"
                : "!! 批处理闭环没走通");
        System.out.println(line);
        System.exit(0);
    }

    /** 造演示数据：5 个客户、12 张账单，其中 4 张逾期。 */
    private static void seedData() {
        try {
            Files.createDirectories(WORKDIR);
            Files.writeString(WORKDIR.resolve("customers.csv"), """
                    id,name
                    c01,星海科技
                    c02,蓝湾物流
                    c03,橙子文创
                    c04,北岭制造
                    c05,同方贸易
                    """);
            Files.writeString(WORKDIR.resolve("invoices.csv"), """
                    id,customer_id,amount,status
                    inv01,c01,12000,overdue
                    inv02,c01,8600,paid
                    inv03,c02,5300,paid
                    inv04,c03,21000,overdue
                    inv05,c03,4400,overdue
                    inv06,c04,9800,paid
                    inv07,c01,6600,overdue
                    inv08,c05,15200,paid
                    inv09,c02,3100,overdue
                    inv10,c04,27500,paid
                    inv11,c05,7800,paid
                    inv12,c03,11900,paid
                    """);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void cleanup() {
        try (Stream<Path> walk = Files.walk(WORKDIR)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
