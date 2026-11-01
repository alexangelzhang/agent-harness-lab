# 《AI Agents: The Definitive Guide》12 章对标

来源：知乎逐章精读（2026-10-28）：
https://zhuanlan.zhihu.com/p/2098720944680333653
原书：牛津大学自主 AI 工作流课程团队《AI Agents: The Definitive
Guide》（671 页，12 章，四大阶段：控制流编排 → 工具契约与沙箱 →
落地与评估 → 记忆/成本/威胁建模）。

## 逐章对账

| 章 | 核心主张 | 本项目对应 | 状态 |
|------|-----------|-----------|------|
| Ch1 四大要素 + FSM 工具调用管理（IDLE→PLANNING→TOOL_EXEC→OBSERVATION→终止） | langgraph4j 状态图版 = 显式状态机（节点=状态、条件边=转移边界）；ToolGate 是工具前置门禁 | ✅ |
| Ch2 ReAct / Plan-and-Solve / CoT vs ToT | 九大模式含 react / plan / lats（树搜索回溯剪枝）/ storm 等；图版+手写版双实现 | ✅ |
| Ch3 轨迹展开 + 学习闭环（critic 反哺 actor） | TrajectoryCollector 轨迹捕获；agentloop 反馈回流；RuleDistiller 离线策略优化（双层纠错的离线层） | ✅ |
| Ch4 复合模型架构（小模型判别/分类/拦截，大模型规划） | `harness.routing`：ModelRouter 按任务信号分 FAST/BALANCED/FRONTIER，CandidateFilter 先收窄候选——路由思想已有，「判别式模型专管敏感词拦截/语义分类」这层没显式分 | 🟡 |
| Ch5 显式控制点 + 强类型契约 + 双层纠错 | SchemaTool（参数契约进提示词）；ToolGate/sandbox 策略 = 运行时拦截；RuleDistiller = 离线策略优化。双层齐了 | ✅ |
| Ch6 PoLP + 分层沙箱 + **路径验证流水线**（规范化→白名单前缀→运行时 inode 校验，防目录穿透） | `sandbox.PathGuard`：相对路径对工作根解析 + 规范化剥障眼法 → 白名单前缀 → 运行时存在性校验（inode/TOCTOU 挂点），fail-closed 每层留痕 | ✅ |
| Ch7 渐进暴露 / HITL / 热更 + **迭代诊断流**（失败会话录制重放 + 归因：模型退化/Prompt 漂移/下游抖动） | HITL/热更/录制同上；归因树：`diagnostics.FailureDiagnostician`——ENV_FAIL 步→下游抖动、同任务历史成功→Prompt 漂移、同一改法理由 ≥3 次→模型退化，级联可同命中，报告带修法建议 | ✅ |
| Ch8 度量体系（Pass@k/步数/轨迹冗余/动作有效性）+ 对抗测试作 CI 门禁 | `eval` 包 Pass@k/步数统计/对抗探针 + MemoryBench 五维 | ✅ |
| Ch9 专有 Benchmark 全生命周期 + repo→沙箱基准 | 维度评测有（eval/MemoryBench）；「拉真实仓库进沙箱、注入 issue、跑断言」的基准流水线是挂点，容器化过重不实现 | 🟡（挂点） |
| Ch10 三记忆类型 + checkpointed 回溯分支 | 分层记忆（ContextWindow/MemoryStore/KnowledgeBase）；`memory.EpisodicMemory` 从失败档案蒸馏教训进记忆库（幂等），检索时教训跟着进场——情境记忆补全 | ✅ |
| Ch11 FlashAttention/Prefix Caching/投机采样 | GPU 算子层与 Java 工程无关；项目层对应物是 context 包（响应缓存/逐步裁剪）+ Budget（成本水位线） | ✅（对应物） |
| Ch12 分层威胁模型：间接提示注入 / 记忆投毒 / 资源耗尽（Denial of Wallet） | DoW = Budget；记忆投毒 = 写入闸门；间接提示注入：`security.InjectionGuard` 指令模式扫描 + 命中句整句脱敏 + 不可信标记（可展示不可执行），与 credentials 占位符互补（管输入面） | ✅ |

## 缺口清单（建议）

1. **`sandbox.PathGuard` 路径验证流水线**（Ch6）：规范化解析
   （resolve `..`/符号链接）→ 白名单前缀匹配 → 运行时校验（demo
   用 exists 代 inode）——fail-closed，`../../etc/passwd` 在进文件
   系统之前就被拦，审计留痕。
