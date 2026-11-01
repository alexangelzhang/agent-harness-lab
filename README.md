# Agent 工程实验场（Java）：从九大设计模式到生产级 Harness

这个项目始于《Agent 的九种设计模式》的纯 Java 实现，后来一路长成了一个小型
「Agent 工程实验场」：同一套 `LlmClient` 抽象下，并排跑着**编排模式、四种运行
载体、一层生产级治理设施**，全部可离线剧本跑通、可接 Kimi 真实模型。

零第三方依赖（JDK 17+，langgraph4j 相关能力除外），Maven 构建。

## 四个层面

| 层面 | 包 | 内容 |
|------|-----|------|
| 编排模式 | `com.example.agent`（手写版）、`com.example.langgraph4j.patterns`（状态图版） | 九大模式，逐行对照 |
| 运行载体 | `agent` 手写 / `langgraph4j` 状态图 / `agentloop` 原语 loop / `workflow` 动态工作流 | 同一剧本可比选型（loopbench） |
| 治理层 | `com.example.harness` | Budget 预算、RunRecord 档案、ToolGate 工具网关、ApprovalPolicy 审批、RuleDistiller 规则蒸馏、RunReportDemo 运行报表、OuterLoopDemo 一键外循环、Sandbox 沙箱执行后端、ContextWindow/CachingLlm 上下文管理、AskPolicy/StructuredQuestion 交互、Skill/EventBus 技能扩展；记忆层（分层/检索/整理）、路由筛选、凭据保管、磁盘 checkpoint、PendingAction 审批载荷、CodeRunner 脚本批处理 |
| 动态工作流 | `com.example.workflow` | Claude Code「动态 harness」六模式的 Java 版 `agent()`/`parallel()`，预算与留痕已接治理层 |
| 多 Agent 拓扑 | `com.example.multiagent` | 四种组织形式各归其位（Workflow/Supervisor/Hierarchical/Swarm）+ 委派护栏 + 选型决策树，默认立场是不拆；生产深化：Supervisor 并行 fan-out（并发上限 + 故障隔离）、Swarm 整轮时间盒 |

文档地图：

- [LANGGRAPH4J.md](LANGGRAPH4J.md)——主文档：图版与手写版对照、真实模型踩坑、评估、治理层、动态工作流，每一节都有运行入口；
- [AGENT_DESIGN_PATTERNS_REPORT.md](AGENT_DESIGN_PATTERNS_REPORT.md)——第一阶段（九大模式手写版 + 状态图版）的总结报告，历史文献定位；
- [docs/](docs/)——演进过程中研读的文章存档（动态工作流、Harness 工程、上下文工程、
  Vercel Academy 编码 Agent Harness 教程对标等）。

## 模式总览（起点）

| # | 模式 | 核心结构 | 解决的问题 |
|---|------|---------|-----------|
| 1 | **ReAct** | Thought → Action → Observation 循环 | 最基础的「边想边做」，每步执行后保留观察（短期记忆） |
| 2 | **Plan & Solve** | Planner + Executor + Replanner | 先整体规划再执行；某步失败时重规划剩余步骤 |
| 3 | **REWOO** | Planner + Worker + Solver | 把 Observation 隐式嵌入下一步执行单元，规划一次、批量执行，省 LLM 调用 |
| 4 | **LLMCompiler** | Planner(DAG) + 并行调度器 + Joiner | 识别无依赖任务并行执行，降低端到端延迟 |
| 5 | **Basic Reflection** | Generator + Reflector | 「学生写作业、老师批改」式往复迭代，提升输出质量 |
| 6 | **Reflexion** | Actor + Evaluator + Self-Reflection | 引入外部依据校验；失败生成语言反思存入情景记忆并重试（口头强化学习） |
| 7 | **LATS** | Tree Search + ReAct + Plan&Solve + Reflection | 每步扩展多个候选分支、打分择优、死路反思回溯 |
| 8 | **Self-Discover** | Selector + Adaptor + Implementor | 对「任务本身如何推理」进行反思：自选推理模块、组装推理结构再执行 |
| 9 | **STORM** | 大纲生成 → 多视角调研 → 分章撰写 → 润色 | 从零生成维基百科式长文：先调研素材再成文 |

