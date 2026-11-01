# 从零搭建编码 Agent Harness（Vercel Academy 教程中译导览）

来源：https://mp.weixin.qq.com/s/IJiiXuiQpR1DxuBxvomgmQ（公众号「开发者生存指南」/ SleepyZone，
2026-10-28，浙江）；原作 Vercel Academy《Build Your Own AI Coding Agent Harness》
https://vercel.com/academy/build-ai-agent-harness 。

## 是什么

一套围绕 **TeensyCode** 项目（TypeScript）的动手教程：从最小的「能聊天的程序」出发，
每加入一个部件都对应前面暴露的问题——只能聊天就加工具；工具多了就完善描述；
能执行命令了就引入权限与审批；任务变长就处理输出上限、历史裁剪和缓存；
探索和执行互相干扰就拆角色起子 Agent……最终拼出一个编码 Agent 的完整 Harness。

原 38 节合并为 10 章，十章结构即一份「编码 Agent 从零到一」的部件清单：

| 章 | 内容 | 教程解决的问题 |
|----|------|---------------|
| 1 | 从聊天机器人到编码 Agent | 工具循环、read、grep、bash 与执行拦截 |
| 2 | 工具描述与审批机制 | 描述契约、工厂模式、三种审批配置 |
| 3 | 系统提示词与项目约定 | 行为策略、动态提示词、验证要求、AGENTS.md |
| 4 | 沙箱接口与执行后端 | 统一接口、本地与内存实现、云端取舍、生命周期钩子 |
| 5 | 上下文管理与成本控制 | 用量记录、逐步裁剪、输出上限、缓存 |
| 6 | 子 Agent 委派与任务路由 | 委派时机、探索者、执行者、task 路由 |
| 7 | 沙箱生命周期与恢复 | 状态机、快照恢复、持久化工作流、故障处理 |
| 8 | 用户交互、任务规划与验证 | 结构化提问、策略拦截、Todo、搜索策略、验证契约 |
| 9 | 命令行与 Web 交互界面 | CLI 参数、退出清理、流式渲染、Web 架构 |
| 10 | 技能、自定义工具与扩展机制 | 按需技能、工具注册、事件总线 |

Harness 定义（译者版）：围绕模型搭建的那套系统——提供工具、组织项目规则、
管理执行环境和上下文，负责审批、任务委派和用户交互。

## 与本项目对标（2026-10-30 现状）

按十章清单逐项对账（✅ 已有 / 🟡 部分 / ❌ 缺失）：

| 章 | 本项目对应 | 状态 |
|----|-----------|------|
| 1 工具循环 | `com.example.agent` ReAct 手写版、langgraph4j 图版、agentloop 原语 | ✅ |
| 2 工具描述与审批 | `ToolRegistry` 提示词清单、`harness.ToolGate` 权限分级 + `ApprovalPolicy` 三种审批终态 | ✅ |
| 3 系统提示词与项目约定 | 根目录 `AGENTS.md`（本项目自己的）、模式提示词模板即核心 | ✅ |
| 4 沙箱接口与执行后端 | `harness.sandbox`：`Sandbox` 接口 + 内存/本地实现 + 生命周期钩子 | ✅ |
| 5 上下文管理与成本控制 | `harness.Budget` + `harness.context`：ContextWindow 逐步裁剪/摘要压缩/输出上限/用量记账、CachingLlm 响应缓存（TTL + LRU 容量 + 谓词失效）、EnvException 失败分流 | ✅ |
| 6 子 Agent 委派 | 静态 Multi-Agent 图 + `workflow` 动态工作流六模式 | ✅ |
| 7 沙箱生命周期与恢复 | langgraph4j `MemorySaver` checkpoint + interrupt/resume；磁盘快照恢复 = `harness.sandbox` 内存/本地两版 + `LocalSandbox` 整树快照回滚 | ✅ |
| 8 用户交互、规划与验证 | 规划 = Plan&Solve/REWOO + `harness.planning`（TodoList 任务清单、SearchPlanner 搜索策略）；验证契约 = `eval` + judge 双职责；`harness.interaction`：AskPolicy 策略拦截 + StructuredQuestion 结构化提问 | ✅ |
| 9 CLI / Web 交互界面 | `harness.interaction`：CliRenderer 流式渲染（块到块上屏、close 收尾复位）、StreamRenderer 接口可换 Web 皮、`WebDashboard` 事件面板（HTTP 轮询页 + 环形缓冲） | ✅（CLI 参数解析未单列，loopbench 已有 args 分片实践） |
| 10 技能与扩展机制 | `harness.skills`：Skill（触发信号+工具组+用法说明）、SkillRegistry 按需加载、EventBus 事件总线（订阅者解耦） | ✅ 已落地（10-31） |

## 结论

十章全部闭环，每一项都有对应实现并过了编译与离线剧本验证（其中大部分过了真实模型验证）。
云端执行后端是唯一没有代码落地的项——但它不是缺口而是挂点：`Sandbox` 接口本就是为换实现留的，
把内存/本地版换成云端后端（Firecracker 之类）只是新增一个实现类的事。