2. **`harness.diagnostics` 归因树**（Ch7）：失败会话的录制重放已有
   底账（RunRecord），补归因——同一次失败，按规则拆成模型退化
   （轨迹里反复同一错法）/ 下游抖动（EnvException 记录）/ Prompt
   漂移（同 prompt 历史通过、现在失败）， diagnostician 出归因
   报告，告诉人该修哪层。
3. **情境记忆接通**（Ch10）：从 RunRecord 蒸馏「经验教训」进
   MemoryStore——失败轨迹 → 「这个做法别再试」的 episodic 记忆，
   检索时能召回；记忆的第四种内容来源，和写入闸门共用置信治理。
4. **`InjectionGuard` 间接提示注入防线**（Ch12）：工具返回内容
   （网页/文件/邮件）进上下文前做指令模式扫描（「忽略之前指令/
   系统提示已更新」类），命中即标记为不可信数据——可以展示、不可
   执行，fail-closed 的确定性防线，正好和凭证占位符互补（一个防
   输出、一个防输入）。

四个缺口分别落在沙箱安全、可观测诊断、记忆、威胁建模四个面上，
都是「生产级」补法；Ch4/Ch9 维持挂点级不实现。
demo 计划：`mvn exec:java@pathguard` / `@diagnose` / `@episodic` /
`@injection`（或合一为 `@threat` 包演示，看实现时怎么顺）。

## 缺口落地结果（2026-10-31，全部闭环）

1. **`sandbox.PathGuard`**：相对路径先对工作根解析再规范化（障眼法
   `work/../secret` 与 `secret` 同物，字符串匹配拦不住、规范化后拦得住）
   → 白名单前缀匹配 → 运行时存在性校验（inode/TOCTOU 挂点留在这条）。
   fail-closed，每层拒绝带层与理由可复盘。调试抓到一个真 bug：相对
   路径必须对 agent 的工作根解析而不是 JVM cwd，否则「绕一圈回根内」
   的合法路径会被误杀。
   demo（`@pathguard`）：五种请求——正常放行、向上穿越拒、经典
   `../../etc/passwd` 拒、白名单内不存在拒、绕回根内放行（2 放 3 拦）。
2. **`diagnostics.FailureDiagnostician`**：ENV_FAIL 步或 ENV_ABORTED →
   下游抖动（修容错层）；同任务历史成功现在失败 → Prompt 漂移（先
   diff 提示词，别急着换模型）；同一改法理由 ≥3 次 → 模型退化（换模型/
   补示例/拆任务）。级联可同时命中，报告把病因连同证据和建议摆出来。
   demo（`@diagnose`）：三个病历各归各因。
3. **`memory.EpisodicMemory`**：失败档案的主导重试理由蒸馏成「下次先
   处理这个」的教训，置信 0.85 直接 ACTIVE（轨迹验证过，比模型推断
   可信），幂等可反复跑；进库后走正常召回路径，同类任务开场注入——
   档案从「等审计的底账」变成「会被想起的经验」。
   demo（`@episodic`）：两条失败档案 → 1 条教训入库 → 再蒸馏 0 新增
   → 同类任务召回时教训进场。
4. **`security.InjectionGuard`**：五条指令模式（忽略先前指令/伪系统
   提示/越权数据请求/角色劫持/指令式英文）扫描工具返回内容；命中句
   整句脱敏为〔已拦截的注入指令〕，全文包「只当数据引用」标记——
   内容可展示、指令不可执行；干净内容原样通过不浪费 token。
   与 credentials 占位符互补：占位符管输出面（密钥不外流），这里
   管输入面（指令不潜入）。
   demo（`@injection`）：藏注入的邮件命中 2 条模式（实测），脱敏后
   数据句全留、注入句全换。

## 结论

全书 12 章对账闭环：7 章原生 ✅、Ch11 有项目层对应物、Ch4/Ch9 挂点
级、四个硬缺口（Ch6 路径流水线 / Ch7 归因 / Ch10 情境记忆 / Ch12 间接
注入）全部落地，demo `@pathguard` / `@diagnose` / `@episodic` /
`@injection` 离线可跑。生产级智能体书里的「状态漂移、工具幻觉、
级联故障、成本失控、安全越权」五个工程痛点，项目里现在都有对应防线。
