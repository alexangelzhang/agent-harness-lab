package com.example.harness.toolbase;

import com.example.harness.toolbase.SlopCommentChecker.Category;
import com.example.harness.toolbase.SlopCommentChecker.Finding;
import com.example.harness.toolbase.SlopWriteGuard.Severity;
import com.example.harness.toolbase.SlopWriteGuard.Verdict;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI 腔注释护栏剧本（OmO comment-checker 研读落地，2026-11-06）。
 *
 * <p>三幕：</p>
 * <ul>
 *   <li><b>模式库</b>：四类 AI 腔各埋一条，行号和类别都得揪对；
 *       逃生口（{@code @allow-comment} 行、disable-file 文件头）要放行；</li>
 *   <li><b>写入链路</b>：ERROR 档拦死带脏注释的写入（打回文本带行号类别），
 *       修好后放行；WARN 档放行但带命中清单；</li>
 *   <li><b>不误伤</b>：讲 WHY 的注释、工单链接、正则解释、BDD 标记全过。</li>
 * </ul>
 *
 * <p>运行：{@code mvn exec:java@commentcheck -pl verify}（离线剧本，无需 key）。</p>
 */
public class SlopCommentScenario {

    public static void main(String[] args) {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("AI 腔注释护栏：垃圾注释在落盘前被打回");
        System.out.println(line);

        boolean ok = true;
        SlopCommentChecker checker = new SlopCommentChecker();

        // ── 第一幕：模式库逐项揪
        System.out.println("── 第一幕：模式库");
        String dirty = """
                // 显然这里要把计数器加一
                counter++;
                // ==============================
                // TODO:
                // increment the counter
                counter.increment();
                // @allow-comment obviously this line is allowed
                """;
        List<Finding> findings = checker.scan(dirty);
        Set<Category> caught = findings.stream()
                .map(Finding::category).collect(Collectors.toSet());
        for (Finding f : findings) {
            System.out.println("  揪到 第 " + f.line() + " 行 [" + f.category() + "] " + f.excerpt());
        }
        ok &= report("四类各揪到一条", caught.containsAll(Set.of(
                Category.FILLER, Category.DECORATIVE_SEPARATOR,
                Category.EMPTY_TODO, Category.ECHO_COMMENT)));
        ok &= report("行号对得上（FILLER 在第 1 行、ECHO 在第 5 行）",
                findings.stream().anyMatch(f -> f.line() == 1 && f.category() == Category.FILLER)
                        && findings.stream().anyMatch(f -> f.line() == 5
                                && f.category() == Category.ECHO_COMMENT));
        ok &= report("@allow-comment 逃生口放行（7 行不被揪）",
                findings.stream().noneMatch(f -> f.line() == 7));
        ok &= report("复述误伤防线：中文复述不做词面比对",
                checker.scan("// 计数器加一\ncounter++;\n").isEmpty());
        ok &= report("disable-file 文件头整文件免检",
                checker.scan("// comment-checker-disable-file\n// obviously\nint x = 1;\n")
                        .isEmpty());
        System.out.println();

        // ── 第二幕：写入链路
        System.out.println("── 第二幕：写入链路");
        SlopWriteGuard guard = new SlopWriteGuard(Severity.ERROR);
        Verdict blocked = guard.check("OrderService.java", dirty);
        System.out.println("  打回文本节选：" + blocked.errorMessage().lines()
                .limit(2).collect(Collectors.joining(" / ")));
        ok &= report("ERROR 档拦死带脏注释的写入", !blocked.allowed()
                && blocked.errorMessage().contains("第 1 行"));
        String fixed = """
                // 补偿 legacy importer 的 off-by-one（工单 PAY-1123）
                counter++;
                counter.increment();
                """;
        ok &= report("修好后放行", guard.check("OrderService.java", fixed).allowed());
        SlopWriteGuard warnGuard = new SlopWriteGuard(Severity.WARN);
        Verdict warned = warnGuard.check("OrderService.java", dirty);
        ok &= report("WARN 档放行但带命中清单", warned.allowed()
                && !warned.findings().isEmpty());
        System.out.println();

        // ── 第三幕：干净代码不误伤
        System.out.println("── 第三幕：不误伤");
        String clean = """
                // 这里必须用悲观锁：库存扣减在秒杀下会被超卖（工单 PAY-998）
                // 正则解释：\\d{4}-\\d{2} 匹配 ISO 月份，历史数据里有 13 月的脏数据
                // given 用户已登录
                // when 提交订单
                // then 库存减一
                lock();
                """;
        List<Finding> cleanFindings = checker.scan(clean);
        ok &= report("讲 WHY / 工单链接 / 正则解释 / BDD 标记全过", cleanFindings.isEmpty());

        System.out.println();
        System.out.println(line);
        System.out.println(ok
                ? "全绿：四类腔调落盘前打回，逃生口放行、干净注释不误伤。"
                : "!! 有断言未过");
        System.out.println(line);
        System.exit(ok ? 0 : 1);
    }

    private static boolean report(String label, boolean ok) {
        System.out.println((ok ? "  ✓ " : "  !! ") + label);
        return ok;
    }
}
