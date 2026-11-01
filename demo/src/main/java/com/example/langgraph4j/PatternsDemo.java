package com.example.langgraph4j;

import com.example.langgraph4j.patterns.PlanSolveGraph;
import com.example.langgraph4j.patterns.ReflectionGraph;

/**
 * 九个图版 —— langgraph4j 状态图版演示入口：
 * Reflection / Plan&Solve / Reflexion / REWOO / LLMCompiler / LATS / Self-Discover / STORM，
 * 外加系列补充 Multi-Agent（无手写版对照，图优先模式）。
 */
public class PatternsDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(66));
        System.out.println("  模式：Basic Reflection —— langgraph4j 状态图版");
        System.out.println("=".repeat(66));
        ReflectionGraph.demo();

        System.out.println("\n" + "=".repeat(66));
        System.out.println("  模式：Plan & Solve —— langgraph4j 状态图版");
        System.out.println("=".repeat(66));
        PlanSolveGraph.demo();

        System.out.println("\n" + "=".repeat(66));
        System.out.println("  模式：Reflexion —— langgraph4j 状态图版");
        System.out.println("=".repeat(66));
        com.example.langgraph4j.patterns.ReflexionGraph.demo();

        System.out.println("\n" + "=".repeat(66));
        System.out.println("  模式：REWOO —— langgraph4j 状态图版");
        System.out.println("=".repeat(66));
        com.example.langgraph4j.patterns.ReWooGraph.demo();

        System.out.println("\n" + "=".repeat(66));
        System.out.println("  模式：LLMCompiler —— langgraph4j 状态图版");
        System.out.println("=".repeat(66));
        com.example.langgraph4j.patterns.LlmCompilerGraph.demo();

        System.out.println("\n" + "=".repeat(66));
        System.out.println("  模式：LATS —— langgraph4j 状态图版");
        System.out.println("=".repeat(66));
        com.example.langgraph4j.patterns.LatsGraph.demo();

        System.out.println("\n" + "=".repeat(66));
        System.out.println("  模式：Self-Discover —— langgraph4j 状态图版");
        System.out.println("=".repeat(66));
        com.example.langgraph4j.patterns.SelfDiscoverGraph.demo();

        System.out.println("\n" + "=".repeat(66));
        System.out.println("  模式：STORM —— langgraph4j 状态图版");
        System.out.println("=".repeat(66));
        com.example.langgraph4j.patterns.StormGraph.demo();

        System.out.println("\n" + "=".repeat(66));
        System.out.println("  模式 10（补充）：Multi-Agent —— langgraph4j 状态图版");
        System.out.println("=".repeat(66));
        com.example.langgraph4j.patterns.MultiAgentGraph.demo();

        // langgraph4j 内部的异步执行器是非 daemon 线程，演示结束后显式退出
        System.exit(0);
    }
}
