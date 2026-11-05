# OmO（oh-my-openagent）研读存档：编排体系与 Kibitzer 记忆侧车

出处：
- 项目仓库：https://github.com/code-yeongyu/oh-my-openagent （OmO 5.0，Sisyphus Labs，跑在作者 fork 的 pi「senpi」引擎上）
- 编排指南原文：https://raw.githubusercontent.com/code-yeongyu/oh-my-openagent/refs/heads/dev/docs/guide/orchestration.md
- 官方文档站：https://omo.dev/en/docs
- Manifesto：https://omo.dev/en/manifesto （2026-09-14 版，旧版 2026-01 存档于 /manifesto/2026-01）

存档日期：2026-11-05。以下为全文浓缩，非逐字翻译。

## 一、OmO 是什么

一个多模型 agent 编排 harness：把一个编码会话变成一支分工明确的开发团队。三个发行版共用同一套内核——OpenCode 插件（Ultimate）、Codex 插件（Light）、独立命令行 OmO Native（`omo`，内置 senpi 引擎）。核心立场是「不绑定任何一家模型厂商」，按任务类型路由到不同模型。

## 二、编排体系（Orchestration）

**一个编排者**：主 agent 就是用户会话本身，跑在会话模型上，永不让出会话。它只做规划、分派、裁决、证据归拢；所有实现/测试/QA 都通过 `task` 工具委派出去——执行时主 agent 自己一行产品代码都不写。

**task 工具两种分派**：
- `task(category=...)` → category worker：全新 worker 会话，模型和技能由 category 配置决定。内置 category：`architect`（设计咨询/跨模块调试）、`artistry`（创意）、`deep`（硬调试/重研究）、`quick`（机械单文件）、`ultrabrain`（单个硬逻辑问题）、`unspecified-high/low`（兜底）、`visual-engineering`（前端）、`writing`（文档）。可拆的活拆成一把 quick 并发，整体性硬活整包给 deep/ultrabrain。
- `task(subagent_type=...)` → 具名 agent：四个 curated 只读专家（`explore` 代码搜索、`librarian` 文档/OSS 检索、`plan-consultant` 规划缺口分析、`plan-reviewer` 计划评审，后两个 plan-gated，只有显式走 ulw-plan 流程才放行）+ 三个 ulw-loop 评审员（code-reviewer / qa-executor / gate-reviewer，可写报告产物）。

**规划与执行分离**：
- `/ulw-plan`：主 agent 变身访谈式规划师，先用 explore/librarian 并行调研，再按意图清晰度决定问几个问题（CLEAR 只问 Owner-decision，UNCLEAR 直接采用最佳实践默认值），产出 `.omo/plans/<slug>.md`。写计划前先让 plan-consultant 做 gap 分析，写完后 plan-reviewer 做多轮高精度评审（只拒有实据的 blocker）。
- `/ulw-execute`：同会话执行已批准计划。`.omo/boulder.json` 记录多工作注册表，每个 checkbox 过五道门（重读计划、自动验证、Manual-QA 产物、对抗 QA、清理回执），证据落 `.omo/ulw-execute/ledger.jsonl`。worker 的「做完了」要经独立评审员核实才翻 checkbox。会话中断后重跑即续跑（最多自动续 8 轮）。
- `mass-ulw`：任务间有真实依赖序时定义 DAG，走 workflow 工具分 phase 跑。
- Team Mode：多条车道摸同一模块且需要中途互通时用，共享 mailbox + 任务清单，默认关。

**其余机制**：IntentGate（正则检测 `ulw`/`team mode`/`hyperplan` 等关键词注入对应模式 prompt，不做语义分类）；hashline_edit（LINE#ID 哈希锚定编辑，opt-in）；纪律层（todo enforcer 把摸鱼 agent 拽回来、comment checker 铲 AI 腔注释、Goal 持会话级目标+空闲时再注入续跑提示+完成审计）；category 和 curated agent 各自带 provider fallback 链，首选厂商挂了自动降级；按模型家族定制 prompt 预设。

## 三、Manifesto 浓缩

《Ultrawork Manifesto》核心主张：工作的价值来自人的智慧与经验，其余只是手的移动；工具的职责是把「优质 token + 算力」直接编译成好作品，harness 选型、模型脾气、技能配置都不该是用户的问题；agent 没有人活出来的经验，所以工具要把过来人的智慧「活着」传递给下一个人。一句话口号：别想太多，just ulw ulw。

## 四、Kibitzer 记忆侧车（本次落地对象）

原文段落（orchestration.md「Kibitzer memory nudges」一节）：

> Kibitzer is the memory component's read-only recall judge: one resident sidecar session per main agent session, started lazily and kept for the life of that session. Every prompt, tool call and tool result of the main session is fed to it as a bounded, redacted event (tool arguments, result heads, assistant text and prompts are all capped; secrets are masked before anything is stored). It only spends a model turn when a prompt or tool call surfaces a stored memory it has not judged yet, and at most two such wakes run at once per machine. Inside a wake it can `read` and `grep` the workspace, page the parent transcript with `session_entries`, and `search`/`read` memory - eight tool calls per wake, read-only, nothing that writes memory or files - and its only output is `nudge`, which becomes a notice reading `recalled memory: <hint>` on the main agent's side. When its own context fills up it reseeds itself with what it has already delivered or rejected; when its model fails it backs off and comes back later. It never edits anything and never wakes an idle session; it just reminds the main agent of what it already knows. `/search` is the manual recall surface, and `memory.recall.enabled: false` turns Kibitzer off.

