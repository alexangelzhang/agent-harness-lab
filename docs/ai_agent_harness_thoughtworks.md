# What does an AI agent harness actually do?（ThoughtWorks Insights）

来源：https://www.thoughtworks.com/insights/blog/machine-learning-and-ai/ai-agent-harness

Birgitta Böckeler 用类似方式解释这层关系：LLM 是核心，harness 是外围层，把工具、prompt 和其他机制拢在一起，让模型更能胜任实际工作。**更好的模型不自动等于更好的 agent——外围环境同样重要。**

## 为什么 agent 需要 harness

模型能推理问题，但仍可能完不成任务。「修复这个应用的认证 bug」——识别可能的修复只是起点，落地和验证还会引出：认证代码在哪？项目用什么框架和约定？该改哪些文件？跑什么命令？怎么测试？怎么知道问题真修好了？第一个方案不work 怎么办？一条 prompt 给不了这一切。

harness 提供任务外围的结构：告诉 agent 项目怎么运作、给它工具、跑检查、保留有用的状态、出错时给反馈。这才是 agent 区别于「对 prompt 回话的 LLM」的地方。

## harness 实际做的六件事

1. **给指令**：通过系统 prompt 告诉 agent 编码标准、应用结构、用哪些命令、怎么跑测试、仓库哪些部分要特别小心。这引入 **context engineering**：在有限的上下文窗口里选择、组织和维护 agent 需要的信息。目标是把关键规则说清楚、把详细信息需要时好找。**更长的说明书不自动等于更好的说明书。**
2. **给工具**：文件和仓库、终端、API、数据库、浏览器、测试工具、构建系统。交互通过 tool call 完成：agent 决定用哪个工具，harness 执行，结果作为新上下文返回给模型。agent 框架/SDK 提供定义 agent、连接工具、管理环境交互的构件，可构成更大 harness 的一部分。
3. **提供对的环境**：光有工具不够——正确的运行时、依赖、配置、项目结构、访问权限。生产 harness 还需要更强的隔离、访问控制、监控、明确的执行/修改边界。
4. **记录状态**：任务太大一次做不完：暂停、恢复、分阶段。harness 保存已完成、待办、失败、待检查项。哪怕一个简单的进度文件也有用。任务越大，连续性越重要。
5. **提供反馈**（最重要的部分之一）：单元测试、集成测试、端到端测试、linter、类型检查、静态分析、构建结果。形成反馈环：**Reason → Act → Observe → Verify → Repeat**。反馈越有用越及时，agent 越少靠猜。
6. （语境中另述）context engineering 与 harness engineering 的关系：给 coding agent 搭用户 harness 是 context engineering 的一种具体形态。
