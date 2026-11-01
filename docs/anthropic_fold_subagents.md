# Anthropic：别再堆子代理了——何时把 Subagent 折回主 Agent Loop

出处：https://mp.weixin.qq.com/s/1zvpMqjfhsxTMFV4jarIxA （AnthroTech AI 研读版）
原文：https://claude.com/resources/articles/building-agents-when-to-fold-subagents-into-your-main-agent-loop

## 核心观点

随着模型能力提升（Opus 4.5 起，单循环能处理的工作比多数团队预期的多），
应重新审视模型 / Agent / Tools / Harness 的职责划分。Anthropic Applied AI
在企业部署对比中观察到：把路由器、规划器和领域 Subagent **折回主 Agent
Loop**，配合渐进式披露的 Skills 和 Tools，任务质量优于「超长 Prompt 单 Agent」
和「多 Subagent」两类方案，成本和延迟也更低。但**不是取消 Subagent**——
是减少没必要的 Subagent。

## 企业级 Multi-Agent 的五类问题

1. **上下文交接失真**（Telephone Game）：多次传递，保真度逐棒下降；
2. **Subagent sprawl**：各团队各建 Agent + 中央编排器，功能重叠、无人负责，
   架构变成组织架构的复制品（康威定律）；
3. **Frankenprompt**：多团队往同一份系统提示词里加规则，指令冲突、风格不一；
4. **评估局部化**：各团队优化各自的子任务，局部指标好 ≠ 端到端好；
5. **延迟翻倍**：某客户实测单 Agent Loop 速度约为多 Agent Harness 的两倍。

## 核心技术：Progressive Disclosure（渐进式披露）

先给模型简短的能力描述，需要时才加载详细指令/工具定义。三载体分工：

| 载体 | 承载内容 | 何时用 |
|---|---|---|
| Skills | 工作流程、规则、判断方法 | 模型需遵循专业工作方式时 |
| Tools | 操作、实时数据、结构化结果 | 模型需执行动作或访问系统时 |
| Knowledge Base | 政策、指标定义、产品规格 | 模型需查阅事实时 |

- 工具按**用户意图**组织，不是每个底层 API 一个工具；
- 工具定义超过 **30~50 个**时选择性能下降（工程经验值）→ Tool Search / 延迟加载；
- 参考资料不要代码预检索拼进 Prompt，放知识库按需查；
- Claude Code 系统提示词借此缩减 80%+（个案数据，非普适承诺）。

## Single Agent Loop 的四个特征

1. **规划与执行同一模型**：计划写在上下文里，随执行结果调整；
2. **工具调用可并行**：一轮一批，Harness 执行后原始结果回同一模型；
3. **循环仍有硬限制**：轮次预算、成本预算、写入控制归 Harness；可接验证器；
4. **职责再划分**：工作流进 Skills，硬约束（验证/审批）进 Tools，
   便宜且必做的步骤（实体解析）仍用代码，工具内部的机械工作也归代码。
   ——不是模型取代代码，是重新划分。

## Subagent 仍然适用的场景（别过度合并）

1. **并行独立任务**：各任务需新上下文，结束后上下文可丢弃（独立研究、黑盒验证）；
2. **主上下文膨胀到可测量的性能下降**（不是看起来长就拆）；
3. **工具选择困难或指令冲突**；外加黑盒验证与模块化权限管理。

## 迁移四步（每步都可对照检查）

1. **先建端到端评估集**：中央共享 + 各团队小型 Skill 评估集两层；
   10 个精选 Golden Examples 就够起步；还在用 Orchestrator 就跟踪路由准确率；
   难重放的环境记录事件时间戳供事后重放；
2. **优先合并高度耦合的 Subagent**（总被连着调、共享数据），
   依据是共享上下文而非业务名/部门名；
3. **先把 Subagent 当 Tool 调用**：不重写就能建新执行模式、攒基准数据；
   只有 Subagent 需要直接对用户说话时才做完整对话交接；
4. **一次只合并一个**：提示词整理成 Skill、API 转 MCP Tools、
   **对比迁移前后**——不只看平均得分，要检查哪些任务从过变挂；
   每个任务跑多次（文中失败案例只在 2 次中挂 1 次）。

**失败案例**：客服 Agent 合并后延迟降了，但主 Agent 查到订单后自己答了，
没问配送专员，漏了交付日期——减少 Agent 数可能破坏业务规则。

## 多团队共建的分工

- **MCP Tools 按意图组织 + 权限内聚**：每个工具自查调用者权限，不足返回
  明确拒绝——一个 Loop 服务不同权限用户，不必为权限差异拆 Agent；
- **Skills 团队自治**：各团队维护自己的 Skill，指定负责人、彼此区分、
  配套评估；管理工具 /skill-creator（建/审/精简/重组）与
  /skill-doctor（成本与实际使用频率体检，清理不再需要的）；
- **Knowledge Base 按需检索**，不做常驻上下文。

## 迁移工具（原文列出，以官方文档为准）

/claude-api build-eval（建评估集）、prompt-audit（审旧模型遗留指令）、
hillclimb（基于评估集迭代对比）、cost-optimize（成本逐项分析）。

## 总结

先测量 → 一次只改一个因素 → 保留经验证有效的方案。
不要因为架构成了习惯就停止重新评估它——这与本项目 TopologyAdvisor
「默认立场是不拆」的立场互为印证：拆与不拆都是选择题，评估是唯一裁判。

## 与本项目的对照（落地依据）

| 文章能力 | 项目现状 | 缺口 |
|---|---|---|
| Skills 渐进披露 | harness.skills（按需加载+热刷新+事件总线） | ✅ 已有；缺**库体检**（skill-doctor）→ 补 `SkillDoctorDemo` |
| Tools 按意图组织 + 工具搜索 | harness.routing（候选先收窄）+ toolbase | ✅ 已有 |
| Knowledge Base 按需检索 | harness.memory / EpisodicMemory | ✅ 已有 |
| 端到端评估集 + 合并前后对照 | langgraph4j/eval（EvalHarness/Pass@k/AdversarialProbe） | 缺「同一任务 Subagent 版 vs 折叠版 A/B + 从过变挂显形」→ 补 `FoldEvalDemo` |
| 默认不拆的立场 | TopologyAdvisor（写密集前置劝退、默认单 Agent） | ✅ 立场一致，本文是上游方法论 |
| 轮次/成本预算归 Harness | Budget / 各 demo | ✅ 已有 |
| 工具权限内聚 | ToolGate（渠道准入、fail-closed） | ✅ 已有 |
