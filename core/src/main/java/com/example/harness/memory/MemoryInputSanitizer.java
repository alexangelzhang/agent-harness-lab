package com.example.harness.memory;

import com.example.harness.credentials.CredentialVault;
import com.example.harness.security.InjectionGuard;

/**
 * 不可信记忆内容的净化边界。原始 transcript 留在证据层，
 * 分析与整理只接触这个类返回的新副本；不会把脱敏结果回写成证据。
 */
public final class MemoryInputSanitizer {

    private final InjectionGuard injectionGuard;
    private final CredentialVault credentialVault;

    public MemoryInputSanitizer(InjectionGuard injectionGuard, CredentialVault credentialVault) {
        this.injectionGuard = injectionGuard;
        this.credentialVault = credentialVault;
    }

    /** 为单份分析生成净化副本，原对象及其 entries 保持不变。 */
    public MemoryTranscript sanitize(MemoryTranscript original) {
        return new MemoryTranscript(original.entries().stream().map(this::sanitize).toList());
    }

    /** 同时处理分析输出，防止已登记密钥或注入指令经候选写回记忆。 */
    public String sanitize(String untrusted) {
        return injectionGuard.sanitize(credentialVault.redact(untrusted));
    }
}
