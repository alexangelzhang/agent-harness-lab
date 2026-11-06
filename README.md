# Agent 工程实验场（Java）：从九大设计模式到生产级 Harness

这个项目始于《Agent 的九种设计模式》的纯 Java 实现，后来一路长成了一个小型
「Agent 工程实验场」：同一套 `LlmClient` 抽象下，并排跑着**编排模式、四种运行
载体、一层生产级治理设施**，全部可离线剧本跑通，可接 Kimi 或任意 OpenAI
兼容端点的真实模型。

零第三方依赖（JDK 17+，langgraph4j 相关能力除外），Maven 构建。

## 四个层面

| 层面 | 包 | 内容 |
|------|-----|------|
| 编排模式 | `com.example.agent`（手写版）、`com.example.langgraph4j.patterns`（状态图版） | 九大模式，逐行对照 |
| 运行载体 | `agent` 手写 / `langgraph4j` 状态图 / `agentloop` 原语 loop / `workflow` 动态工作流 | 同一剧本可比选型（loopbench） |
| 治理层 | `com.example.harness` | 预算 / 审批 / 工具网关 / 凭据 / 沙箱 / 注入防线 / 路径验证 / 磁盘 checkpoint / 失败归因 / 限流 / 可观测 / 红队；记忆系统（三路召回、写回闸门、遗忘、五维评测）与情境记忆；Sensor 反馈层；Harness 模板；技能与事件总线；运行档案 + 报表 + 一键外循环 |
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

两模块：`core/` 是库（`agent-core`，语义化版本，生产可用），`verify/` 是 58 个
场景（库的可执行规约）。命令从仓库根跑，统一入口 `scripts/dev.sh <id>`
（自动先装 parent+core，改完 core 直接跑；等价 `mvn -q exec:java@<id> -pl verify`）。

第一次跑，这三条足够感受全貌：

```bash
scripts/dev.sh graph      # 九大模式的状态图版（手写版：mvn -q exec:java -pl verify）
scripts/dev.sh loopbench  # 四种运行载体同剧本对比
scripts/dev.sh report     # 历次运行沉淀成一张报表
```

其余四十多个入口不在这里罗列——每个 id 写在 [LANGGRAPH4J.md](LANGGRAPH4J.md)
对应能力的章节里，读到哪节跑哪节；全量 id 以 `verify/pom.xml` 的 exec
executions 为准。

剧本场景用 `ScriptedLlm` 假模型，离线可跑，完整打印控制流轨迹；真实模型
对照组要 `KIMI_API_KEY`（见下节）。

## 接入真实模型

所有载体只依赖 `LlmClient` 接口，任何 OpenAI 兼容端点都能接。内置客户端：

- `LangChain4jLlm`——生产级写法，包 langchain4j 官方 OpenAI 客户端；
- `JdkHttpLlm`——零依赖参考实现（JDK HttpClient + 手写 JSON）；
- `WebSearchLlm`——客户端层联网搜索（$web_search 内置工具是 Moonshot 系网关的
  私有扩展，端点不支持时不可用）；
- `OpenAiCompatibleLlm`——手写版模块自带的同款零依赖客户端（读 `OPENAI_*` 变量）。

配置只有三个变量（经 `harness.config.AgentConfig` 统一装配：默认值 + 启动校验 +
注入式 `from(Map)`）：`LLM_API_KEY`（真实模式必填）/ `LLM_BASE_URL` / `LLM_MODEL`，
产物目录另有 `AGENT_RUNS_DIR` / `AGENT_RULES_DIR`。旧名 `KIMI_API_KEY` 等仍兼容，
新部署请用 `LLM_*`。新增配置不许再散落直读。

真实模式的场景入口统一检查环境变量，缺 key 时明确报错退出，不会悄悄退回剧本。

## 代码结构

```
core/src/main/java/com/example/    # agent-core：库（生产可用，语义化版本）
├── agent/                      # 九大模式手写版
│   ├── llm/                    # LlmClient / ScriptedLlm / OpenAiCompatibleLlm
│   ├── tool/                   # Tool / ToolRegistry / SampleTools / Evaluator
│   └── patterns/               # react / plan / rewoo / compiler / basicreflection
│                               # / reflexion / lats / selfdiscover / storm
├── langgraph4j/                # 状态图版 + 评估（见 LANGGRAPH4J.md）+ LLM 客户端
│                               #（LlmClient 含 stream 流式输出：SSE 逐块 + 预算熔断）
│   ├── patterns/               # 九个图版 + Multi-Agent
│   ├── llm/                    # JdkHttpLlm / LangChain4jLlm / ScriptedLlm / WebSearchLlm
│   └── eval/                   # 轨迹级评估（Pass@k / 步数统计）
├── agentloop/                  # Agent loop 原语：行动→验收→带反馈重试
├── multiagent/                 # 四拓扑 + 委派护栏 + 选型决策 + 并行/时间盒
├── workflow/                   # 动态工作流：LLM 自己生成子 Agent 图
└── harness/                    # 治理层：Budget / RunRecord+RunStore / ToolGate
                                # / ApprovalPolicy / RuleDistiller 在包顶层，
                                # 其余一个能力一个子包：config / context / credentials
                                # / diagnostics / interaction / memory / planning
                                # / probe / redteam / routing / sandbox / security
                                # / sensor / skills / telemetry / template / toolbase

verify/src/main/java/com/example/    # agent-verify：61 个场景 = core 的可执行规约与回归套件
└── （与 core 同包结构，只放 *Scenario / PatternRunner / LoopBenchmark；
    mvn exec:java@<id> 入口全在这里登记）
```

仓库根的 `runs/`（运行档案 JSONL，含 archive/ 归档）与 `rules/`（蒸馏出的规则草稿）是运行时产物。

## 说明

- 每个模式的提示词模板即该模式的核心（设计模式本质上是「以结构化 prompt
  告诉模型如何规划与调用工具」），模板均可在对应 Agent 类中直接修改定制。
- LATS 实现为教学简化版：宽度受限的最优先搜索 + 失败反思回溯，
  未实现完整 MCTS 的模拟退火与 UCT 公式。
- 治理层的生产语义（预算耗尽转人工、越权调用在进工具前被拦、审批一事一议）
  在 harness 包各场景里有完整剧本，真实模型路径已验证。
- 设计原则（来自多轮 Harness 文章共识）：API 优先、浏览器兜底——结构化接口
  拿得到的数据不让模型点网页；上下文里永远不放凭据；批处理交给脚本，模型只管推理；
  fail-closed——未注册的工具查不到就拒，不猜不降级；治理挂在注册壳上，不进业务代码；
  记忆的有效性 ≠ 重要性——重要的过期事实照样该退役；遗忘是控制面动作不是缺陷——
  到期要置 EXPIRED、超保留期要物理清，遗忘和记住同样要留痕。
