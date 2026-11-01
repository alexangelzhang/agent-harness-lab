# Harness Engineering（Fowler / ThoughtWorks）对标

来源：
- Birgitta Böckeler《Harness engineering for coding agent users》（martinfowler.com，
  2026-04-02，ThoughtWorks Distinguished Engineer）：
  https://martinfowler.com/articles/harness-engineering.html
- Birgitta Böckeler《What does an AI agent harness actually do?》（ThoughtWorks
  Insights）：https://www.thoughtworks.com/insights/blog/machine-learning-and-ai/ai-agent-harness

两文互补：ThoughtWorks 那篇回答「harness 做哪六件事」（功能视角），Fowler
这篇回答「harness 怎么工程化」（控制论视角：前馈/反馈、计算/推断、调节类别、
转向循环）。核心等式：**Agent = Model + Harness**；更好的模型不自动等于更好
的 agent，外围环境同样重要。

## ThoughtWorks 六件事对账

| 六件事 | 本项目对应 | 状态 |
|------|-----------|------|
| 1. 给指令 | AGENTS.md + `toolbase.SchemaTool`（参数契约进提示词）+ Skills | ✅ |
| 2. 给工具 | `toolbase`：GovernedTool 治理壳 / ToolResolver fail-closed / ChannelAcl 渠道准入 | ✅ |
| 3. 提供对的环境 | `harness.sandbox`（内存/本地双后端 + 快照恢复 + 生命周期钩子）+ `credentials`（密钥不进上下文） | ✅ |
| 4. 记录状态 | FileSystemSaver 磁盘 checkpoint + TodoList + RunRecord/RunStore 运行档案 | ✅ |
| 5. 提供反馈 | `harness.sensor`：Sensor 接口（计算/推断分野）+ Finding（修正指引）+ SensorSuite（quality left 分层）+ `SensorVerifier` 接进 agentloop 回流闭环 | ✅ |
| 6. context engineering | `harness.context`（逐步裁剪 + 输出上限 + 响应缓存） | ✅ |

## Fowler 概念对账

| 文章要点 | 本项目对应 | 状态 |
|------|-----------|------|
| Guides（前馈）/ Sensors（反馈）都要 | 前馈：AGENTS.md/Skills/ToolGate/Budget/写入闸门；反馈：`harness.sensor` 整层（与 RuleDistiller 台账共同构成转向循环） | ✅ |
| 计算型 vs 推断型 sensor | `Sensor.Kind` 标在接口上；计算型四件套毫秒级每次跑（占位符/行数红线/重复/适应度），推断型 `LlmReviewSensor` 显式标注贵、抽样跑 | ✅ |
| 「自带修正指引的信号」= 正向 prompt injection | `Finding` = 规则+严重度+位置+问题+**修正指引**，`toPromptBlock()` 渲染成回流块 | ✅ |
| 转向循环（steering loop）：问题重复出现 → 改进 guides/sensors | `SensorLedger`：规则触发计数，反复 ≥2 次蒸馏成 guides 草稿（复用 RuleDistiller 提案制）；「sensor 从不触发」单列 | ✅ |
| quality left：检查按成本/速度/关键度沿时间线分布 | `SensorSuite` PRE_COMMIT 每次跑 / INTEGRATION 抽样跑（默认每 5 次 1 次） | ✅ |
| 三类调节：可维护性 / 架构适应度（fitness function）/ 行为 | `FitnessSensor` 挂点：性能红线/可观测性约定/API 契约前馈为可执行检查 | ✅ |
| Harness 模板：拓扑 + guides/sensors 一捆 | `harness.template`：`HarnessTemplate`（约定 + sensor 一捆）×三拓扑、`TetheredWorkspace` 实例化带来源戳、`TemplateRegistry.driftCheck` 漂移检查 | ✅ |
| 人的角色：隐性 harness，输入引导到最重要处 | ApprovalPolicy / PendingAction 审批载荷 / HITL 中断 / 写入闸门 confirm | ✅ |
| 开放问题：guides 与 sensors 一致性、信号冲突取舍 | `SensorSuite` guides 撞车标注：前馈已要求、反馈仍抓到时原样标注，不替模型裁决 | ✅ |

## 缺口清单（2026-10-31 已全部落地，留档）

新包 `harness.sensor`——Feedback 层立起来，让「反馈」和「前馈」对称：

1. **`Sensor` 接口 + `Finding`**：一条发现 = 规则名 + 位置 + 问题 + **修正指引**
   （Fowler 说的「自带修正指引的信号」，正向 prompt injection）。反馈不再
   是 PASS/FAIL 散文。
2. **计算型 sensor 示例**（毫秒级、确定性、每次变更都跑得起）：占位符检查
   （TODO/FIXME 残留）、单文件改动行数红线（把「对 300 行函数的审美厌恶」
   外部化）、重复模式粗检。
