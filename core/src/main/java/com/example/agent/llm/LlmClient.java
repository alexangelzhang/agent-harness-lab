package com.example.agent.llm;

/**
 * LLM 抽象。所有 Agent 模式都只依赖该接口，
 * 可替换为真实模型（OpenAI 兼容接口）或脚本化假实现（便于离线演示与测试）。
 */
public interface LlmClient {

    /** 发送一段提示词，返回模型完整输出。 */
    String complete(String prompt);
}