要点拆解：

1. **每主会话一个常驻 sidecar**，懒启动，与会话同寿命；
2. **事件流有界且脱敏**：主会话的 prompt / 工具调用 / 工具结果都以截断+遮罩后的形式喂给它，secret 在落盘前就遮掉；
3. **按闹铃醒来，不是每轮都跑**：只有当 prompt 或工具调用触到一条「它还没判定过」的已存记忆时才花一次模型调用；单机最多两个 wake 并发；
4. **wake 内自证**：可 read/grep 工作区、翻父会话转录（session_entries）、search/read 记忆库——每次 wake 上限 8 次工具调用，全部只读；
5. **唯一输出是 nudge**：变成主 agent 侧的一条 `recalled memory: <hint>` 提示；不能写记忆、不能写文件、不改任何东西；
6. **自我续命**：自己的上下文满了就用「已投递/已拒绝」的记录重播种；模型调用失败则退避重试；
7. **从不主动吵醒空闲会话**，只提醒主 agent「你其实已经知道」；
8. `/search` 是手动回忆入口，`memory.recall.enabled: false` 可整体关闭。

设计哲学一句话：记忆的写入有闸门（别处管），记忆的**想起**也应该有法官——不是每条相关记忆都值得打断主 agent，sidecar 先把「值不值得提醒」判一遍，主 agent 只看到判完通过的。

## 五、对我们的启发

- 我们的记忆系统已有「触发 → 三路召回 → 融合 → 写回有闸门」，缺的是 Kibitzer 这个位置：**召回之后、注入之前的常驻裁判**。现在的召回是同步管线内一步到位，Kibitzer 把它拆成异步旁观席——主 loop 不等待，sidecar 判完才 nudge。
- 「bounded + redacted event」与我们的 Sensor 反馈层思路相通：治理层看到的一切都该先过脱敏和截断。
- 「每 wake 8 次只读工具调用 + 零写入」是一个可断言的强不变量，适合剧本场景直接验证。
- 「上下文满了用已投递/已拒绝重播种」是 sidecar 自己的压缩策略，比通用摘要便宜。

## 六、容错层机制（2026-11-05 补读，落地对象）

出处：packages/hashline-core/（AGENTS.md + src/hash-computation.ts + src/edit-operations.ts）与 packages/omo-opencode/src/hooks/tool-pair-validator/tool-result-repair.ts。

**Hashline 哈希锚定编辑**：
- 每行渲染为 `LINE#HASH|content`。哈希算法：内容去 `\r`、去行尾空白后做 xxhash32，取模 256 查一张 256 项字典出两位码；含字母/数字的行种子为 0，**全空白行用行号做种子**（不同位置的空行锚不同，replace 空行不撞锚）。另有 legacy 变体（剥全部空白）兼容旧锚。
- 编辑操作三种：`replace`（pos 或 pos+end 区间）、`append`（带 pos 是插到该行后，不带是文件尾）、`prepend`（带 pos 是插到该行前，不带是文件头）。未知操作直接拒。
- 应用管线：去重 → 按行号**从尾到头**排序（行号在应用中不失效）→ **整批验锚**（行号越界或哈希对不上当前内容 = 视图过期，抛 HashlineMismatchError，一行不改）→ 区间重叠检出 → 应用并统计 noop。
- 配套：autocorrect（剥模型复述时带出的前缀/缩进/回声）、统一 diff 生成、200 行/64KB 分块渲染。

**tool_call / tool 结果修复**：
- tool-pair-validator 的 tool-result-repair：扫消息里「有调用没结果」的悬空工具部分，补成终态 error（`[Tool execution was interrupted before it produced output]`），让转录重新配对——修复的是**会话状态**，不是 JSON 语法。
- 我们落地的「畸形 tool_call 修复」是这条思路在参数面的推广：模型的参数 JSON 带伤（围栏/散文/尾逗号/单引号/类型错）时先走确定性修复管线再判死活，每步记账，修不好 fail-closed 不靠猜。

**对我们的启发**：模型输出是「永远可能带伤的输入」，容错层的位置在解析与执行之间；修复必须确定性 + 可审计，任何「让模型自己再猜一次」的修法都是把伤口往后挪。

## 七、纪律层机制（2026-11-05 补读，落地对象）

出处：omo.dev 文档站 Docs 总页「Why It's Better」一节与编排指南的 ulw-execute 续跑段。

- **Todo enforcer**：agent 空转摸鱼会被拽回任务清单。清单本身只是记录，纪律在于「盯住每一轮」——整轮无进展而清单未勾完，就注入拽回提示。
- **Goal（opt-in，`goal.enabled` 与 `goal.auto_start` 默认都关）**：会话级持续目标；agent 空转/想停时再注入续跑提示，直到一次**完成审计**确认干完才放行。「完成」不由 agent 自己宣布。
- **续跑有上限**：ulw-execute 的 idle 续跑最多 8 次——纪律层不等于无限唠叨，拽不动就该转人工，不是把提示词越写越凶。
- **Comment checker**：自动剥 AI 腔注释（本次未落地，属文案纪律而非执行纪律）。

**对我们的启发**：我们原有 TodoList 解决了「记」，这次补的是「执行」；Goal 与 Todo 的分工值得保留——清单全勾 ≠ 目标达成（事都办了，结果不对），所以收工需要对着目标审证据，而不是数勾。