3. **推断型 sensor 示例**：包一层 `LlmClient` 做语义判断（语义重复/过度设计），
   显式标注贵 + 不确定，只抽样跑——两类 sensor 的分界本身就是文章论点。
4. **quality left 分层**：`SensorSuite` 按 tier 编排——PRE_COMMIT 层每次跑
   （计算型），INTEGRATION 层抽样跑（推断型）；同一份 Finding 格式。
5. **接进 agentloop**：Verifier 之后挂 SensorSuite，findings 以结构化块回流
   下一轮 prompt（行动→验收→感知→重试，闭环多一环）。
6. **转向闭环接通**：sensor findings 落痕（哪条规则、触发几次），重复出现的
   finding 蒸馏成 guides 草稿（复用 RuleDistiller 的提案制）；「从未触发」
   计数一起进报告——回答 Fowler 的开放问题之一。
7. **fitness function 挂点**：`FitnessSensor`——把「API 契约 / 性能红线 /
   可观测性约定」写成可执行检查（示例：响应时间契约、日志字段约定），
   架构适应度从口号变检查。
8. **冲突裁决**：guides 规则与 sensor finding 同时存在时，报告里标注
   「AGENTS.md 已要求 X，sensor 仍发现 Y」——一致性感知，不替模型做决定。

### 落地结果

- `Sensor` 接口的 kind 标在类型上（COMPUTATIONAL / INFERENTIAL），
  `Finding` 带修正指引与 guides 撞车标注；计算型示例四件套（占位符 /
  行数红线 / 重复模式 / FitnessSensor 架构适应度），推断型
  `LlmReviewSensor`（语义重复/过度设计/暴力修复）。
- `SensorSuite` 两层时机：PRE_COMMIT 每次跑、INTEGRATION 按抽样间隔跑；
  `SensorLedger` 转向循环原料——反复触发 ≥2 次蒸馏成规则草稿（复用
  RuleDistiller 提案制），从未触发的 sensor 单列（质量好还是检测失灵，
  台账不替人回答）。
- `SensorVerifier`（agentloop 包）：装饰任意 Verifier 的零侵入接法——
  不达标才跑 sensor，发现以结构化块追加进回流理由，剧本验证 TODO 修正
  指引被下一轮消费、2 轮收工。
- demo `mvn exec:java@sensor`：四幕编排——计算型感知（含 guides 撞车
  标注）、推断型抽样（5 轮实跑 2 次）、回流闭环、转向循环（3 次台账
  → 2 条草稿 + 从未触发清单）。
- 真实闭环 `mvn exec:java@sensorreal -Dexec.args="[1-3]"`（需
  KIMI_API_KEY）：场景一真实 Kimi 按任务要求写下 TODO，sensor 拦下、
  修正指引回流、二稿改「待补充」收工——拦截与改正都是模型真实发生的；
  场景二推断型真实评审抓到语义重复 + 两处过度设计；场景三真实产出
  逐轮攒台账，触发过谁、谁从没响一屏摆出。剧本证明机制对，真实闭环
  证明机制在真实输出下照旧对。
- **Harness 模板**（`harness.template`，Fowler 最后一个概念）：
  `HarnessTemplate` = 拓扑约定（guides 原料）+ sensor 清单，
  `instantiate()` 渲染出带来源戳（workspace@template@vN）的
  `TetheredWorkspace`（guides + sensor 套件配好撞车标注）；
  预置三种企业拓扑（api-service / event-processor / dashboard）；
  `TemplateRegistry.driftCheck` 把「实例化后与上游脱节」显形——
  上游升版 v2，工作区还拴 v1 时给出 re-instantiate 提示。
  demo（`mvn exec:java@template`）：同一份毛病代码三拓扑各拦各的
  （API 4 条 / 事件 3 条 / 看板 1 条）、实例化渲染、漂移检查、
  Ashby 量化（约定数 vs sensor 覆盖数）四幕。

## 结论

Fowler/ThoughtWorks 两文的对账项全部闭环（含 Harness 模板）：六件事的
「提供反馈」从零件变成层（Sensor 抽象 + 计算/推断分野 + quality left +
修正指引格式 + 转向循环台账 + fitness function 挂点 + guides/sensors
一致性感知），外加拓扑模板把「企业里少数几种拓扑」固化成
guides+sensors 一捆，漂移检查接住实例化后的脱节。
跑通：`mvn exec:java@sensor` / `@sensorreal`（需 KIMI_API_KEY）/
`@template`；反馈层与前馈层（guides/Skills/ToolGate/写入闸门）对称，
Agent = Model + Harness 的 Harness 半边补完。