演进主线：

```
ReAct ── 加规划──▶ REWOO ── 加变量链──▶ Plan&Solve ── 加 DAG/并行──▶ LLMCompiler
  │
  └── 加反思──▶ Basic Reflection ── 加环境评估/记忆──▶ Reflexion ──▶ LATS（集大成）
                                                    └─▶ Self-Discover（小粒度反思 task 本身）
STORM = 规划(大纲) + 工具调研 + 写作 的内容生成特化
```

## 运行

仓库分两模块：`core/` 是库（`agent-core`，语义化版本，生产可用），`demo/` 是集成验证（52 个 demo，库的可执行规约）。命令从仓库根跑，`scripts/dev.sh <id>` 一条搞定（先装 parent 和 core 再 exec，改了 core 也不用手动 install）；等价形式 `mvn -q exec:java@<id> -pl demo`（首次或改 core 后先 `mvn -q install -N && mvn -q install -pl core`）。

```bash
mvn -q exec:java -pl demo     # 运行全部 9 个模式的演示（手写版，需已 install core）
scripts/dev.sh graph      # langgraph4j 状态图版：九个图版剧本演示
scripts/dev.sh loopbench    # 图版 vs 原语版 loop 同剧本对比
scripts/dev.sh archive      # 运行档案：四种载体（loop/状态图/动态工作流/多 Agent）同一张审计底账
scripts/dev.sh distill      # 外循环蒸馏：失败模式 → 规则草稿（剧本历史，含采纳检验）
scripts/dev.sh distillreal  # 外循环真实版：真实撞护栏 → 真实档案 → 蒸出规则（需 KIMI_API_KEY）
scripts/dev.sh workflow     # 动态工作流六模式（剧本）
scripts/dev.sh sandbox      # 沙箱执行后端：内存快照恢复 + 本地策略拦截
scripts/dev.sh context      # 上下文管理：逐步裁剪 + 输出上限 + 响应缓存
scripts/dev.sh interaction  # 用户交互：结构化提问 + 策略拦截 + 流式渲染
scripts/dev.sh skills       # 技能与事件总线：按需加载 + 订阅解耦
scripts/dev.sh memory       # 记忆层：自动注入 + 主动检索 + 异步整理
scripts/dev.sh routing      # 路由与筛选：候选先收窄，模型按任务挑
scripts/dev.sh credentials  # 凭据保管：密钥不进模型上下文，执行前夜注入
scripts/dev.sh checkpoint   # 磁盘 checkpoint：进程重启从盘上接着跑
scripts/dev.sh pending      # 待处理操作：系统强制的审批载荷 + 面板按钮
scripts/dev.sh coderunner   # 代码执行批处理：模型写脚本，沙箱跑循环
scripts/dev.sh toolbase     # 工具底座：治理壳 + fail-closed + 渠道准入 + 统一装配
scripts/dev.sh hotreload    # 技能热刷新：落盘即捡起，进程不重启
scripts/dev.sh memorysystem # 记忆系统：触发 / 三路召回 / 融合 / 写回 / 指标
scripts/dev.sh memorybench  # 记忆治理：写入闸门 / 遗忘清理 / 五维评测
scripts/dev.sh memoryreal "[1-3]"  # 记忆系统真实模型闭环（需 KIMI_API_KEY）
scripts/dev.sh sensor       # Sensor 反馈层：计算/推断分野 / 修正指引回流 / 转向循环
scripts/dev.sh sensorreal "[1-3]"  # Sensor 真实闭环（需 KIMI_API_KEY）
scripts/dev.sh template      # Harness 模板：拓扑拴束 / 实例化 / 漂移检查
scripts/dev.sh pathguard     # 路径验证流水线：规范化→白名单→运行时校验防穿透
scripts/dev.sh diagnose      # 失败归因：下游抖动 / Prompt 漂移 / 模型退化
scripts/dev.sh episodic      # 情境记忆：失败档案蒸馏教训，同类任务召回进场
scripts/dev.sh injection     # 间接提示注入防线：扫描/脱敏/不可信标记
scripts/dev.sh topology      # 多 Agent 四拓扑：Workflow/Supervisor/Hierarchical/Swarm + 委派护栏 + 选型决策
scripts/dev.sh parallel "[real]"  # Supervisor 并行 fan-out：同时开工省墙钟，单专家故障不拖死整轮（real=真实主管并发分派）
scripts/dev.sh swarmdeadline # Swarm 整轮时间盒：到点就地收工，已有产出不丢
scripts/dev.sh stream        # LLM 流式输出：逐块消费 + 预算熔断
scripts/dev.sh streamreal "[1-2]"  # 流式真实对照组：SSE 逐块接收 / 真实熔断（需 KIMI_API_KEY）
scripts/dev.sh eval         # 轨迹级评估：Pass@k / 步数统计 / 对抗探测（剧本）
scripts/dev.sh foldeval     # 折叠对照评估：Subagent 分派 vs 折回主 Loop，从过变挂逐题显形（剧本）
scripts/dev.sh realfoldeval "[每阶段遍数]"  # 折叠对照真实版：订单题真实跑折叠 loop，原版 vs 加固 Skill（需 KIMI_API_KEY）
scripts/dev.sh protocolprobe   # 协议自检探针：真实 ReAct 实验前置预检，裸 prompt vs 带协议（剧本+真实）
scripts/dev.sh ratelimit        # 限流与背压：并发上限（信号量）+ 最小调用间隔，真实 LLM 是稀缺资源
scripts/dev.sh telemetry    # 可观测性：TelemetryLlm 指标装饰 + Prometheus 文本导出 + span 树 JSONL trace（剧本）
scripts/dev.sh redteam      # 安全面：PromptFuzzer 变异 fuzz 护栏/解析器 + 红队 12 探针对打脱敏管线（剧本）
scripts/dev.sh skilldoctor  # 技能库体检：使用频率 + 内容重复度 + 触发撞车 → 膨胀候选
scripts/dev.sh realeval "[k] [startIndex]"  # 真实模型轨迹评估：k 次 trial 并行开跑 + 限流闸（需 KIMI_API_KEY）
scripts/dev.sh report       # 运行报表：底账终态分布 / 按 runner 成本 / 护栏专项 / 失败模式 TOP
scripts/dev.sh outerloop    # 一键外循环：真账蒸馏 → 去重 → 落草稿（real 参数先造真实档案，需 KIMI_API_KEY）
scripts/dev.sh topologyreal "[1-6]"  # 多 Agent 拓扑真实对照组：画像/主管/总监/客服接力/写密集劝退/双路由对照（需 KIMI_API_KEY）
scripts/dev.sh supervisorreal "[1-3]"  # Supervisor 真实对照组：平级分派/专家工具化/全真实矩阵（需 KIMI_API_KEY）
scripts/dev.sh workflow "real [1-7]"  # 动态工作流真实模型分片
```

