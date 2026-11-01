package com.example.harness.credentials;

import java.util.ArrayList;
import java.util.List;

/**
 * 凭据保管演示：密钥不进模型上下文（2026-10-31 十二部件文章 #7）。
 *
 * <p>场景「正常路径」：模型拿到「给客户退款」的意图，工具参数里只有
 * {@code ${SECRET:stripe}} 占位符；执行边界经 {@link CredentialVault#inject}
 * 换成真值完成调用；整条轨迹（提示词 + 工具参数 + 返回）过 {@code scan}
 * 确认没有明文密钥。</p>
 *
 * <p>场景「兜底拦住泄漏」：某个配置错的工具把注入后的完整参数原样写进
 * 返回值——scan 在留痕前抓住它，演示直接抛 {@link CredentialVault.LeakDetected}，
 * 生产里这里是告警 + 吊销密钥。</p>
 *
 * <p>运行：{@code mvn exec:java@credentials}。</p>
 */
public class CredentialDemo {

    public static void main(String[] args) {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("凭据保管：模型只见意图，密钥执行前夜才注入");
        System.out.println(line);

        CredentialVault vault = new CredentialVault()
                // 演示占位密钥：拼出来是为了不碰 Stripe 真实密钥的格式（GitHub 推送保护会拦）
                .register("stripe", "sk_" + "demo" + "_" + "0123456789abcdef");

        // 模型看到的世界：工具定义 + 占位符参数，没有任何密钥
        String modelContext = """
                工具 refund(customer_id, amount, credential=${SECRET:stripe})：
                调用 Stripe 退款接口，credential 由系统在执行时注入。""";
        String toolArgs = "customer_id=cust_42 amount=1800 credential=${SECRET:stripe}";
        vault.scan("模型上下文", modelContext);
        vault.scan("工具参数", toolArgs);
        System.out.println("模型上下文与工具参数过扫描：无明文密钥 ✓");
        System.out.println();

        // 执行边界：注入真值，调用，返回里只有结果
        String injected = vault.inject(toolArgs);
        String toolResult = "refund_ok: re_3NkX... 退款 1800 元已受理";
        vault.scan("工具返回", toolResult);
        System.out.println("【正常路径】");
        System.out.println("  工具入参（执行边界注入后，仅此一处出现真值）：");
        System.out.println("    " + vault.redact(injected));
        System.out.println("  工具返回：" + toolResult);
        System.out.println("  整条轨迹过扫描：无泄漏 ✓");
        System.out.println();

        // 兜底：配置错的工具把注入后的参数原样吐回
        System.out.println("【兜底路径】配置错的工具把完整入参写进了返回值：");
        String leakyResult = "调用完成，入参=" + injected;
        List<String> leakedTo = new ArrayList<>();
        try {
            vault.scan("工具返回", leakyResult);
        } catch (CredentialVault.LeakDetected e) {
            leakedTo.add("工具返回");
            System.out.println("  ✗ " + e.getMessage());
            System.out.println("  生产动作：告警 + 吊销该密钥 + 拦下入档。");
            System.out.println("  脱敏后的文本长这样：" + vault.redact(leakyResult));
        }
        boolean caught = !leakedTo.isEmpty();
        System.out.println();
        System.out.println(line);
        System.out.println(caught
                ? "收尾：密钥只活在执行进程里；任何地方出现明文，扫描器先喊。"
                : "!! 泄漏未被抓住");
        System.out.println(line);
        System.exit(0);
    }
}
