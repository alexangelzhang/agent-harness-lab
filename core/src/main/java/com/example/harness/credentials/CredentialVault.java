package com.example.harness.credentials;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 凭据保管库：模型只表达意图，密钥在执行前夜才注入（文章 #7）。
 *
 * <p>错误做法是把 {@code STRIPE_API_KEY} 直接放进模型上下文——
 * 上下文会被打印、被日志记录、被压缩进摘要，密钥从此到处都是。
 * 正确做法：模型手里的工具参数只写占位符 {@code ${SECRET:stripe}}，
 * {@link #inject} 在<b>执行边界</b>把真值换进去，调用结束后密钥只存在于
 * 工具进程里。能力和凭据是两回事，分开管理。</p>
 *
 * <p>{@link #scan} 是兜底的安全网：任何要进轨迹/日志的文本先过一遍扫描，
 * 发现明文密钥立刻报泄漏——生产接告警系统，这里直接抛异常中断演示。</p>
 */
public final class CredentialVault {

    /** 在文本中发现明文密钥的泄漏告警。 */
    public static final class LeakDetected extends RuntimeException {
        private static final long serialVersionUID = 1L;

        LeakDetected(String where, String service) {
            super("凭据泄漏：" + where + " 中出现 service=" + service + " 的明文密钥");
        }
    }

    private final Map<String, String> secrets = new LinkedHashMap<>();

    /** 登记一个服务的密钥（启动时由部署环境注入，模型全程不可见）。 */
    public CredentialVault register(String service, String secret) {
        secrets.put(service, secret);
        return this;
    }

    /** 执行前夜注入：把参数里的 ${SECRET:xxx} 占位符换成真值。 */
    public String inject(String args) {
        String out = args;
        for (Map.Entry<String, String> e : secrets.entrySet()) {
            out = out.replace("${SECRET:" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    /** 扫描一段要留痕的文本：发现任何已登记密钥的明文即报泄漏。 */
    public void scan(String where, String text) {
        for (Map.Entry<String, String> e : secrets.entrySet()) {
            if (text.contains(e.getValue())) {
                throw new LeakDetected(where, e.getKey());
            }
        }
    }

    /** 脱敏副本（演示打印用）：密钥全部抹成 ***。 */
    public String redact(String text) {
        String out = text;
        for (String secret : secrets.values()) {
            out = out.replace(secret, "***");
        }
        return out;
    }
}