其余入口（article / real / realpatterns / realadvanced / eval / realeval /
foldeval / realfoldeval / skilldoctor / protocolprobe / ratelimit /
telemetry / redteam /
judge / loop / gate / approval / distill / distillreal / sandbox / coderunner / workflow /
context / interaction / routing / credentials / checkpoint / pending /
toolbase / hotreload / memory / memorysystem / memorybench / memoryreal /
sensor / sensorreal / template / pathguard / diagnose / episodic /
injection / topology / topologyreal / supervisorreal / parallel /
swarmdeadline / stream / streamreal / report / outerloop）见
[LANGGRAPH4J.md](LANGGRAPH4J.md)「运行」一节。

演示默认使用 `ScriptedLlm`（脚本化假模型）预置对话，完整打印每个模式的控制流
轨迹（Thought / Action / Observation / 评分 / 批语 / 反思等）。

## 接入真实模型

所有载体只依赖 `LlmClient` 接口。真实模型客户端已内置：

- `KimiLlm`——Kimi（Moonshot）agent 网关，走 OpenAI 兼容接口，需要环境变量
  `KIMI_API_KEY`，可选 `KIMI_CHAT_BASE` / `KIMI_MODEL`；
- `LangChain4jLlm` / `OpenAiCompatibleLlm`——OpenAI 兼容接口，需要 `OPENAI_API_KEY`。

