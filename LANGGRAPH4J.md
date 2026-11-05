# Agent 工程实验场 · 主文档

> 本文件是项目主文档。项目起点是知乎《LangGraph 核心概念系统详解（结合手写
> ReAct 对比，面试级理解）》的 **Java 实现**（Python LangGraph → Java 对应
> [langgraph4j](https://github.com/langgraph4j/langgraph4j)，
> `org.bsc.langgraph4j:langgraph4j-core`，独立于 LangChain4j / Spring AI，
> 可搭配任意 LLM 客户端），此后一路演化成覆盖编排模式、四种运行载体与
> 治理层的实验场——全貌见 [README](README.md)，第一阶段总结见
> [AGENT_DESIGN_PATTERNS_REPORT](AGENT_DESIGN_PATTERNS_REPORT.md)（历史文献）。
> 下文「langgraph4j」相关章节即最初的实现与踩坑记录。

## 项目演进时间线

| 时间 | 阶段 | 落了什么 |
|------|------|---------|
| 10-12 | 起点 | 《Agent 的九种设计模式》→ `com.example.agent` 纯 JDK 手写版（九大模式） |
| 10-20 | 图版合并 | 《LangGraph 核心概念》→ `com.example.langgraph4j` 状态图版并入同项目，手写/图版逐行对照 |
| 10-21 | 真实模型 | `JdkHttpLlm` 接入 agent 网关，九大图版逐一真实验证；HITL 审批中断 + 客户端层 `$web_search` |
| 10-22 | 评估 | `eval` 包轨迹级评估（Pass@k / 步数统计）+ 对抗探针；judge 双职责（终审 + 捞回） |
| 10-26 | 治理层 | `com.example.harness`：Budget 预算、RunRecord 档案、ToolGate 网关、ApprovalPolicy 审批、RuleDistiller 规则蒸馏 |
| 10-29 | 原语 loop | `com.example.agentloop` 不依赖状态图的 Agent loop 原语（Actor/Verifier/StopCondition + 失败分流 + 预算终态）；loopbench 同剧本对比图版 vs 原语版 |
| 10-30 | 动态工作流 | `com.example.workflow`：Claude Code「动态 harness」六模式 Java 版，真实模型验证协议成立——见 docs/dynamic_workflows_claude.md |
| 10-30 | 治理闭环 | workflow 接入 Budget + RunRecord；RunArchiveScenario 跨载体汇总——原语/图/动态工作流三种载体同一张审计底账；文档定位统一（README / AGENTS.md / 历史报告封存） |
| 10-31 | 沙箱后端 | `harness.sandbox`：Sandbox 执行环境抽象（内存/本地两种实现 + 生命周期钩子 + 快照恢复），补齐 Vercel Academy 教程第 4 章缺口，见 docs/build_coding_agent_harness_vercel.md |
| 10-31 | 上下文/交互/技能 | `harness.context`（逐步裁剪+输出上限+响应缓存）、`harness.interaction`（结构化提问+策略拦截+流式渲染）、`harness.skills`（按需技能+事件总线）——教程第 5/8/9/10 章落地，见 docs/build_coding_agent_harness_vercel.md |
| 10-31 | 缺口清零 | 磁盘快照（LocalSandbox 落盘 + 恢复）、缓存 TTL/容量/谓词失效、摘要压缩、TodoList + SearchPlanner、WebDashboard 事件面板——Vercel Academy 十章对标全部闭环，见 docs/build_coding_agent_harness_vercel.md |
| 10-31 | 十二部件 | 「生产级 Agent Harness 十二个部件」缺口补齐：`harness.memory`（分层记忆/主动检索/异步整理）、`harness.routing`（候选筛选 + 模型路由）、`harness.credentials`（凭据保管）、skills 长 capability/operation 检索层、FileSystemSaver 磁盘 checkpoint、PendingAction 审批载荷 + 面板按钮、CodeRunner 脚本批处理——见 docs/harness_twelve_points.md |
| 10-31 | 工具底座/记忆系统 | 尼恩 QwenPaw 工具底座：`harness.toolbase`（SchemaTool 参数契约 / GovernedTool 治理壳 / ToolResolver fail-closed / ChannelAcl 渠道准入 / WorkspaceBootstrap 统一装配）、`SkillWatcher` 技能热刷新（指纹轮询，进程不重启）；智能体AI 记忆系统：`Memory` 字段升级（confidence/status/validUntil/permissions）、MemoryTrigger 触发器、KnowledgeBase 外部知识、RecallPipeline 三路召回 + 五维融合 + 冲突抑制、写回三操作 ADD/MERGE/SUPERSEDE、MemoryMetrics 指标——见 docs/toolbase_and_memory_systems.md |
| 10-31 | 记忆治理 | LLM Memory 综述三缺口补齐：`MemoryJanitor`（到期置 EXPIRED / 超保留期物理清 / 报告可审计）、写入闸门（置信 <0.6 挂 UNCONFIRMED，确认才转正）、`MemoryBench` 五维 benchmark（保真/抗干扰/持久/效率/容量）+ 限容量 LRU 淘汰；清理接进 RecallPipeline（每 8 次召回自动 sweep）——见 docs/memory_survey_review.md |
| 10-31 | Sensor 反馈层 | Fowler/ThoughtWorks Harness 工程：`harness.sensor`（Sensor 计算/推断分野、Finding 带修正指引 = 正向 prompt injection、四件计算型 + LlmReview 推断型、FitnessSensor 架构适应度、SensorSuite quality left 分层、guides 撞车标注）、`SensorLedger` 转向循环台账、`agentloop.SensorVerifier` 零侵入回流闭环——见 docs/harness_engineering_fowler_thoughtworks.md |
| 10-31 | Harness 模板 | `harness.template`：HarnessTemplate 拓扑约定+sensor 一捆（api-service/event-processor/dashboard 三拓扑）、TetheredWorkspace 实例化带来源戳、TemplateRegistry.driftCheck 漂移检查、Ashby 量化——Fowler 对账最后一块闭环 |
| 10-31 | 专著四缺口 | 牛津《AI Agents: The Definitive Guide》Ch6/7/10/12 硬缺口：`sandbox.PathGuard` 路径验证流水线（规范化→白名单→运行时校验，防目录穿透）、`diagnostics.FailureDiagnostician` 失败归因（下游抖动/Prompt 漂移/模型退化）、`memory.EpisodicMemory` 情境记忆（失败档案蒸馏教训进记忆库，幂等）、`security.InjectionGuard` 间接提示注入防线（指令模式扫描+脱敏+不可信标记）——见 docs/ai_agents_definitive_guide_review.md |
| 10-31 | 多 Agent 四模式 | 智能体AI《多 Agent 不是单 Agent 的升级版》：新包 `multiagent`——`WorkflowPattern`（控制流写死）、`SupervisorPattern`（平级分派 + Supervisor-as-Tools 双结构）、`HierarchicalPattern`（层层下派）、`SwarmPattern`（去中心化接力）、`DelegationGuard` 委派护栏（max-depth/handoffs/循环检测）、`TopologyAdvisor` 选型决策树（读源/写密集信号，默认不拆）——见 docs/multi_agent_topology_review.md |
| 11-01 | 生产化缺口 #1 单测落地 | core 接入 JUnit 5（parent pom 挂 surefire，`mvn test/verify` 自动跑）：第一批 32 个用例覆盖六个纯逻辑无 LLM 依赖的类——`RunStoreTest`（追加/读回/轮转/归档保留/坏行容忍/清空 6 例）、`MetricsRegistryTest`（三类的累加快照导出复位 6 例）、`TelemetryLlmTest`（complete/stream 两路计数、失败、在飞配对 5 例）、`TracerTest`（span 父子链/traceId 过滤/JSONL 追加导出/双关幂等 5 例）、`RateLimitedLlmTest`（并发峰值恰为上限、间隔闸、异常时信号量不泄漏 4 例）、`PromptFuzzerTest`（变异确定性、crash 复现样本、策略覆盖 6 例）；测试自身也过 `-Werror -Xlint:all`。至此生产化缺口清单 #1~#7 全部清零；`mvn -q clean verify` = 编译 + 单测的提交前底线 |
| 11-01 | 生产化缺口 #5+#7 落地 | 可观测性 `harness.telemetry`：`MetricsRegistry`（counter/timer/gauge，Prometheus 文本导出接 textfile collector）+ `TelemetryLlm`（LlmClient 装饰器，调用/失败/耗时/字符量自动攒，label 分维）+ `Tracer`（span 树 JSONL 跨进程锁导出，traceId 下钻）；安全面 `harness.redteam`：`PromptFuzzer`（8 类变异 fuzz 护栏与解析器，crash 留复现样本）+ `RedTeamBattery`（12 条标准攻击库，确定性/LLM 双判定模式，guarded 脱敏管线一键组）——新场景 `@telemetry`（12 断言）/ `@redteam`（fuzz 89 变异零 crash，红队裸目标 67% vs 脱敏 33%，盲区如实报告）；至此缺口清单只剩 #1 单测 |
| 11-01 | 限流接入真实并发 | #6 落地接线两处：`EvalHarness.evaluateParallel`（k 次 trial 并行开跑，独立编译图/独立 threadId，并行只省墙钟不改 Pass@k，线程异常兜成 ERROR 轨迹）——`@realeval` 已接 `RetryingLlm(RateLimitedLlm(real, 4, 200))` 并发闸，k=3 真实回归 Pass@3=3/3、墙钟 66s vs 串行相加 140s；`@parallel` 加第四幕 `real` 参数——真实主管经 `ProtocolProbe` 预检后 `dispatchParallel` 并发分派，3 条全命中花名册 |
| 11-01 | 审计与外循环闭环 | 多 Agent 运行经 `RunJournal` 落 runs.jsonl 同一底账（GUARD_BLOCKED 显式终态），`@archive` 跨四载体汇总；外循环升级：`RuleDistiller` 护栏模式（cycle/max-depth/max-handoffs 聚类，与 AGENTS.md 去重）+ 采纳检验（合并专员后重跑不再被拦）+ 真实版 `@distillreal`（真实撞护栏蒸出规则）；Swarm 路由带当前持有者参数（跳过交回自己），真实对照组逼出写密集前置劝退与模型路由 modelRouter；蒸馏出的护栏教训（交接绕圈/层级深度/交接次数上限/写密集不拆）已合并进 AGENTS.md 已知深坑，去重双向验证过（剧本版标【已收录】、真实版断言与去重共存） |
| 11-01 | 多 Agent 生产深化三件套 | 并行 fan-out（`SupervisorPattern.dispatchParallel`，并发上限 + 单专家故障隔离成「[调用失败]」）；Swarm 整轮时间盒（`run(..., deadlineMillis)`，到点就地收工、已有产出不丢）；LLM 流式输出（`LlmClient.stream` 逐块回调返 false 即中止，JdkHttpLlm 走 SSE 提前关流省 token，与 complete 独立两条路径；ScriptedLlm 切词演练同路径）——新场景 `@parallel` / `@swarmdeadline` / `@stream` / `@streamreal [1-2]`，并行 run 落账 `multiagent:supervisor-parallel`；真实轨全量回归 17 分片全绿；回归同时逼出 TopologyRealScenario 退款专员话术补禁提词（提「物流」被关键词路由误判交回上一棒，护栏按设计掐循环） |
| 11-01 | realadvanced 三处脆弱修复 | 真实回归三连击暴露并全部修复：①ReAct 解析器对「只吐 Thought 不吐 Action」按直接终答兜底（ArticleScenario.LlmThinkNode，真实模型漂移不炸循环）；②`RetryingLlm` 按消息特征重试瞬时故障（400/tokenization/timeout 最多 3 次退避，LangChain4jLlm 刻意 maxRetries(0) 的责任上移到 wiring 层）；③HITL 恢复前确认 lastAction 非 Finish（模型首跳直出终答时没有 checkpoint 可恢复，别再发 resume）；④curl 对照实测定位网关新约束——$web_search 回传 tool_calls 的 type 必须写 "function"，照抄响应的 "builtin_function" 报 400 tokenization failed（WebSearchLlm 修复，三坑均已入 AGENTS.md 深坑）。修复后 `@realadvanced` 三段全通（HITL 中断→审批→恢复、混合调度双路径、客户端层 $web_search 真实出答案） |
| 11-01 | 报表与一键外循环 | 清单 7/8/9/10 落地：`RunReportScenario`（`@report`）把底账读出来见人——终态分布条、按 runner 成本、护栏专项（按规则类型聚类拦在哪）、失败模式 TOP、按天趋势，GOOD_TERMINALS 表与蒸馏器共享；`OuterLoopScenario`（`@outerloop`，`real` 参数先造真实档案）一键跑「真账蒸馏→去重→落草稿→采纳检验」；顺手修蒸馏器真 bug——终态过滤漏 DONE，workflow 正常收口被蒸成废草稿；eval/realeval 提进 README 主清单；对标研读三步流程（存档→落地→验证登记）固化进 AGENTS.md |
| 11-01 | 生产化缺口 #6 并发与资源 | `RateLimitedLlm`（llm 包）：限流与背压两闸——并发上限（信号量，超出原地排队=背压）+ 最小调用间隔（防突发打满网关配额）；与 RetryingLlm 正交，推荐 `RetryingLlm(RateLimitedLlm(real))` 限流在内重试在外，重试风暴冲不破并发闸；HTTP 侧 JdkHttpLlm 改全 JVM 共享 HttpClient（连接复用，WebSearchLlm 早就是共享）；新场景 `@ratelimit`（剧本离线）：6 线程打上限 2（峰值恰 2、排队不丢）+ 100ms 间隔（5 次 412ms vs 对照 0ms）4 断言全绿 |
| 11-01 | 生产化三缺口（2~4） | 库形态：仓库分模块——`core/`=库 `agent-core`（121 个类，语义化版本，铁律 core 不依赖 verify 模块，编译即验证），`verify/`=52 个场景（exec executions 随迁），`scripts/dev.sh <id>` 一条命令（装 parent+core 再 exec），等价 `mvn -q exec:java@<id> -pl verify`；配置管理：`harness.config.AgentConfig` 统一装配（LLM_API_KEY/CHAT_BASE/MODEL/AGENT_RUNS_DIR/AGENT_RULES_DIR，默认值+requireApiKey 启动校验+from(Map) 注入式），JdkHttpLlm/LangChain4jLlm/WebSearchLlm 与全部场景横幅直读已收敛；状态存储：`RunStore` 硬化——跨进程文件锁（FileChannel.lock）、超 8MB 轮转进 archive/、留最近 5 份归档、坏行跳过告警不拖垮读取，临时验证程序 8/8 实测（轮转顺序保持/坏行容忍/保留上限/clear 连归档清），@topology 写账 + @report 读账回归正常 |
| 11-01 | Anthropic 折叠 subagent 研读落地 | `FoldEvalScenario`（`@foldeval`，eval 包）同一组 Golden Examples 跑 Subagent 分派版 vs 折回主 Loop 版（Skill 注入 + 工具表）各 2 遍——复刻原文客服失败案例：订单题折叠版第 1 遍漏调 query_shipping 从过变挂（1/2），简单题无损且平均调用严格更少；`SkillDoctorScenario`（`@skilldoctor`，harness.skills 包）技能库体检——使用频率（僵尸/低频）+ promptSnippet 3-gram Jaccard 重复度 + trigger 撞车三维度出膨胀候选清单；真实对照组 `RealFoldEvalScenario`（`@realfoldeval`）跑出剧本给不了的教训：prompt 没写协议说明时真实模型 6/6 遍全程自然语言、一轮 Action 不吐（教协议前别谈行为），补齐后 6/6 全走对；`ProtocolProbe`（`harness.probe` 包，`@protocolprobe`）协议自检探针把教训固化成真实实验前置预检——一次最小调用确认模型会吐协议行，requireOrExit 硬闸门 fail fast 不烧额度，支持自定义协议行 pattern（multiagent 分派协议经 `rosterProtocol` 接入，探针 prompt 没给专员名单时真实模型真会自造名字，第一版实测命中），已接入 realfoldeval / real / realadvanced×2 / supervisorreal / topologyreal 并逐一真实回归；见 docs/anthropic_fold_subagents.md |
| 11-05 | Kibitzer 记忆侧车 | OmO（oh-my-openagent）研读落地：新子包 `harness.memory.kibitzer`——`KibitzerEvent`（事件进侧车前截断 + secret 脱敏）、`MemoryReader`（只读视图，零写入是编译期事实不是约定）、`Kibitzer`（唤醒闸门：触到未判定记忆才花裁判模型调用；每 wake 8 次只读自证预算；唯一动作 nudge；裁判故障退避不误判；已投递/已拒绝清单重播种）——召回之后注入之前的常驻裁判，与同步 RecallPipeline 互补；场景 `@kibitzer` 两幕全断言——见 docs/omo_orchestration_kibitzer.md |

一句话：模式层（九大模式）→ 载体层（手写/状态图/原语/动态工作流，可对比选型）
→ 治理层（预算/档案/网关/审批/蒸馏/记忆——写回有闸门、遗忘在岗、五维可量）
→ 审计闭环（跨载体一张底账）。

## 运行

仓库分两模块（2026-11-01 起）：`core/`=库 `agent-core`（语义化版本，生产可用），
`verify/`=54 个场景（core 的可执行规约）。以下命令从仓库根跑，
`scripts/dev.sh <id>` 与 `mvn -q exec:java@<id> -pl verify` 等价
（前者自动先装 parent+core；首次手动跑先 `mvn -q install -N && mvn -q install -pl core`）：

```bash
scripts/dev.sh graph        # 运行九个图版演示（八大模式 + Multi-Agent 补充，ScriptedLlm 剧本）
scripts/dev.sh article      # 运行文章五概念演示（ArticleScenario）
scripts/dev.sh real         # 接入真实 LLM 跑 ReAct 与 REWOO 两张图（需 LLM_API_KEY）
scripts/dev.sh realpatterns # 真实 LLM 跑另外五个模式图（可用 "reflection,compiler" 挑着跑）
scripts/dev.sh realadvanced # 真实 LLM 进阶：HITL 审批中断 + 客户端层 $web_search 搜索
```

无需 API Key 的前两个命令用 `ScriptedLlm` 脚本化假模型预置对话，完整打印图的执行轨迹。
（不带 `@` 的 `mvn -q exec:java -pl verify` 默认跑手写版九大模式 PatternRunner。）

治理层与能力层的四十多个场景不在这里单列：每个 id 随能力在下方对应章节
首现（如「运行档案」一节的 `mvn exec:java@archive`），全量 id 以
verify/pom.xml 的 exec executions 为准。

## 接入真实 LLM（LangChain4jLlm / JdkHttpLlm）

LLM 抽象是接口 `llm/LlmClient.java`（`String complete(String)`），有三个实现：

- **`LangChain4jLlm`（生产级，RealScenario 默认用）**：包装 langchain4j 官方 `OpenAiChatModel`，
  pom 已接入 `org.bsc.langgraph4j:langgraph4j-langchain4j`（官方集成模块：ChatMessage 序列化、
  Tool 节点等，1.9.6 锁定 langchain4j **1.22.0**）和 `dev.langchain4j:langchain4j-open-ai`。
  `OpenAiChatModel.builder()` 指向任意 OpenAI 兼容端点；temperature 是 `Double`，
  **不设置即不发送**（网关模型只允许 1，带了会被拒绝）；K2 推理时 content 为空
  则回退 `AiMessage.thinking()`。
- **`JdkHttpLlm`（零依赖参考实现）**：JDK HttpClient + 手写 JSON 提取，取 `content`、
  为空回退 `reasoning_content`，不引任何第三方库。
- **`ScriptedLlm`**：脚本化假模型，离线跑剧本。

九个图版和 ArticleScenario 只依赖 `LlmClient` 接口，换实现即换模型。

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `LLM_API_KEY` | （必填） | API Key |
| `LLM_BASE_URL` | `https://agent-gw.kimi.com/coding/v1` | OpenAI 兼容端点 |
| `LLM_MODEL` | `kimi-k2-0905-preview` | 模型名 |

默认值只是作者自用的缺省，换任意 OpenAI 兼容端点改三个变量即可；旧名
`KIMI_API_KEY` / `KIMI_CHAT_BASE` / `KIMI_MODEL` 仍兼容（新名为空时回退）。

`RealScenario` 用真实模型 + **真实联网搜索**（`tool/WebSearchTool.java`：中文维基百科 opensearch +
页面摘要，免费免 Key，JDK HttpClient 手写 JSON 解析）跑两张图（实测 exit=0）：

- **ReAct 图**：问「维基百科记载，Google 成立于哪一年？成立于几月几日？距今多少年了？」
  模型走 `Search[Google 成立日期] → Finish` 多轮循环，证据来自维基百科真实条目，
  【关键事实】句定位到「1998年9月4日创立」；
- **REWOO 图**：问「Google 成立于哪一年？距今多少年了？langgraph4j-core 最新版本是多少？」
  真实 planner 一次产出**三步链式计划** `#E1 = Search[...]` → `#E2 = Calculator[2026 - #E1]`
  → `#E3 = Maven[org.bsc.langgraph4j:langgraph4j-core]`，跨三个数据源（维基百科 /
  计算器 / Maven Central）执行，worker 变量替换（**优先四位年份**提出 1998）算出 28，
  solver 汇总三项全部正确。Maven 工具见 `tool/MavenTool.java`（读 maven-metadata.xml 的
  `<latest>`，免费免 Key）；planner 提示词按工具表 + 参数格式提示（toolHints）动态生成；
  solver 输入的每条证据截断到 350 字防超时（K2 推理链长，`LangChain4jLlm` 已把
  请求超时放宽到 120s）。

踩坑两则（都修了）：① LLM 给的查询带疑问词时 opensearch 命中率低——查询逐级降级
（原查询 → 去疑问词 → 纯字母词）；② 手写 JSON 提取用 `(?:...)* ` 正则匹配长文本值会
**递归爆栈（StackOverflowError）**——已改为手工扫描转义。

## 真实 LLM 跑另外六个模式图（RealPatternsScenario）

`exec:java@realpatterns`（可用 `-Dexec.args="reflection,plansolve,reflexion,compiler,lats,selfdiscover,storm,multiagent"` 只跑部分）。
全部实测通过（`kimi-k2-0905-preview`，exit=0）：

- **Reflection**：冷萃咖啡液 slogan 创作-批改循环，真实 reflector 连批 3 轮（每轮指出
  「萃字重复」「概念错位」等具体问题）→ 达到 maxRounds 返回最后一版——真实模型的
  批评远狠于剧本，展示了循环上限的保护作用；
- **Plan & Solve**：真实 planner 产出 4 步编号计划，executor 逐步执行（可自行决定是否调工具），
  finalizer 汇总正确（1998 / 28 年）；
- **Reflexion**：硬约束任务（恰好 20 个汉字 + 禁用词）——actor 首轮把自我批评写进输出
  导致 FAIL（evaluator 是纯 Java 程序化校验，整段输出都算数），self-reflection 生成语言反思
  存入情景记忆，**第 2 次尝试读到反思后输出干净的 20 字 slogan，PASS**——记忆环全链路真实跑通；
- **LLMCompiler**：planner 产出「Google / Microsoft 成立年份」两个 Search 任务的 DAG，
  scheduler 节点内 CompletableFuture **并行执行同一波次**（2 任务并发），joiner 正确汇总
  （Microsoft 1975 更早，早 23 年）；
- **LATS**：三层搜索树真实展开——深度 0 三个候选查询分别评 9/9/4 分，择优「Google 成立
  年份 维基百科」（其观察里有 1998）；深度 1 模型在 Finish 与 Calculator 之间选了
  `Calculator[2026-1998]`（10 分）先算年数；深度 2 收敛 Finish（10 分）给出
  「1998 年成立，距今 28 年」全部正确。LLM 调用量为七模式之最（expand + 每候选一次
  evaluate），全套最慢；
- **Self-Discover**：水池进出水管问题——真实 selector 从模块库中选出「分步骤推理 /
  逆向求解 / 验证与反思」，adaptor 产出 3 步 JSON 推理计划，implementor 逐步执行
  （净效率 1/24 → 方程 t=24 → 合理性校验），finalizer 汇总「24 小时」正确——
  「先反思推理方式、再按该方式解题」的两段式在图上真实可见；
- **STORM**：主题「Java 虚拟线程」——真实模型产出 15~17 章大纲（章节数完全由模型
  自由决定），researcher ⇄ writer 两节点环逐章「3 视角提问（真实维基搜索，哲学式提问
  命中率低但 writer 靠自身知识仍成文）→ 撰写」，polisher 补引言/结论拼成 3889 字符
  完整长文（两次运行均成稿）。LLM 调用量随行数线性放大（~4 次/章 + 3 次固定），
  第一版撞上 langgraph4j 默认 `recursionLimit(25)`（图步数 = 1 + 2×章节数 + 1，
  17 章需 36 步）——放宽到 100 后跑通。这是「运行期量超出图默认护栏」的典型案例；
- **Multi-Agent**（系列补充）：supervisor 真实调度 2 轮——Researcher 真实维基搜索
  拿到「1998年9月4日创立」，Calculator 算出 28 年，supervisor 第 3 轮 FINISH 给出
  完整答案。真实模型的 FINISH 输出是多行 Markdown，第一版正则 `.+` 只捕获了
  首行（答案只剩「最终答案」三个字）——改 `(?s)` 后修复：协议解析的正则要
  预判多行 payload。

注意：K2 是推理模型，单次调用最长可达数分钟（网关拥塞时更明显），`LangChain4jLlm`
已把请求超时放宽到 300s 并关闭重试；跑全套真实演示建议给足时间。

## HITL 审批与客户端层联网搜索（RealAdvancedScenario）

`exec:java@realadvanced`，三个演示都实测通过（exit=0）：

- **HITL 审批**：ReAct 图编译时加 `MemorySaver` + `interruptBefore(TOOL_NODE)`。真实模型
  产出 `Action: Search[...]` 后**在工具执行前中断**，待审批动作从 State 读出
  （`Search[Google 成立年份 维基百科]`），Checkpoint 历史 2 个快照；人工确认后
  `GraphInput.resume()` 恢复执行，Search 真正发起、拿到证据、Finish 出正确答案。
- **混合调度**：同一张 ReAct 图挂三个工具——`Calculator`（Java）、`Search`（维基百科，
  Java 图级工具）、`WebSearch`（节点外壳 + 客户端 `$web_search` 内核），模型按问题类型
  路由：百科事实走 `Search`（1998 / 28 年正确），维基没有的新锐信息走 `WebSearch`
  （联网查到 GitHub 版本信息）——图保留节点可见性/可中断性，执行引擎可托管。
- **客户端层搜索**（`llm/WebSearchLlm.java`）：把网关内置 `$web_search` 下沉到 LLM 客户端
  内部循环（协议：请求 → 模型返回带 search_id 的 tool_call → 客户端回空结果 tool 消息 →
  服务端注入搜索结果 → 下一轮出正文，16s 两轮实测打通）。**图级 vs 客户端级对比**：
  图级 Search 是显式节点——进 State 轨迹、可 interruptBefore 审批、可被 LLMCompiler 并行；
  客户端级是节点黑盒——实现最省事但图上不可见、不可中断、结果不可复用。
  实测还观察到：网关搜索对冷门技术问题（langgraph4j 版本）给出的答案与 Maven Central
  实际不符（且两次查询结果不一致），权威数据查询仍应走专用工具（`MavenTool`）。

踩坑：① `$web_search` 是 Moonshot 私有扩展，langchain4j 的 ToolSpecification 只能发
`type:"function"` 会被网关忽略——`WebSearchLlm` 与 `JdkHttpLlm` 一样手写 HTTP，请求体完全可控；
② 模型的工具使用以 **prompt 声明为准**：提示词里只写 Calculator 时，即使 API 注册了
`$web_search` 模型也会拒绝调用（「工具口径要一致」）；
③ 手工拼 messages JSON 时初始串不能提前闭合数组尾 `]`（否则追加的消息挂在数组外，HTTP 400）。

## 九大模式 → 状态图（手写版 vs 图版，逐行对照）

见 `patterns/ReflectionGraph.java` 与 `patterns/PlanSolveGraph.java`，类注释里逐行标注了
手写版（`com.example.agent` 包）每一行循环代码对应的节点/条件边。核心规律：

| 手写版 | 状态图 |
|---|---|
| 方法（planner/generate/critique/executor） | 节点（NodeAction） |
| while/for 循环 | 节点自环或两节点环（普通边连回去） |
| 循环里的 if-else 分支 | 条件边 + 路由函数（pathMap） |
| 循环变量（round/cursor） | State 的 `Channels.base` 字段（节点返回覆盖） |
| 累积列表（completed/messages） | State 的 `Channels.appender` 字段（节点返回追加） |
| `return`（跳出循环） | 路由返回 `StateGraph.END` |

- **Basic Reflection**：`START → generator → reflector`，reflector 后条件边判断
  ACCEPT / 轮数上限 → END，否则 → generator（两节点环）。运行入口 `ReflectionGraph.runScenario()`，
  剧本与手写版一致（slogan 两轮批改后 ACCEPT）。场景 2 验证环境故障分流：
  generator 的 LLM 调用抛 `EnvException`（网关超时）时不占 round 额度、路由回自身重试，
  超限终止并在 verdict 通道写 `ENV_ABORTED`——和 agentloop 包同一套约定
  （`envError`/`envCount` 通道 + generator/reflector/judge 三条边各自带环境分支）。
  场景 3 验证预算护栏：预算 4 次调用时第 2 轮批改完即
  `BUDGET_EXHAUSTED` 转人工（maxRounds=10 只是摆设），定稿通道保留
  最后一版供审计。
- **Plan & Solve**：`START → planner → executor`，executor 后条件边三分支：
  失败 → `replanner → executor`；成功未走完 → executor 自环；走完 → `finalizer → END`。
  运行入口 `PlanSolveGraph.runScenario()`，剧本与手写版一致（查退款政策失败 → 重规划 → 汇总）。
- **Reflexion（Actor/Evaluator/Self-Reflection 三环）**：
  `START → actor → evaluator ──(条件边)──▶ 通过或达上限 → END，失败 → self_reflection → actor（记忆环）`。
  建模要点：Evaluator 是程序化环境校验（`Predicate<String>`）而非 LLM 调用，做成纯 Java 节点后
  同样进入执行轨迹与 Checkpoint；语言反思写入 `Channels.appender` 的 reflections 通道，
  actor 每轮读取全部历史反思 —— 「口头强化学习」的情景记忆就是图上的一个追加通道。
  运行入口 `ReflexionGraph.runScenario()`，剧本与手写版一致（17+25 答错 52 → 反思进位 → 42 通过）。
- **REWOO**：`START → planner → worker ⇄(条件边) → solver → END`。
  变量传递 = `variables` base 通道（手写版 `vars` Map，worker 每轮覆盖）；
  证据累积 = `evidence` appender 通道。环内只有 worker（纯 Java）没有 LLM ——
  图上直接看出 REWOO 省 token 的本质。全程仅 2 次 LLM 调用（planner + solver）。
  运行入口 `ReWooGraph.runScenario()`（Google 创立年份 + 距今年数）。
- **LLMCompiler（并行 Function Calling）**：DAG 是 planner 运行时才产出的动态结构，
  而 StateGraph 的节点/边是编译期静态的，无法按 plan 动态加节点；诚实的翻译是保留
  **scheduler 节点**：节点内用 `CompletableFuture` 并行执行当前就绪波次（并行度不减），
  条件边按完成数自环推进下一波，全部完成 → `joiner → END`。
  运行入口 `LlmCompilerGraph.runScenario()`（张译/吴京并行搜索 → 求年龄差，第一波 2 任务并发）。
- **LATS（Language Agent Tree Search）**：搜索树是**运行时逐层分叉**的动态结构，
  与 LLMCompiler 同理无法按树动态加节点；翻译为 `search` 节点完成一整轮迭代
  （expand→execute→evaluate→descend 在一次节点调用内完成，候选分支以 List 展开），
  树的形态数据（`TreeNode` 列表 + `currentId`）存 State 的 base 通道。死路回溯体现为：
  search 把 `currentId` 改写为父节点 id → 条件边 → `reflect` 节点（语言反思写入
  `reflections` appender 通道并注入后续 expand 提示词作警示）→ 无条件边回 search 重扩。
  图结构：`START → search ── finish → END / deadend → reflect → search / continue → search 自环`。
  运行入口 `LatsGraph.runScenario()`（剧本：虚拟线程两轮扩展收敛 Finish，与手写版同剧本）。
  图版还要求 State 值可序列化（`MemorySaver` 写 Checkpoint 会深度序列化）——
  `TreeNode` 实现了 `Serializable`，channel 默认值不允许 null（用 -1 做「根」哨兵）。
- **Self-Discover（自我发现）**：阶段是**静态流水线**，一对一忠实翻译：
  `START → selector → adaptor → implementor ──(条件边)── 未走完：自环 / 走完 → finalizer → END`。
  唯一运行期才能确定的是 Adaptor 产出的**步骤数**，故 implementor 每次节点调用只执行一步、
  `stepIndex` base 通道推进游标（与 Plan & Solve 的 executor 同构）；
  步骤结果累积在 `stepResults` appender 通道，作为后续步骤与 finalizer 的上下文。
  运行入口 `SelfDiscoverGraph.runScenario()`（剧本：苹果题，选模块 → 2 步 JSON 计划 → 汇总 27 个）。
- **STORM（维基式长文生成）**：先调研后成文，两层循环的翻译方式不同——
  「逐章」是运行期量，翻译为 `researcher ⇄ section_writer` **两节点环**（`sectionIndex`
  游标 + 条件边），同 Self-Discover / Plan & Solve 的游标自环；
  「每章 3 个视角」是**静态固定**的，收在 researcher 节点内部以 List 展开
  （不画成节点，否则每章要重复三条平行边）。
  `qas` base 通道每章覆盖（不串素材），`article` appender 通道跨章累积正文，
  polisher 读全量章节拼 `# 主题 + 引言 + 正文 + 结论` 终稿：
  `START → outline_generator → researcher ⇄ section_writer(条件边) → polisher → END`。
  运行入口 `StormGraph.runScenario()`（剧本：虚拟线程 3 章 × 3 视角，15 次 LLM 调用成稿）。
- **Multi-Agent（多智能体协作，系列补充）**：唯一一个**图优先**的模式——协作拓扑
  本身就是图，无需「while 循环 → 环」的翻译。角色 = worker 静态节点（闭包捕获
  职责与专属工具），派单/收工 = supervisor 节点 + 条件边（`NEXT: 角色名` 或
  `FINISH: 答案`，pathMap 含每个角色名），调度环 = worker → supervisor 无条件边，
  共享对话记录 = `transcript` appender 通道（黑板，全员可读）：
  `START → supervisor ──(条件边)──▶ 各 worker ──┐ →（回 supervisor）│ └─ FINISH → END`。
  真实运行注意：FINISH 的最终答案是多行 Markdown，协议正则必须开 `(?s)`。
  运行入口 `MultiAgentGraph.runScenario()`（剧本：Researcher 查年份 → Calculator 算年数 → FINISH）。

## 概念对照：Python LangGraph → langgraph4j

| 文章概念（Python） | langgraph4j（Java） |
|---|---|
| `StateGraph` | `new StateGraph<>(SCHEMA, AgentState::new)` + `addNode/addEdge/addConditionalEdges` |
| `Annotated[..., operator.add]` 追加合并 | `Channels.appender(ArrayList::new)`（AppenderChannel） |
| 普通字段默认覆盖 | `Channels.base(() -> 默认值)` |
| Node：返回待更新字段的 dict | `NodeAction<S>`：返回 `Map<String, Object>`，经 `AsyncNodeAction.node_async(...)` 包装 |
| 普通边 | `addEdge("tool_node", "llm_think_node")`（工具→LLM 的环就是手写 ReAct 的 while 循环） |
| 条件边 + 路由函数 | `addConditionalEdges(source, edge_async(route), pathMap)` |
| `__end__` | `StateGraph.END`（注意值是 `"__END__"` 大写，pathMap 的键要精确匹配） |
| `graph.invoke(initial_state)` | `compiledGraph.invoke(GraphInput.args(Map), RunnableConfig)` |
| `MemorySaver` + checkpointer | `CompileConfig.builder().checkpointSaver(new MemorySaver())` |
| 中断 / 人工介入 / 恢复 | `.interruptBefore("tool_node")` + `invoke(GraphInput.resume(), config)` |
| 多线程（会话隔离） | `RunnableConfig.builder().threadId("...")` |

## 演示内容（ArticleScenario）

1. **概念 1-4 State / Node / Edge / Compile**：用状态图重写 ReAct——
   `llm_think_node`（生成 Thought+Action）⇄ `tool_node`（执行 Calculator），
   条件边路由函数判断 `Finish` / 步数上限 / 去工具节点；
   `invoke` 返回最终 State，可见 messages 逐步追加（不会覆盖历史）。
2. **概念 5 Checkpoint**：`MemorySaver` + `interruptBefore("tool_node")`——
   LLM 决定调工具后**在工具执行前中断**（模拟敏感操作人工审批），快照已存内存；
   `GraphInput.resume()` 恢复执行得到答案；同一线程第二轮提问会再次中断、再次恢复。

## 踩坑记录（与文章「常见踩坑点」一一对应）

- **消息被覆盖**：langgraph4j 中 `messages` 必须声明为 `Channels.appender`，
  用 `Channels.base` 会被整体覆盖 —— 等价于文章说的「忘加 `operator.add`」。
- **pathMap 不匹配**：`StateGraph.END` 的值是 `"__END__"`（大写），
  路由返回值与 pathMap 键必须精确一致，否则报 `cannot find edge mapping`。
- **节点只返回要更新的字段**，不要返回全量 State。
- **JVM 不退出**：langgraph4j 内部的异步执行器是非 daemon 线程，
  命令行演示需 `System.exit(0)`（Web 应用无需关心）。
- **stream 的 forEachAsync**：1.9.x 中图执行到 END 后生成器不自动关闭，
  教学演示建议直接用 `invoke`（同步返回 `Optional<State>`）。
- **channel 默认值不允许 null**：`Channels.base(() -> null)` 会在建初始 State 时
  `Collectors.toMap` 抛 NPE——可空语义用哨兵值（如 -1 表示「根」）。
- **State 值必须可序列化**：挂了 `MemorySaver` 后每个节点结束都会把 State 深度序列化
  写 Checkpoint（`Serializer.cloneObject`），自定义类型要实现 `Serializable`，
  否则运行时才抛 `NotSerializableException`。
- **图的步数有默认护栏**：langgraph4j 默认 `recursionLimit(25)`，图执行超过 25 步抛
  `Maximum number of iterations reached`。循环多的模式（如 STORM 逐章环，步数 =
  1 + 2×章节数 + 1）要 `CompileConfig.builder().recursionLimit(...)` 放宽，
  或在图内对运行期量（章节数/步数）设上限。
- **`value(key, default)` 双参重载已 `@Deprecated(forRemoval=true)`**（1.9.6）：迁移为
  `value(key).orElse(default)`——单参 `value(String)` 返回 `Optional<T>` 不受影响。
  注意 `return value("k").orElse(0)` 这类语句类型推断会得到 `Object`，需显式类型见证
  `this.<Integer>value("k").orElse(0)`（String / Boolean 同理）。
- **`invoke(Map, RunnableConfig)` 同样 `@Deprecated(forRemoval=true)`**（1.9.6）：
  迁移为 `invoke(GraphInput.args(map), config)`（`GraphInput.resume()` 等恢复路径
  不受影响）。注意 `Map.of("k", "v")` 是 `Map<String,String>`，需显化类型参数
  `GraphInput.args(Map.<String, Object>of(...))` 才能匹配 `args(Map<String, Object>)`。
- **防回归守卫**：pom 的 maven-compiler-plugin 已开 `-Xlint:all -Xlint:-path -Werror`，
  任何人再引入上述废弃 API（或任何 lint 警告）会直接编译失败。`path` 警告因第三方
  依赖树（opennlp-tools）含坏 classpath 条目而单独排除；`-source/-target` 写法会触发
  bootstrap class path 警告，故统一改用 `maven.compiler.release=17`。

## 与手写版（com.example.agent）的关系

同一栋楼的两种写法：手写版用 while/for 循环 + if-else 表达控制流；图版把循环变成
状态图的环，循环控制变成条件边，全局 messages 变成带合并策略的 State 通道，记忆变成
Checkpoint。九大模式中的 Reflection / Planning / Multi-Agent 都可以按同样方式「画成图」。

## Ch8 轨迹级评估（eval 包）

对应《AI Agents: The Definitive Guide》Ch8：BLEU/ROUGE 评不了 Agent，要看任务
完成率 + 步数 + 轨迹冗余度。`com.example.langgraph4j.eval` 四件套：

- `TrajectoryCollector`：包装 `CompiledGraph`，用 `stream(GraphInput, RunnableConfig)`
  （1.9.6 非废弃重载）逐 superstep 拉 `NodeOutput`，记录节点序列/终态/耗时/异常，
  沿 cause 链识别 `Maximum number of iterations`（recursionLimit 护栏命中）。
- `CountingLlm`：LlmClient 计数包装器，统计 LLM 调用数（成本代理指标）。
- `EvalHarness`：同任务跑 k 次（每次重新编译图 + 独立 threadId），产出
  Pass@k、步数平均/极值、平均 LLM 调用、冗余度（步数/去重节点数）。
  2026-11-01 新增 `evaluateParallel`：k 次 trial 提交给调用方给的
  ExecutorService 并行开跑——每 trial 仍独立编译图 + 独立 threadId，并行只
  改墙钟不改 Pass@k 语义；线程内异常兜成 ERROR 轨迹不拖垮报告；k 次共享
  稀缺 LLM 时在外层套 `RateLimitedLlm` 并发闸（realeval 已接
  `RetryingLlm(RateLimitedLlm(real, 4, 200))`）。
- `EvalScenario`（`mvn exec:java@eval`）：场景 A 用顺利/坎坷两套 ScriptedLlm 剧本
  跑 ReflectionGraph（Pass@5=3/5）；场景 B 把 recursionLimit 压到 5 让五步计划
  必撞护栏（Pass@2=0/2，ERROR 轨迹含护栏命中标记）。
- `RealEvalScenario`（`mvn exec:java@realeval`，支持 `[k] [startIndex]` 分片参数）：
  ReflectionGraph × JdkHttpLlm 真实评估，前后跑了三轮，每一轮都有新发现：

  1. **第一轮（原始提示词）Pass@5 = 1/5**。trial 1 首轮通过（2 次调用 / 10s），
     其余 4 次都顶满 maxRounds=3（6 次调用 / 46–123s）。剧本演不出来的真实
     故障暴露了：k2-0905-preview 在 generator 的「修改稿」分支会退化成只回
     「**修改后的作品：**」一个标题、slogan 本体丢了（第 2 轮 draft 还正常，
     第 3 轮塌成纯标题），reflector 只能对着空稿干挑刺。单看最终文本分不出
     「改了三稿越来越好」和「改了三稿输出塌了」，得看轨迹。
  2. **第二轮（generator 加「只输出作品全文，不要标题/引号/解释」）Pass@5 =
     3/5**。塌稿问题没了，但剩下 2 次失败其实是**死规则误判**：「低温静候，
     慢萃出鲜」两个意思都在，只是没有连着写出「低温慢萃」四个字，被
     contains 判定误杀；另一次定稿明明达标，只是严格 reflector 始终不说
     ACCEPT、顶满轮数被流程门槛连坐。步数多少只该报告、不该卡门槛。
  3. **第三轮（LLM 裁判）Pass@5 = 5/5**。请模型按意思当裁判（「有没有说清
     低温萃取 + 花时间慢萃，换个说法也算」），不数字面子串；步数不再卡门槛。
     5 次全过，平均 2.8 次 LLM 调用 / 34s。

  三轮下来把书里 Ch8/Ch9 串起来了：轨迹能发现输出退化（第一轮），
  但 Pass@k 准不准，最终看裁判靠不靠谱（第二、三轮）。

  **第四轮（裁判进图）Pass@5 = 5/5**。第三轮起裁判还是跑完再请的外援，
  这轮把 judge 做成了 ReflectionGraph 里的一个节点，顶满 maxRounds 时
  条件边不再直接交稿，routed 到 judge 终裁，结果写进 verdict 通道
  （ACCEPT / JUDGE_PASS / JUDGE_FAIL），Pass@k 直接读图内终裁——
  「谁来定稿」是图结构的一部分。judge 与 generator/reflector 共用
  CountingLlm 即可，裁判成本自然计入本 trial。

  **第五轮（judge 双职责）Pass@5 = 5/5**。judge 现在管两件事：reflector
  说 ACCEPT 时先复核再放行（防太好说话，verdict=ACCEPT 表示复核确认，
  JUDGE_REJECT 表示打回、还有轮数就回 generator）；顶满轮数时终裁兜底
  （防太严误杀）。`JudgeScenario`（`mvn exec:java@judge`）三个案例：好稿遇
  严格 reflector → 终裁捞回（JUDGE_PASS）；跑题稿 → 终裁不放行
  （JUDGE_FAIL）；跑题稿遇老好人 reflector → 复核打回、顶满轮数
  （JUDGE_REJECT）。复核让每次 ACCEPT 多 1 次调用（trial 均值 2.8 → 5.0
  次），这是把「放行权」从 reflector 手里收回来的代价。

- `FoldEvalScenario`（`mvn exec:java@foldeval`，2026-11-01）：折叠对照评估——
  Anthropic「什么时候把 subagent 折回主 loop」的评估驱动落地。同一组
  Golden Examples 跑两版架构各 2 遍：架构 A 用 multiagent 包的
  SupervisorPattern.dispatch（主管分派 + 专家 + 综合），架构 B 是约 20 行
  mini ReAct loop（Skill 文本常驻提示词 + 工具表）。结果照抄原文的客服失败
  案例：订单题 B 版第 1 遍查到订单就自己答了（漏调 query_shipping，丢了
  「周四送达」），1/2 过；A 版 2/2 全过。简单题 B 版无损且平均调用严格更少
  （退款/发票 1 < 3，订单 2 < 4）。结论不是「B 更好」，而是原文原话：
  不能只看平均性能和成本，合并前评估集必须覆盖被改写的调用链，
  从过变挂的题要么保留 subagent、要么把约束写进 Skill 并补评估。

- `RealFoldEvalScenario`（`mvn exec:java@realfoldeval [每阶段遍数]`，2026-11-01，
  需 LLM_API_KEY）：剧本那道订单题换 kimi-k2 真实跑折叠 loop，两阶段
  对照——原版 Skill vs 加固版（「不调 query_shipping 直接回答视为违规」）。
  真实跑出一个剧本给不了的教训：**prompt 里没写协议说明时，模型 6/6
  遍全是自然语言闲聊、一轮 Action 都不吐**——不是漏调工具，是根本不知道
  有工具可调；补上「可用工具 + 每轮输出一行 Action」的协议说明后，
  模型 6/6 遍全走对（query_order → query_shipping → Finish），原版软
  提醒就够，没复现从过变挂。行为结论只报告不硬断言（拿实验结果当门槛
  等于自己给自己判卷），硬断言只盯装置：轮数内收口、调用有账。
  现已接入 `ProtocolProbe` 预检（`harness.probe`）：跑 2×runs 遍真实
  实验前先花 1 次调用确认模型在原版 Skill prompt 下会吐 Action，
  装置没搭对直接 fail fast。

## 对抗探针（AdversarialProbe）

来自 AWS Bedrock AgentCore 平台文（2026-10）的「对抗双胞胎」做法：
正向评估只证明「该成的成」，探针证明「该拒的拒」——该拒没拒，
门禁就是摆设。`AdversarialProbe` = 一次必失败的运行 + 断言失败方式对得上
（`mvn exec:java@eval` 场景 C，3/3 通过）：

- 步数护栏：5 步计划 + `recursionLimit(4)` 必须撞
  `Maximum number of iterations`；
- judge 复核：跑题稿 + 老好人 reflector(ACCEPT) + 剧本 judge(NO) 必须
  以 `JUDGE_REJECT` 收场（judge 是剧本 LLM，离线可跑）；
- 非法输入：planner 输出无编号文本必须报「无法从 LLM 输出解析计划」。

反向验证过：1 步计划 + 充足护栏正常跑完时，探针如实报「没拒!」，
不会把成功误判成被拒。

**judge 打回理由回流**：judge 回 NO 时格式为「NO：原因」，理由写进 critique
通道——generator 下一轮按裁判的批语改，而不是按 reflector 那句客气的
ACCEPT 瞎改（Addy Osmani「loop engineering」的验证守则：不过就带反馈
从头再来，不许无反馈重试）。真实裁判的打回理由很具体（JudgeScenario case 3：
「写的是热气腾腾……与冷萃定位直接矛盾」）。

## Harness 治理层（harness 包）

生产级 Agent 的控制面，原语版和图版共用（依赖方向：harness ← agentloop / patterns）：

- `Budget`：任务预算（调用数 / token / 估算成本），计量器 wiring 传入
  （典型 `CountingLlm::calls`）。耗尽走显式终态「预算耗尽，转人工」
  （`LoopResult.stopReason` / 图版 verdict 通道 `BUDGET_EXHAUSTED`），
  按轮结算、一轮内可能微量超出。这是治理层的油表——不看轮数看烧钱。
- `RunRecord` + `RunStore`：运行档案。原语版和图版落成同一张记录
  （runId / runner / 任务 / **终态** / 逐步轨迹 / LLM 调用数 / 耗时），
  JSONL 追加落盘（`runs/runs.jsonl`），`readAll` / `find(runId)` 读回。
  终态显式：SUCCESS / EXHAUSTED / ENV_ABORTED / BUDGET_EXHAUSTED，
  图版记 verdict 通道值（ACCEPT / JUDGE_* / MAX_ROUNDS / ERROR）；
  多 Agent 版经 `RunJournal` 落同一格式，runner 分拓扑
  （multiagent:*），终态多一档 GUARD_BLOCKED（护栏拦截是设计内收场）。
  四种编排载体（原语 loop / 状态图 / 动态工作流 / 多 Agent 拓扑）
  的运行同一张底账，`mvn exec:java@archive` 的跨载体汇总按 runner
  分终态分布与平均成本。档案答「发生了什么」，图版 checkpoint 答
  「现场长什么样」，两者互补。
- `RunArchiveScenario`（`mvn exec:java@archive`）：7 次运行跑出全部终态 →
  落盘 → 读回审计汇总（按终态分组：次数 / 调用数 / 平均耗时）→
  **跨载体汇总**（按 runner 分组：agentloop / reflection-graph / workflow
  各自的终态分布、平均调用、平均耗时，选型对比有据可查）→
  按 runId 回放单条轨迹。其中两条 workflow 记录由动态工作流包
  现场生成（分类并执行 DONE、预算耗尽 BUDGET_EXHAUSTED），
  审计底账成立的演示。
- `ContextWindow` / `CachingLlm`（`harness.context` 包，2026-10-31，教程第 5 章）：
  上下文管理与成本控制。`ContextWindow`：带 token 预算的对话历史——用量记账
  （入窗/输出累计）、逐步裁剪（超预算从最老的非受保护消息开始逐条丢，
  system 与最近两条不动，**丢了什么逐条进裁剪日志可审计**）、输出上限
  （超出截断记账）、**摘要压缩** `compactWith`（最老一批消息交给模型压成
  摘要钉回窗口，细节让位给要点——裁剪之外的第二档）。`CachingLlm`：透明
  响应缓存——TTL 过期、LRU 容量、按谓词批量失效，过期/淘汰单独记账。
  场景（`mvn exec:java@context`）：逐步裁剪日志、摘要压缩前后对照、
  同题问三遍只调一次、缓存过期/挤淘汰/谓词作废。
- `AskPolicy` / `StructuredQuestion` / `StreamRenderer`（`harness.interaction`
  包，2026-10-31，教程第 8/9 章）：用户交互。动作先过策略三态
  （AUTO 直通 / ASK 发带选项的结构化题 / FORBID 拒，规则按序匹配、
  不认识默认 ASK——cautious，同 ApprovalPolicy 取值）；`Interviewer` 抽象
  提问渠道（CLI/Web/审批流换皮，剧本实现 ScriptedInterviewer），
  答案必须 resolve 过选项校验，不允许自由发挥；`CliRenderer` 流式渲染
  （块到块上屏，close 收尾复位）。`WebDashboard`：事件总线的浏览器皮肤——
  内嵌 HttpServer（JDK 自带，零依赖）订阅事件进环形缓冲，页面每秒拉
  /events 渲染；与 CLI 渲染器是平权订阅者，加皮肤不动 agent 代码；
  用完即停不留活进程。`TodoList` + `SearchPlanner`（`harness.planning`）：
  任务清单（勾不完的 allDone 不算完——反懈怠锚点，渲染进提示词）与
  搜索策略（按查询形态定工具顺序：带路径先 cat、关键词先 grep、看结构先 ls）。
  场景（`mvn exec:java@interaction`）：查物流 AUTO、导出数据 ASK
  （人选脱敏样本）、删库 FORBID；流式渲染；清单反懈怠与三种查询的
  工具排序；Web 面板起服务、HTTP 自证、停服务。
- `Skill` / `SkillRegistry` / `EventBus`（`harness.skills` 包，2026-10-31，
  教程第 10 章）：技能与扩展机制。技能 = 触发信号 + 工具组 + 提示词用法说明，
  注册不等于加载——请求命中信号才 `load` 进系统提示词（工具表随任务长出来）；
  事件总线解耦发布方与订阅者（审计日志、指标计数、渲染各订各的，
  主循环只 publish）。场景（`mvn exec:java@skills`）：按需加载「订单查询」，
  工具调用广播 ToolCalled，发布计数收尾。
- `Sandbox`（`harness.sandbox` 包，2026-10-31）：执行环境抽象——工具循环里
  「执行」分两类：调 LLM（`LlmClient` 已抽象）和动真实环境（跑命令、改文件），
  沙箱补后者这层。`Sandbox` 接口统一 `exec`（退出码 + 双通道输出 + 耗时），
  生命周期 `create/destroy` + 钩子 `beforeExec/afterExec`（拦截、审计都挂边界，
  同 ToolGate「副作用不会发生」语义）+ 快照挂点 `snapshot/restore`。
  `MemorySandbox`：内存假环境（小型文件系统 + cat/ls/grep/echo/pwd/rm
  解释器），离线确定性，快照恢复用深拷贝——**命令历史不入快照，
  闯祸记录不随回滚消失**（审计底账不回滚）。
  `LocalSandbox`：真实命令执行（bash -c、一次一进程、超时强杀），
  构造传入 deny 谓词在进程创建前拦危险命令；**磁盘快照已补齐**
  （整树拷贝到 `runs/snapshots/<id>/<名字>`，跳过 target/.git 等产物，
  restore 原样拷回；云端后端是 `Sandbox` 接口的换实现挂点，不假装做了）。
  场景（`mvn exec:java@sandbox`）：剧本模型探查内存代码库 → rm -rf 前
  打快照 → 删完回滚文件原样回来；本地沙箱真实执行无害命令展示非零退出
  与 stderr，`rm -rf /` 被 deny 谓词拦下、进程未创建。
- `PathGuard`（`harness.sandbox` 包，2026-10-31，Definitive Guide Ch6）：
  路径验证流水线——相对路径先对工作根解析再规范化（障眼法
  `work/../secret` 与 `secret` 同物，字符串匹配拦不住）→ 白名单前缀
  → 运行时存在性校验（inode/TOCTOU 挂点留在这条）。fail-closed，
  每层拒绝带层与理由可复盘——`../../etc/passwd` 在进文件系统前被拦。
  场景（`mvn exec:java@pathguard`）：五种请求 2 放 3 拦。
- `ToolGate`：工具权限网关。工具按 {@code Level}（READ/WRITE/ADMIN）声明
  权限，网关按当前用户角色在执行前校验——越权调用<b>在进工具之前</b>被拦，
  副作用不会发生；DENIED 消息回到 agent 上下文（可改提建议降级完成），
  同时经 Auditor 回调留痕（谁/什么工具/需要什么/实际角色）。
  对应 LangChain Deep Agents「Skill 选工具组、解析函数按用户权限收紧」。
  场景（`mvn exec:java@gate`）：经理全程畅通；专员更新预测被拦、
  经理调 ADMIN 导出也被拦——两次越权都留痕进轨迹和 JSONL 档案。
- `ApprovalPolicy`：审批策略。动作按风险分级（AUTO 直通 / NEEDS_APPROVAL
  挂起等人工 / FORBIDDEN 直接拒），策略表声明式构建、像代码一样评审版本化，
  不认识的动作默认要审批（cautious，宁挂起不错放）。与 ToolGate 分工：
  策略答「要不要人批」，网关答「这人够不够格」——批准是一事一议授权。
  图版接线：策略做条件边分流，挂起节点由编译期 `interruptBefore` 布点，
  挂起后 `GraphInput.resume()` 恢复，checkpoint 存中断点可回滚。
  场景（`mvn exec:java@approval`）：专员更新预测 → 挂起 → 模拟批准 →
  提升权限执行（APPROVED_DONE）；全量导出 → FORBIDDEN 直接终态；
  只查 CRM → AUTO 直通（AUTO_DONE）。
- `RuleDistiller`：外循环最小版——从运行档案蒸馏规则草稿（对应 Meta
  「重复两次写进永久规则」+ Warp 改进 Skill）。阈值 ≥2 次；只依赖档案本身
  （扫 step 里的 DENIED 文本 + 按终态聚合 + 多 Agent 的 GUARD_BLOCKED
  护栏步按「规则类型|runner」聚类，cycle/max-depth/max-handoffs 各给
  修法草稿，GUARD_BLOCKED 不进笼统计数避免重复）；产出带证据的草稿写入
  `rules/AGENTS.draft.md`，提案制：人审后手动合并进 AGENTS.md 才生效，
  采纳 ≠ 生效。场景（`mvn exec:java@distill`）：权限缺口撞墙 3 次 +
  预算耗尽 2 次 + 循环交接被掐 2 次 + 超深委派被拦 2 次 → 四类草稿；
  正常达标运行不被误伤。蒸馏后与 AGENTS.md 既有规则去重（按类目探测词，
  命中标【已收录】人审跳过——同一教训别在草稿和深坑里存两份）；末尾走
  检验闭环：照 cycle 草稿把互踢皮球的两位专员合并成一位，重跑同一任务，
  新档案 SUCCESS 不再 GUARD_BLOCKED——采纳 → 生效 → 回同类任务验证，
  蒸馏 → 去重 → 采纳 → 检验，四步走通。真实版
  `RuleDistillRealScenario`（`mvn exec:java@distillreal`，需 LLM_API_KEY）：
  紧护栏（max-handoffs=1）下真实模型接力真实撞出 GUARD_BLOCKED ×2，
  连同底账里 real- 前缀的真实运行一起蒸馏——真实撞的护栏同样蒸得出
  规则（交接次数反复到顶），落 rules/AGENTS.real-draft.md，硬断言挂退出码。
- `RunReportScenario`（`mvn exec:java@report`，2026-11-01）：底账只进不出的
  局面到此打破——终态分布（带百分比条）、按 runner 的平均调用/步数/耗时
  与终态分布、护栏专项（GUARD_BLOCKED 占比 + 按规则类型聚类「拦在哪」）、
  失败模式 TOP（runner×终态，蒸馏的量化输入；SUCCESS/DONE/ACCEPT 等正常
  收口方言不算失败——与 RuleDistiller 同一张 GOOD_TERMINALS 表）、按天
  落账。只读不改进档案，底账为空给提示收工（不造假数据）；各分组计数对
  总账的一致性断言挂退出码。
- `OuterLoopScenario`（`mvn exec:java@outerloop`，2026-11-01）：一键外循环——
  「真账进 → 草稿出」一条命令，不再依赖人记起来挨个敲。与 `@distill`
  （剧本造历史证明机制）的分野：只吃真账——不清空不造数，全量蒸馏
  runs/runs.jsonl → 与 AGENTS.md 去重 → 落 rules/AGENTS.outer-draft.md
  （带时间戳覆盖式）→ 有新的循环交接类草稿才跑采纳检验（合并专员重跑，
  必须 SUCCESS）→ 打印本轮小结。`-Dexec.args=real` 先复用
  RuleDistillRealScenario.runTightRelay 跑 1 轮紧护栏真实接力造新鲜档案
  （需 LLM_API_KEY），适合接在真实回归后定期执行。顺手修了一个蒸
  馏器真 bug：终态模式过滤漏了 DONE，workflow 的正常收口被当失败蒸成
  「见来源运行逐条排查」的废草稿——GOOD_TERMINALS 表补齐。
- `FailureDiagnostician`（`harness.diagnostics` 包，2026-10-31，Definitive
  Guide Ch7）：失败归因——录制底账（RunRecord）早就在攒，归因补上：ENV_FAIL
  步 → 下游抖动（修容错层）；同任务历史成功现在失败 → Prompt 漂移（先
  diff 提示词）；同一改法理由 ≥3 次 → 模型退化（换模型/补示例/拆任务）。
  级联可同时命中，报告把病因连同证据和修法建议摆出来，不替人拍板。
  场景（`mvn exec:java@diagnose`）：三个病历各归各因。
- `Memory` / `MemoryStore` / `MemoryConsolidator`（`harness.memory` 包，
  2026-10-31，十二部件 #3 + 智能体AI 记忆系统）：持久记忆层。记忆带
  作用域（USER/WORKSPACE/PROJECT）+ 所有者 + 来源 + <b>置信度 + 状态
  （ACTIVE/待确认/已被替代/已过期）+ 有效期 + 权限</b>——检索默认只出有效且
  权限够的，被替代的留库备查不进结果。检索两条路：开场自动注入与中途
  主动 search（同一个关键词打分器，中文自动拆 2-gram）。写回是四个操作：
  ADD（新事实）/ MERGE（同主题补充并成更完整的记录）/ SUPERSEDE（旧状态
  标记被替代、历史保留）/ SKIP（重复不动）——不是只有 add。
  场景（`mvn exec:java@memory`）：两层检索 + 一轮整理。
- `MemoryTrigger` / `KnowledgeBase` / `RecallPipeline` / `MemoryMetrics`
  （`harness.memory` 包，2026-10-31，智能体AI「七模块 16 步」）：记忆系统
  主干。触发器先裁决该查不该查（「继续上次」放行、「1+1 等于几」短路）；
  三路召回（短期对话 / 长期记忆 / 外部知识，按任务动态选）后五维融合
  （相关性 × 置信度 × 时效 + 重要性抬升），冲突对里旧的让位、理由留
  notes 可审计，不硬合并；指标记触发率/三路命中/注入条数。
  清理周期在岗：每 8 次召回先跑一轮 MemoryJanitor.sweep 再查，
  到期记忆不占检索位，遗忘不靠人想起才跑；清理计数进指标。
  真实闭环场景（`mvn exec:java@memoryreal -Dexec.args="[1-3]"`，需
  LLM_API_KEY）：真实模型带着召回记忆写评审邀请函；从对话提取事实
  并自评置信——推测项（实测模型自己给 0.4）被闸门挂成 UNCONFIRMED；
  8 次召回自动 sweep 后，过期记忆不进注入上下文。
- `Sensor` / `Finding` / `SensorSuite` / `SensorLedger`（`harness.sensor` 包
  + `agentloop.SensorVerifier`，2026-10-31，Fowler/ThoughtWorks Harness
  工程）：反馈层立起来，与前馈对称。Sensor 接口把 kind 标在类型上
  （COMPUTATIONAL 毫秒级每次跑 / INFERENTIAL 贵且不确定抽样跑）；
  Finding = 规则+严重度+位置+问题+**修正指引**（自带修正指引的信号 =
  正向 prompt injection）+ guides 撞车标注。计算型四件套：占位符残留 /
  改动行数红线（「对 300 行函数的审美厌恶」外部化）/ 重复模式粗检 /
  `FitnessSensor`（架构适应度前馈为可执行检查：性能红线、可观测性约定、
  API 契约）；推断型 `LlmReviewSensor` 语义评审（语义重复/过度设计/
  暴力修复）。`SensorSuite` quality left 分层：PRE_COMMIT 每次跑 /
  INTEGRATION 抽样跑。`SensorVerifier` 装饰任意 Verifier 零侵入接进
  AgentLoop——不达标才跑 sensor，发现以结构化块回流下一轮理由。
  `SensorLedger` 转向循环原料：规则触发计数，反复 ≥2 次蒸馏成 guides
  草稿（复用 RuleDistiller 提案制），从未触发的 sensor 单列（质量好
  还是检测失灵，台账不替人回答）。场景（`mvn exec:java@sensor`）：
  计算型感知（含 guides 撞车）→ 推断型抽样（5 轮实跑 2 次）→ 回流闭环
  （TODO 修正指引回流、2 轮收工）→ 转向循环（3 次台账 → 2 条草稿 +
  从未触发清单）四幕。真实闭环场景（`mvn exec:java@sensorreal
  -Dexec.args="[1-3]"`，需 LLM_API_KEY）：真实模型按任务要求写下 TODO，
  sensor 拦下、修正指引回流、二稿改「待补充」收工（实测 2 轮 4 次调用）；
  推断型真实评审抓到语义重复 + 两处过度设计；真实产出逐轮攒台账，
  触发过谁、谁从没响一屏摆出。
- `HarnessTemplate` / `TetheredWorkspace` / `TemplateRegistry`
  （`harness.template` 包，2026-10-31，Fowler Harness 模板概念）：
  一捆 guides + sensors 把 agent 拴在服务拓扑上。模板 = 拓扑约定
  （guides 原料，不 negotiable）+ sensor 清单（约定前馈成检查）；
  `instantiate()` 渲染出带来源戳（workspace@template@vN）的
  TetheredWorkspace，guides 成文、sensor 套件配好撞车标注；预置三种
  企业拓扑（api-service 契约/性能/可观测、event-processor 幂等/重试、
  dashboard 查询范围/口径说明）。`TemplateRegistry.driftCheck` 接文章
  头号挑战：上游模板升版，实例化工作区还拴旧版时给出 re-instantiate
  提示，脱节不靠翻 changelog 发现。场景（`mvn exec:java@template`）：
  同一份毛病代码三拓扑各拦各的（API 4 条/事件 3 条/看板 1 条）→
  实例化渲染 → 漂移检查 → Ashby 量化（约定数 vs sensor 覆盖数：
  承诺拓扑 = 削减模型可产生空间的多样性）四幕。
  场景（`mvn exec:java@memorysystem`）：预算三条历史打架只带当前有效值，
  写回三操作，指标收尾。
- `MemoryJanitor` / 写入闸门 / `MemoryBench`（`harness.memory` 包，
  2026-10-31，LLM Memory 综述的控制面/评测主张）：遗忘与防污染补完。
  遗忘是第四个一等动作：`sweep()` 一条流程跑三种清理——到期 ACTIVE
  置 EXPIRED、被替代/过期超保留期物理清除、报告三类各动多少可审计，
  幂等可定时跑。写入闸门：置信 < 0.6（模型推断而非用户确认）的记忆以
  UNCONFIRMED 落库，检索默认不出，`confirm(id)` 转正——低置信照单全收
  等于把污染写进长期记忆。`MemoryBench` 五维离线 benchmark（保真/
  抗干扰/持久/效率/容量）+ 限容量最久未用淘汰；同分召回按创建时间取新、
  写入时钟严格递增。场景（`mvn exec:java@memorybench`）：写入闸门 →
  遗忘清理 → 五维出分三幕；评测从「支持多长」转向「过去是否真的有用」。
- `EpisodicMemory`（`harness.memory` 包，2026-10-31，Definitive Guide
  Ch10）：情境记忆——三大记忆类型里最后一块。失败档案的主导重试理由
  蒸馏成「下次先处理这个」的教训进记忆库（置信 0.85 直接 ACTIVE：轨迹
  验证过比模型推断可信），幂等可反复跑；进库后走正常召回路径，同类
  任务开场注入——档案从「等审计的底账」变成「会被想起的经验」。
  场景（`mvn exec:java@episodic`）：两条失败档案 → 1 条教训 → 再蒸馏
  0 新增 → 同类任务召回时教训进场。
- `Kibitzer` / `KibitzerEvent` / `MemoryReader`（`harness.memory.kibitzer`
  包，2026-11-05，OmO 研读落地）：记忆召回的常驻裁判——每主会话一个
  只读侧车，主 loop 的 prompt / 工具调用 / 工具结果截断脱敏成
  `KibitzerEvent` 后喂给它，不挡主 loop 的路。唤醒闸门：事件触到
  「还没判定过」的已存记忆才花一次裁判模型调用（便宜小模型的位置），
  没触到零消耗；一次 wake 内最多 8 次只读探查自证，裁判说值得提醒
  才把 `recalled memory: <hint>` 拍进主会话，同一条记忆判过不重判；
  事件文本进裁判 prompt 前过 `InjectionGuard`（工具结果是攻击者能
  碰的输入面，注入句整句拦截，防伪造 nudge）；裁判调用炸了退避
  不误判，记忆保持未判定等下次；上下文塞满时用
  「已投递/已拒绝」清单重播种。零写入不靠自觉靠类型——侧车的依赖
  是 `MemoryReader` 只读视图，写方法编译期就不存在。场景
  （`mvn exec:java@kibitzer`）：按闹铃醒 + 驳回与退避两幕，断言全绿。
- `CandidateFilter` / `ModelRouter`（`harness.routing` 包，2026-10-31，
  十二部件 #4/#9）：小决定不花大模型。筛选器把检索回来的候选按
  关键词重叠打分，只放过线且进 topK 的进上下文，滤掉谁带分数可解释；
  模型路由按任务信号分 FAST/BALANCED/FRONTIER 三档，每条决定带命中
  规则名。场景（`mvn exec:java@routing`）：3 候选滤成 2、删生产索引走
  frontier / 查物流走 fast / 写方案走 balanced。
- `CredentialVault`（`harness.credentials` 包，2026-10-31，十二部件 #7）：
  凭据不进模型上下文。模型手里的参数只有 `${SECRET:stripe}` 占位符，
  inject 在执行边界换真值；scan 是兜底安全网——任何要留痕的文本出现
  明文密钥即报 LeakDetected（生产接告警 + 吊销）。场景
  （`mvn exec:java@credentials`）：正常路径轨迹零密钥；配置错的工具
  把入参吐回返回值，扫描器当场抓住。
- `InjectionGuard`（`harness.security` 包，2026-10-31，Definitive
  Guide Ch12）：间接提示注入防线——工具返回的外部内容（网页/文件/
  邮件）进上下文前过指令模式库（忽略先前指令/伪系统提示/越权数据
  请求/角色劫持/指令式英文），命中句整句脱敏为〔已拦截的注入指令〕，
  全文包「只当数据引用」标记——内容可展示、指令不可执行；干净内容
  原样通过不浪费 token。与 credentials 占位符互补：占位符管输出面
  （密钥不外流），这里管输入面（指令不潜入）。场景
  （`mvn exec:java@injection`）：藏注入的邮件命中脱敏，数据句全留。
- `Capability` / `OperationIndex`（`harness.skills` 包，2026-10-31，
  十二部件 #1）：任务单元检索层。capability 按接入系统归组工具
  （aws/jira/billing），模型用 search_operations 按任务发现 operation、
  load_operation 只把命中的工具定义加载进上下文——目录按需发现，
  不是一次给全；list_capabilities 回答「你能访问什么」。已并入 skills
  场景二（`mvn exec:java@skills`）。
- `DiskCheckpointScenario`（`mvn exec:java@checkpoint`，2026-10-31，十二部件
  #11）：磁盘 checkpoint。langgraph4j 自带 FileSystemSaver 把 checkpoint
  序列化到 `runs/checkpoints/scenario/`，演示完整重启闭环——进程 1 跑到
  审批挂起点退出，进程 2（全新图实例 + 全新 saver 实例）从盘上 resume
  跑完。状态序列化用 StdStateSerializer 最简实现（Java 原生序列化，
  换 JSON 是换 serializer 的事）。
- `PendingAction`（`harness.interaction` 包，2026-10-31，十二部件 #8）：
  系统强制审批的结构化载荷——action/args/risk/依据策略，是否审批由
  系统决定不由模型决定，与模型 ask_human（补信息）是两个层级。
  WebDashboard 把它渲染成批准/拒绝按钮（/pending 轮询 + /decide 回传）。
  场景（`mvn exec:java@pending`）：退款 18000 命中高危策略 → 挂起 →
  HTTP 模拟点「批准」→ 决定回传、面板清零。
- `CodeRunner`（`harness.sandbox` 包，2026-10-31，十二部件 #5）：脚本
  批处理原语。模型写一次处理脚本，循环在沙箱里跑完，结论一次交还——
  脚本经 Sandbox.exec 进边界，策略拦截/超时/审计一个不少。场景
  （`mvn exec:java@coderunner`，需本机 python3）：5 客户 × 12 账单找
  逾期，逐条编排要 10 次 LLM 调用，写脚本只要 2 次。
- `SchemaTool` / `GovernedTool` / `ToolResolver` / `ChannelAcl` /
  `WorkspaceBootstrap`（`harness.toolbase` 包，2026-10-31，尼恩 QwenPaw
  工具底座）：工具治理四件套。SchemaTool 给工具结构化参数契约（进提示词
  的是参数表不是散文）；GovernedTool 治理壳——注册时声明权限 + 风险，
  调用自动过 ToolGate、NEEDS_APPROVAL 挂起发 PendingAction，业务工具
  零权限逻辑；ToolResolver fail-closed——未注册工具 Phase0 拒绝并留痕，
  不猜不降级；ChannelAcl 渠道准入三态（白名单直通/黑名单拒/陌生人
  pending 等管理员）；WorkspaceBootstrap 统一装配，入口再多装配不漂移。
  场景（`mvn exec:java@toolbase`）：壳拦权限、挂审批、放行三步走 +
  未注册 DENY + ACL 四连 + 两入口一致。
- `SkillWatcher`（`harness.skills` 包，2026-10-31，尼恩「技能注入」链路
  的单进程版）：技能热刷新。本地目录就是 Manager→Worker 的管道，
  文件名 → mtime+size 指纹（ETag 替身）轮询，新增/变更即注册或重载，
  进程不重启。场景（`mvn exec:java@hotreload`）：运行中推 git.skill 被
  捡起可用，改文件热重载。
- `SkillDoctorScenario`（`harness.skills` 包，2026-11-01，对标 Anthropic
  /skill-doctor）：技能库体检，三个维度——使用频率（0 加载 = 僵尸，
  <5 加载 = 低频观察）、内容重复度（promptSnippet 的 3-gram Jaccard ≥ 0.6
  = 合并候选，提示词膨胀直接吃上下文窗口）、触发撞车（trigger 互为包含 =
  加载时抢同一请求）。场景库埋了三种病灶：僵尸 sms-notify-legacy、
  与 order-ops 内容重叠的旧版 order-faq-v1、低频 invoice-issue；体检全揪出来
  且健康技能不被冤枉。场景（`mvn exec:java@skilldoctor`）。
- `ProtocolProbe`（`harness.probe` 包，2026-11-01）：协议自检探针——
  真实实验的前置预检，一次最小调用确认模型在待测 prompt 下会按协议吐
  协议行（且引用名在声明的清单里），不过就 fail fast，不拿真实额度跑
  注定产不出轨迹的实验。两层失败分开报：没吐协议行 = prompt 缺协议
  说明；名字不在表 = 模型自造（探针 prompt 里没给清单时真实模型真的会
  自造专员名，multiagent 接入第一版实测命中）。`check` 返回结果打报告，
  `require` 硬闸门抛异常，`requireOrExit` 是场景 wiring 专用（挂则 exit 1）；
  支持自定义协议行 pattern（multiagent 的「分派：专员 | 任务」），
  `rosterProtocol(names)` 生成分派协议 prompt（专员名单必须写进 prompt）。
  已接入：realfoldeval（Action 协议）、real（ReAct 场景）、realadvanced
  （HITL + 混合调度两段；客户端层 $web_search 无 Action 协议，刻意不接）、
  supervisorreal 场景一、topologyreal 场景二（分派协议）。
  场景（`mvn exec:java@protocolprobe`）三幕：剧本通过放行、剧本闲聊
  拦截（探针之外 0 调用）、真实双探（裸 prompt 挂 / 带协议过，kimi-k2
  实测复现）。
- `RateLimitedLlm`（`langgraph4j.llm` 包，2026-11-01，生产化缺口 #6）：
  限流与背压——真实 LLM 是稀缺资源（额度 + 网关配额），并发一上量
  （dispatchParallel 并行 fan-out、批量评估 k 次 trial）必须先有限流。
  两道闸：<b>并发上限</b>（信号量，超出的调用方原地排队——背压是压回去
  不是压垮）+ <b>最小调用间隔</b>（两次发起的最小间距，防突发打满配额）。
  与 RetryingLlm 正交，推荐套法 `RetryingLlm(RateLimitedLlm(real))`——
  限流在内重试在外，重试风暴冲不破并发闸。HTTP 侧 JdkHttpLlm 改为全 JVM
  共享一个 HttpClient（连接复用，不再每实例一池；WebSearchLlm 早就是共享）。
  场景（`scripts/dev.sh ratelimit`）两幕剧本：6 线程打上限 2 的并发闸
  （峰值恰好 2、排队不丢请求）+ 100ms 间隔闸（5 次 ≥400ms、对照组 0ms）。
- **可观测性三件套**（`harness.telemetry` 包，2026-11-01，生产化缺口 #5）：
  底账（RunStore）答「这一次运行发生了什么」，指标和 trace 答「这一批
  运行整体什么样、时间花在哪」——三者同哲学：一行一条 JSONL，人可直接看，
  机器直接抓。`MetricsRegistry`：counter/timer/gauge 三类线程安全，
  对标 Micrometer 最小子集但零依赖，`exportPrometheus()` 出 Prometheus
  文本展示格式（textfile collector 直接抓，不建 pushgateway 也有图看）；
  `TelemetryLlm`：LlmClient 装饰器——调用/失败/耗时/prompt+output 字符量
  （无 token 用量时的成本代理量）全自动攒，按 label 分维度，在飞调用数
  +1/-1 配对收尾归零；`Tracer`：span 树（traceId/spanId/parentId）+ 相对
  时间戳事件日志，`exportJsonl` 带跨进程锁追加导出，traceId 一把捞回整条
  链下钻排障，接 OTLP 只换导出端。场景（`scripts/dev.sh telemetry`）三幕：
  装饰器计数与 CountingLlm 一个不差（5/5）、Prometheus 导出标签/计时器/
  gauge 14 行、span 树 1 根 2 子父子关系与事件日志全对，12 断言全绿。
- **安全面两件套**（`harness.redteam` 包，2026-11-01，生产化缺口 #7）：
  fuzz 问「输入面 Robust 吗」，红队问「攻击面守得住吗」，两层都要定期跑。
  `PromptFuzzer`：8 类变异策略（截断多切点/字符扰动/全半角扰乱——解析器
  按 ASCII 匹配协议行时全角输入就是盲区/大小写漂移/注入前后缀拼接/重复
  放大），目标当黑盒函数全量砸，目标抛异常即 crash 并留复现样本，扫描
  结论确定性可复现（回归才有意义）；`RedTeamBattery`：标准攻击库 12 条
  （越狱/越权/注入/泄露四类），判定器双模式——`leakedSecretVerdict`
  确定性查机密串、`llmJudgeVerdict` 用 LLM 按量规判 YES/NO（真实红队
  判定必须模型做，攻击是否得手常无确定性模式可查），`guarded(target,
  guard)` 一键组脱敏管线。场景（`scripts/dev.sh redteam`）：InjectionGuard
  + 协议行解析器 fuzz 77+12 条变异零 crash；红队对打——裸目标（剧本演的
  无防线助手）突破率 67%，脱敏管线压到 33% 且设计捕获面（注入指令/越权
  索取/伪系统更新）全守住；残余盲区（DEBUG 口吻/话术套取）如实报告——
  输入面扫描不是银弹，得输出面与模型侧防线补位。
- `multiagent` 包（2026-10-31，多 Agent 四模式 + 选型决策）：四种拓扑各归
  其位——`WorkflowPattern`（控制流写死、模型只管工序）、`SupervisorPattern`
  （平级分派 + Supervisor-as-Tools 双结构：专家可以包成主管的 SchemaTool）、
  `HierarchicalPattern`（总监→组长→工程师层层下派）、`SwarmPattern`
  （去中心化接力，焦点漂移即交棒、控制权不绕回中心）；`DelegationGuard`
  委派护栏——max-depth / max-handoffs / 循环检测三重闸，每次交接前必过、
  拒绝理由说人话进 trace；`TopologyAdvisor` 选型决策树——任务画像
  （固定工序/中央协调/分层/焦点漂移/独立读源数/共享写出目标数）→ 推荐
  拓扑 + 理由，写密集直接劝退，默认立场是单 Agent 够用就不拆。
  场景（`mvn exec:java@topology`）：五幕 13 断言——决策器四种画像、
  Workflow 流水线、Supervisor 双结构、Hierarchical 正常 + 超深被拦、
  Swarm 接力 + 循环交接被掐断。见 docs/multi_agent_topology_review.md。
  每次运行经 `RunJournal` 落进 runs/runs.jsonl——runner 分拓扑
  （multiagent:supervisor 等），GUARD_BLOCKED 是与 SUCCESS 平起平坐的
  显式终态；剧本版与两个真实版的多 Agent 运行，和单体 loop / 状态图 /
  动态工作流同一张底账，跨拓扑按 runner 可分终态与成本。
  真实对照组 `TopologyRealScenario`（`mvn exec:java@topologyreal -Dexec.args="[1-6]"`，
  需 LLM_API_KEY）：六个场景——真实模型把任务描述翻译成画像信号进决策树
  （写密集前置劝退：共享写出 &gt; 0 且工序不固定，不问其他信号直接建议
  不拆）、真实模型当主管分派/当总监下派/当四位客服接力（路由跳过持有者自己，
  交回自己留给护栏循环检测兜底）、双路由对照（keywordRouter 免费但要
  接棒者说中 cue 词；modelRouter 每次交接一次调用，读语义不用背词，
  对不上花名册一律收工）。
  Supervisor 真实对照组 `SupervisorRealScenario`（`mvn exec:java@supervisorreal
  -Dexec.args="[1-3]"`，需 LLM_API_KEY）：真实主管出「分派：」行 /
  「CALL 工具：」行全部命中花名册（trace 逐条校验，协议不靠剧本），
  外加全真实矩阵（主管、专家、综合全走真实模型）的调用账
  （1 + 3 + 1 次）。
- **生产深化三件套**（2026-11-01，把多 Agent 从「机制对」推向「扛得住生产」）：
  - **并行 fan-out** `SupervisorPattern.dispatchParallel`：主管拆完任务各专家
    同时开工，墙钟从「耗时之和」降到「最慢的那个」；并发上限
    （线程池 = min(maxConcurrency, 子任务数)）是调用配额的安全阀；
    单专家抛异常隔离成一条「[调用失败]」进结果包，综合照常出稿、
    明说哪块缺了；trace 全部返回后按分派顺序统一回调（回调方不是
    线程安全的，不在 worker 线程里触它）。场景（`mvn exec:java@parallel`）：
    三幕——串行基线 ≈900ms / 并行 ≈300ms / 故障隔离；并行 run 落账
    runner `multiagent:supervisor-parallel`。另有第四幕 `real` 参数：
    真实模型主管经 `ProtocolProbe` 分派协议预检后 `dispatchParallel`
    并发分派（主管走 `RetryingLlm(RateLimitedLlm(real))` 并发闸）。
  - **整轮时间盒** `SwarmPattern.run(..., long deadlineMillis)`：接力不再
    没有尽头——死线只掐「还交不交接」，已生成的回复原样保留，首棒
    永远拿得到。场景（`mvn exec:java@swarmdeadline`）：不设盒 3 跳跑完
    vs 盒 500ms 在第 2 棒就地收工，trace 留「到点就地收工」。
  - **LLM 流式输出** `LlmClient.stream(prompt, Predicate<String> onChunk)`：
    逐块回调，返回 false 即中止；默认方法退化为 complete 一次整块
    （不发流式的实现零代码可用）；`JdkHttpLlm` 走真实 SSE，消费方中止时
    直接关响应流、没生成的 token 不计费（与 complete 完全独立的两条
    代码路径，互不动对方的转义处理）；`ScriptedLlm` 按小块切词演练同一
    条代码路径。场景：`mvn exec:java@stream`（完整流式 + 预算 24 字熔断），
    真实版 `StreamRealScenario`（`mvn exec:java@streamreal -Dexec.args="[1-2]"`，
    需 LLM_API_KEY）：SSE 逐块真实到达、30 字预算真实提前关流。
- Jackson 序列化依赖已显式声明进 pom（原本经 langgraph4j 传递引入）。

## Agent loop 原语（agentloop 包）

不依赖状态图框架的通用 loop（`mvn exec:java@loop`）：行动 → 验收 →
带反馈重试，直到达标或轮数耗尽。对应 loop engineering 的 goal 原语——
循环本体不负责聪明，负责把骨架跑严实：

- `AgentLoop`：引擎。三个函数接口随便换实现——`Actor`（干活的）、
  `Verifier`（验收的，理由回流下一轮）、`StopCondition`（可选，确定性终点：
  「测试全过」「分数 ≥ 90」这种硬指标别靠模型自觉）；maxTurns 护栏内置；
  行动/评估抛错记成一轮失败尝试，不中断循环。
- `LoopContext` / `LoopStep` / `Verdict` / `LoopResult`：现场、逐轮轨迹
  （轮次/成果/验收意见/耗时）、验收结论（done + 理由 + kind）、结果
  （completed + steps + stopReason——失败怎么失败的看 stopReason）。
- **失败分两路**（出自 Meta/Stripe 改造实践的失败分流）：成果问题
  （Verifier 判 NO / 行动抛普通异常）吃 maxTurns 修复额度、理由回流；
  环境故障（行动/验收抛 `EnvException`，如 LLM 网关超时）不占额度、
  单独计数 maxEnvFailures（默认 3），超限直接 `LoopResult.envAborted`
  终止并标明原因。`LlmActor`/`LlmVerifier` 用 `EnvException.wrapIfEnv`
  自动把 IO/超时/中断类异常翻译成环境故障。轨迹里环境故障逐步可查，
  但不回流给行动方当「改进意见」（`LoopContext.lastFeedback` 过滤）。
- **预算护栏**（harness 包 `Budget`，生产级油表）：调用数 / token /
  估算成本硬上限，计量器由 wiring 传入（典型 `CountingLlm::calls`）。
  耗尽不看 maxTurns 脸色，走第四种终态「预算耗尽，转人工」
  （`LoopResult.stopReason` / 图版 verdict 通道 `BUDGET_EXHAUSTED`）。
  按轮结算，一轮内可能微量超出。场景 4：maxTurns=10、预算 4 次
  调用，验收永远 NO → 2 轮即转人工，最后一版成果照记（审计可见）。
- `LlmActor` / `LlmVerifier`：LlmClient 版实现——行动方提示词自动带上
  历史轮次和上一轮反馈意见；验收方回 YES 或 NO：原因。
- `AgentLoopScenario`：剧本场景（验收挑两轮毛病后放行，验证反馈确实进了
  下一轮提示词）+ 真实模型场景（JdkHttpLlm 一轮达标，16s）。
- `LoopBenchmark`（`mvn exec:java@loopbench`）：同剧本正面对比图版
  （ReflectionGraph）与原语版（AgentLoop）——同一任务同一剧本，两版
  都是 2 轮 / 4 次调用，消耗打平。对比的意义在选型：要 checkpoint /
  interrupt / HITL 选图版；要轻量、嵌进现有代码选原语版。图版用
  TrajectoryCollector 采轨迹，两版都用 CountingLlm 计数。

  **真实模型对比（`loopbench graph|loop 1 N` 分片跑，各 3 trial）**：
  剧本打平，真实没打平——

  | 版本 | 轮数 | LLM 调用 | 耗时 | 达标 |
  |---|---|---|---|---|
  | 图版（无 judge） | 3 / 3 / 3 | 6 / 6 / 6 | 81s / 107s / 99s | 0/3 |
  | 图版（配 judge） | 3 / 3 / 3 | 7 / 7 / 7 | 100s / 65s / 72s | **3/3** |
  | 原语版 | 2 / 2 / 1 | 4 / 4 / 2 | 11s / 10s / 6s | 3/3 |

  差距不在「图 vs 原语」这个抽象层面，在**验收协议**：原语版的验收是对照
  显式达标条件按意思判断（换个说法也算），图版的 reflector 是开放式评审、
  靠输出字面「ACCEPT」停——k2 作为 reflector 极少吐这个字面 token，
  3 次全顶满轮数按未通过收场（就是 realeval 里 judge 终裁反复捞回的那个
  毛病，这次没配 judge 就裸奔了）。教训复述一遍：**显式达标条件 >
  开放式评审**，循环写在哪（图里还是函数里）是第二位的，停不停得下来
  看验收协议。

  补上 judge 后的复测（2026-10-29，loopbench 真实模式图版已默认带 judge）：
  3 个 trial 全部顶满 3 轮后交 judge 终裁，3/3 捞回放行（JUDGE_PASS），
  每次多 1 次 LLM 调用（终裁）、耗时多 20~30s——验收协议这层补丁把图版从
  0/3 拉到 3/3，代价是每 trial 一次的终裁调用。两版都配上靠谱验收后
  回到同一水平（realeval 配 judge 是 5/5）。

`RealEvalScenario` 末尾还有两个真实模型探针（`realeval` 跑完 k 次后自动接着跑）：
真实 judge 对跑题稿必须复核打回（剧本 generator/reflector 喂跑题稿，
judge 是真实 JdkHttpLlm）；真实全流程跑出的合格稿，reflector 放行时 judge
必须复核确认、顶满轮数时 judge 终裁该捞回（`ACCEPT` / `JUDGE_PASS`
均算探针通过——`JUDGE_REJECT` / `JUDGE_FAIL` 算「该放没放」）。两头都验，
剧本 judge 说 NO 是排好的，真实模型说 NO 才算数。

**踩坑：recursionLimit 的预算比直觉多 1。** 实测 1 步的 Plan&Solve
（START→planner→executor→finalizer→END，路径 5 个节点）需要
`recursionLimit(6)` 才能跑完，5 会拦。给护栏留预算时按
「路径节点数 + 1」算，别卡着边设。

## 动态工作流（workflow 包）

出处：claude.dev《每项任务都有一个 harness：Claude Code 中的动态工作流》
（全文浓缩在 docs/dynamic_workflows_claude.md，知乎译文
https://zhuanlan.zhihu.com/p/2097582147485824443）。核心观点：面对
运行时间长、高度并行、结构复杂或对抗性强的任务，单上下文会出三种
毛病——做到一半就宣称完成的智能体懈怠、偏爱自产的自我偏好偏差、
压缩后丢约束的目标漂移。解法是**模型运行时按眼前的任务现写 harness**：
起一批各自独立上下文、目标聚焦的子智能体并编排它们，而不是把通用
编排图预先写死。

与 `com.example.langgraph4j` 静态多智能体图的关系：静态图通用、
可复用、可 checkpoint；动态工作流为单个任务量身定制、用完即弃。
两者是选型问题，不是替代关系。

运行入口：`mvn exec:java@workflow`（剧本离线演示，生产把
ScriptedLlm 换成 JdkHttpLlm 即可）。两个文件：

- `Workflow`：引擎，对应 JS 侧的 `agent()` / `parallel()`。
  `agent(name, prompt)` 起一个子智能体——它只看到自己的提示词，
  不带主线历史，计数 spawned；`parallel(...)` 扇出按序返回结果
  （演示用普通循环，效果等价于并行，因为每个智能体上下文本来就隔离）；
  综合屏障由调用方拿到全部结果后再起新智能体完成。管道不单独建方法——
  顺序调 agent()、把上一步输出拼进下一步提示词即是管道（注释里点明）。
- `DynamicWorkflowScenario`：六个场景对应文章六种模式，每个场景一个
  独立 ScriptedLlm 队列模拟「模型现场决定起谁、起几个」（场景间
  剧本不混）：
  1. 分类并执行：分类器回「类型=文案」→ Java 路由到文案专员出稿；
  2. 扇出并综合：创意/可信度/合规三个评审员各一个干净上下文 →
     全部到齐（屏障）→ 综合器合并；
  3. 对抗式验证：撰稿人出稿 → **全新上下文的**对抗审查员按准则挑硬伤
     → 修复者改稿（不让原作者自己给自己打分）；
  4. 生成并筛选：三个点子手发散 → 筛选员按标准去重留最优；
  5. 锦标赛：四个候选两两对决，半决赛 B、D 出线，决赛 B 夺冠——
     每场比较都用全新裁判，比较式判断比绝对评分可靠；
  6. 循环直到完成：审查员每轮全新上下文审稿子，第一轮发现预算/负责人
     两处问题 → 修复 → 第二轮「无新发现」停，停止条件驱动、不预设轮数。

收尾打印子智能体总数与 LLM 调用数（演示共 23 个 / 23 次），呼应文章的
token 预算意识：动态编排不是免费的，子智能体数 ≈ token 消耗，
只给复杂且价值高的任务上多智能体。

**真实模型验证（2026-10-30，`mvn exec:java@workflow -Dexec.args="real [1-6]"`
分场景跑，Kimi k2）**：六个模式的提示词协议在真实输出下全部成立——

| 场景 | 协议要求 | 真实表现 |
|---|---|---|
| ① 分类并执行 | 分类器只回类型，路由词含「文案」 | 回「文案」，路由正确（2 调用 / 10s） |
| ② 扇出并综合 | 三评审独立打分，综合器合并 | 三份维度评审互不干扰，综合结论可执行（4 调用 / 82s） |
| ③ 对抗式验证 | 对抗者按准则挑硬伤 | 真挑出来了：「冰镇时光」语义不通、「一口入魂」无依据，修复稿只陈述事实（3 调用 / 48s） |
| ④ 生成并筛选 | 三发散 + 按标准筛选 | 协议没坏，但暴露隔离成本：提示词太省，一个点子手没接住产品名、跑题到「AI 看疹」；筛选员按标准拉回正轨（4 调用 / 44s） |
| ⑤ 锦标赛 | 裁判回『胜者=1/2』 | 三场全部按格式回，决赛判价格锚点派夺冠（7 调用 / 35s） |
| ⑥ 循环直到完成 | 审到「无新发现」停 | 真实审查员三轮都有真问题（防刷去重、候补通知、券追回），**始终不说「无新发现」**，轮数上限强制收工（6 调用 / 85s） |

三条教训：

1. **隔离上下文是双刃剑**（场景④）：子智能体看不到主线，提示词就得自带全部背景；
   提示词越省，隔离的代价越明显。
2. **停止条件要带护栏**（场景⑥）：剧本里两轮就停，真实模型真干活就停不下来——
   这正是文章「循环直到完成」的本意，轮数上限不是摆设，是生产必需品
   （对应 harness 包 Budget 的同款思路）。
3. **比较式协议比开放式可靠**（场景⑤）：裁判只回 1/2，三场全对；让模型打绝对分
   就不知道飘哪去了（同 loopbench 的教训复述）。

顺带修了 `JdkHttpLlm` 的 JSON 提取：反斜杠 u 四位十六进制转义之前被吞成
残留字符（`u003e`），现在按码点解码——所有真实场景的输出干净了。
注意 Java 源码里连注释都过不了反斜杠-u 预扫描，注释里别写这个转义。

**治理层接入（2026-10-30）**：workflow 包挂上 harness 治理层，动态编排
也要留底、也要限量：

- `Workflow(LlmClient, Budget)`：动态编排会起多少子智能体是模型现场决定的，
  比静态图更需要油表。每次 spawn 前检查预算，耗尽抛 `Workflow.BudgetExhausted`
  显式收场转人工（第四种终态，同 AgentLoop 语义；按检查点结算，一次
  complete 总会跑完）。剧本演示「模式六 + 预算护栏」：同一循环剧本
  预算只给 2 次调用，第二轮修复起不来 → BUDGET_EXHAUSTED 落盘。
- 审计留痕：每次 spawn 进 `Workflow.trace()`（名字/输出/耗时），
  `archiveRun` 包装器捕获终态（DONE / BUDGET_EXHAUSTED / ERROR）转成
  RunRecord 追加进与 archive 场景共用的 `runs/runs.jsonl`——
  原语 loop、状态图、动态工作流三种载体的运行落同一张审计底账，
  出了事都能按 runId 回答「这次运行干了什么、怎么收场的」。
  真实模式分片跑的场景也各落一条（wf-real-s1..s6）。
- 真实模式预算护栏（2026-10-30，`real 7`）：锦标赛四候选各占 1 次调用，
  预算只给 4 次——四份候选正常生成（29s），半决赛裁判 spawn 时被拦下，
  终态 BUDGET_EXHAUSTED 落盘，轨迹留痕 4 步「参赛者×4」。
  中断点正好是编排的相位切换处（生成 → 评判），这类工作流做预算规划时
  要按相位分段配额，不能只看总数。