环境变量统一经 `harness.config.AgentConfig` 装配（默认值 + 启动校验 + 注入式 `from(Map)`
供测试）：`KIMI_API_KEY` / `KIMI_CHAT_BASE` / `KIMI_MODEL` / `AGENT_RUNS_DIR` /
`AGENT_RULES_DIR`。新增配置不许再散落直读。

真实模式的 demo 入口统一检查环境变量，缺 key 时明确报错退出，不会悄悄退回剧本。

## 代码结构

```
core/src/main/java/com/example/    # agent-core：库（生产可用，语义化版本）
├── agent/                      # 九大模式手写版
│   ├── llm/                    # LlmClient / ScriptedLlm / OpenAiCompatibleLlm
│   ├── tool/                   # Tool / ToolRegistry / DemoTools / Evaluator
│   └── patterns/               # react / plan / rewoo / compiler / basicreflection
│                               # / reflexion / lats / selfdiscover / storm
├── langgraph4j/                # 状态图版 + 评估（见 LANGGRAPH4J.md）+ LLM 客户端
│                               #（LlmClient 含 stream 流式输出：SSE 逐块 + 预算熔断）
│   ├── patterns/               # 九个图版 + Multi-Agent
│   ├── llm/                    # KimiLlm / LangChain4jLlm / ScriptedLlm / WebSearchLlm
│   └── eval/                   # 轨迹级评估（Pass@k / 步数统计）
├── agentloop/                  # Agent loop 原语：行动→验收→带反馈重试
├── multiagent/                 # 四拓扑 + 委派护栏 + 选型决策 + 并行/时间盒
├── workflow/                   # 动态工作流：LLM 自己生成子 Agent 图
└── harness/                    # 治理层：Budget / RunRecord+RunStore / ToolGate
    └── ApprovalPolicy / RuleDistiller / config（统一配置 AgentConfig）
        / sandbox（沙箱 + CodeRunner 脚本批处理）/ context（上下文）
        / interaction（交互 + Web 面板 + PendingAction 审批载荷）
        / planning（Todo+搜索策略）/ memory（分层记忆+触发+三路召回+指标
        +写入闸门+遗忘清理+自动清理周期+五维评测）
        / routing（筛选+模型路由）/ credentials（凭据保管）
        / toolbase（治理壳+fail-closed+渠道准入+统一装配）
        / skills（技能+事件总线+operation 检索+热刷新）/ probe（协议自检探针）
        / sensor（反馈层：计算/推断感知+修正指引+质量分层+转向台账）
        / template（Harness 模板：拓扑约定+sensor 一捆+漂移检查）
        / diagnostics（失败归因）/ security（注入防线）

demo/src/main/java/com/example/    # agent-demo：52 个 demo = core 的可执行规约与回归套件
└── （与 core 同包结构，只放 *Demo / DemoRunner / LoopBenchmark；
    mvn exec:java@<id> 入口全在这里登记）
```

仓库根的 `runs/`（运行档案 JSONL，含 archive/ 归档）与 `rules/`（蒸馏出的规则草稿）是运行时产物。

## 说明

- 每个模式的提示词模板即该模式的核心（设计模式本质上是「以结构化 prompt
  告诉模型如何规划与调用工具」），模板均可在对应 Agent 类中直接修改定制。
- LATS 实现为教学简化版：宽度受限的最优先搜索 + 失败反思回溯，
  未实现完整 MCTS 的模拟退火与 UCT 公式。
- 治理层的生产语义（预算耗尽转人工、越权调用在进工具前被拦、审批一事一议）
  在 harness 包各 demo 里有完整剧本，真实模型路径已验证。
- 设计原则（来自多轮 Harness 文章共识）：API 优先、浏览器兜底——结构化接口
  拿得到的数据不让模型点网页；上下文里永远不放凭据；批处理交给脚本，模型只管推理；
  fail-closed——未注册的工具查不到就拒，不猜不降级；治理挂在注册壳上，不进业务代码；
  记忆的有效性 ≠ 重要性——重要的过期事实照样该退役；遗忘是控制面动作不是缺陷——
  到期要置 EXPIRED、超保留期要物理清，遗忘和记住同样要留痕。
