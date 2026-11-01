# Agent 设计模式 Java 实现系列 · 总结报告（历史文献）

日期：2026-10-21

> **定位**：本文记录项目第一阶段的成果——九大设计模式的手写版与 langgraph4j
> 状态图版。此后项目已演进为「Agent 工程实验场」：新增 agentloop 原语、
> harness 治理层、动态工作流（workflow 包）与轨迹级评估。全貌与后续主线
> 见 [README](README.md) 和 [LANGGRAPH4J.md](LANGGRAPH4J.md)，本文保留作为
> 第一阶段的完整记录。

## 1. 项目背景

两篇知乎文章的两条 Java 实现线索：

- 《Agent 的九种设计模式》：ReAct 为基础、4 个侧重反思 + 4 个侧重规划的演进分支 → 纯 JDK 手写版（`agent_design_model`）；
- 《LangGraph 核心概念系统详解（结合手写 ReAct 对比）》：State / Node / Edge / Compile / Checkpoint 五概念 → langgraph4j 图版（已合并进同一项目，包 `com.example.langgraph4j`，详见 `LANGGRAPH4J.md`）。

两条线索的关系：同一件事的两种写法。手写版用 while/for 循环 + if-else 表达控制流；图版把循环画成状态图的环、把分支画成条件边、把全局变量画成带合并策略的 State 通道。图版类注释里逐行标注了手写版每一行循环代码对应的节点/边，可逐行对照。

## 2. 项目一览

合并为单一 Maven 项目 `agent-design-patterns`（`pom.xml` 同时引入 langgraph4j-core / langgraph4j-langchain4j / langchain4j-open-ai 依赖），两个包并存：

| | com.example.agent（手写版） | com.example.langgraph4j（状态图版） |
|---|---|---|
| 写法 | 纯 JDK 手写，零第三方依赖 | org.bsc.langgraph4j:langgraph4j-core 1.9.6 |
| 覆盖 | 九大模式全部 | 文章五概念 + 九个图版（八大模式 + Multi-Agent 补充） |
| LLM 抽象 | `llm/LlmClient` + ScriptedLlm / OpenAiCompatibleLlm | 同名接口 + ScriptedLlm / KimiLlm / LangChain4jLlm / WebSearchLlm |
| 运行 | `mvn compile exec:java`（DemoRunner，9 个模式） | `exec:java@graph` / `@article` / `@real` / `@realpatterns` / `@realadvanced` |
| 离线演示 | ScriptedLlm 剧本，无需 API Key | 同左；真实模型需 `KIMI_API_KEY` |

## 3. 九大模式总览（手写版）

| # | 模式 | 核心结构 | 解决的问题 |
|---|------|---------|-----------|
| 1 | ReAct | Thought → Action → Observation 循环 | 最基础的「边想边做」，每步执行后保留观察 |
| 2 | Plan & Solve | Planner + Executor + Replanner | 先整体规划再执行；某步失败重规划剩余步骤 |
| 3 | REWOO | Planner + Worker + Solver | Observation 隐式嵌入下一步执行单元，规划一次批量执行，省 token |
| 4 | LLMCompiler | Planner(DAG) + 并行调度器 + Joiner | 识别无依赖任务并行执行，降低端到端延迟 |
| 5 | Basic Reflection | Generator + Reflector | 「学生写、老师批」式往复迭代 |
| 6 | Reflexion | Actor + Evaluator + Self-Reflection | 外部依据校验；失败生成语言反思存入情景记忆并重试 |
| 7 | LATS | 树搜索 + ReAct + Reflection | 多分支扩展、打分择优、死路反思回溯（教学版最优先搜索） |
| 8 | Self-Discover | Selector + Adaptor + Implementor | 对「任务如何推理」本身反思：自选推理模块再执行 |
| 9 | STORM | 大纲 → 多视角调研 → 分章撰写 → 润色 | 从零生成维基百科式长文 |

演进主线：`ReAct ─加规划→ REWOO ─变量链→ Plan&Solve ─DAG/并行→ LLMCompiler`；`ReAct ─加反思→ Basic Reflection ─环境评估/记忆→ Reflexion ─→ LATS`；Self-Discover 是对 task 本身的小粒度反思；STORM 是内容生成特化。

## 4. 手写循环 → 状态图的映射规律（面试级）

| 手写版 | 状态图 |
|---|---|
| 方法（planner/generate/critique/executor） | 节点（NodeAction） |
| while/for 循环 | 节点自环或两节点环（普通边连回去） |
| 循环里的 if-else 分支 | 条件边 + 路由函数（pathMap） |
| 循环变量（round/cursor/trial） | State 的 `Channels.base` 字段（节点返回覆盖） |
| 累积列表（completed/messages/reflections） | State 的 `Channels.appender` 字段（节点返回追加） |
| `return`（跳出循环） | 路由返回 `StateGraph.END` |

## 5. 真实 LLM 验证结果

模型 `kimi-k2-0905-preview`（Kimi Work agent 网关 OpenAI 兼容接口），客户端 langchain4j 1.22.0 `OpenAiChatModel`（`LangChain4jLlm`）。全部 exit=0。

### 5.1 概念 ReAct 图 + REWOO 图（RealDemo）

- **ReAct**：问「维基百科记载，Google 成立于哪一年？成立于几月几日？距今多少年了？」模型走 `Search[Google 成立日期] → Finish` 多轮循环；Search 工具真实查回中文维基条目，并定位关键事实句「1998年9月4日，Google以私营公司的形式创立」。
- **REWOO**：问「Google 成立于哪一年？距今多少年了？langgraph4j-core 最新版本是多少？」真实 planner 一次产出三步链式计划：
  ```
  #E1 = Search[Google 成立于哪一年]   → 维基百科关键事实 1998
  #E2 = Calculator[2026 - #E1]         → 28
  #E3 = Maven[org.bsc.langgraph4j:langgraph4j-core] → 1.9.6（Maven Central 真实返回）
  ```
  worker 纯 Java 执行（环内无 LLM 调用，省 token 的本质在图上一眼可见），solver 汇总三项全对。

### 5.2 另外四个模式图（RealPatternsDemo）

- **Reflection**：冷萃咖啡液 slogan 任务，真实 reflector 连批 3 轮（指出「萃字重复」「概念错位」「比喻失当」并各给参考改写），未达 ACCEPT，3 轮上限返回最后一版——真实模型的批评远比剧本狠，循环上限确实在起作用。
- **Plan & Solve**：真实 planner 拆出 4 步编号计划，executor 逐步执行，finalizer 汇总正确（1998 / 28 年）。
- **Reflexion**：硬约束（恰好 20 个汉字 + 不得出现「咖啡」）。actor 首轮把自我批评过程写进输出，evaluator 是纯 Java 校验、整段输出都算数 → FAIL；self-reflection 生成的语言反思存入情景记忆，**第 2 次尝试读到反思后输出干净的 20 字 slogan，PASS**。记忆环全链路真实跑通。
- **LLMCompiler**：planner 产出「Google / Microsoft 成立年份」两个 Search 任务的 DAG，scheduler 节点内 CompletableFuture 并行跑完同一波次，joiner 正确答出 Microsoft（1975）比 Google 早 23 年。

### 5.4 HITL 审批与客户端层联网搜索（RealAdvancedDemo，exit=0）

- **HITL**：ReAct 图编译时加 `MemorySaver` + `interruptBefore(TOOL_NODE)`。真实模型产出
  `Action: Search[...]` 后在工具执行前中断，待审批动作从 State 读出，Checkpoint 存 2 个快照；
  人工确认后 `GraphInput.resume()` 恢复，Search 真正执行并答对（1998 / 28 年）。
  这是概念 5（Checkpoint）从剧本到真实模型的闭环。
- **客户端层 $web_search**（`WebSearchLlm`）：网关内置搜索工具下沉到 LLM 客户端内部循环
  （请求 → 带 search_id 的 tool_call → 回空结果 → 服务端注入结果 → 正文），一次
  `complete()` 透明完成，16s 两轮实测打通。与图级 Search 并存对比：
  图级 = 显式节点（进轨迹、可中断审批、可并行调度）；客户端级 = 节点黑盒（省事但不可见）。
  实测观察：网关搜索对冷门技术问题答案与 Maven Central 不符——权威数据查询仍应走专用工具。
- **混合调度**（同图并存）：一张 ReAct 图同时挂 `Search`（维基，Java）与 `WebSearch`
  （节点外壳 + `$web_search` 客户端内核），模型按问题类型路由——百科事实走图级 Search，
  维基没有的新锐信息走 WebSearch，两种执行层在同一张图里各得其所。

### 5.5 真实模型与剧本的行为差异

- K2 心算强：纯算术题直接 `Finish[262244]` 不调工具，必须给「非查不可」的事实类问题才能看到工具循环。
- K2 数数强：「恰好 20 个汉字」一把过；叠加禁用词约束才逼出失败-反思环。
- K2 批评欲强：Reflection 的 reflector 从不说 ACCEPT，maxRounds 是必需品。
- K2 输出格式漂移：REWOO 计划步骤会加引号、Maven 参数会漏 groupId——worker 剥引号 + planner 提示词带参数格式提示（toolHints）两处兜底。
- K2 以 prompt 声明为准的工具观：提示词里没写的工具即使 API 注册了也不会调用（HITL 与 $web_search 两次踩到）。

## 6. 运行指南

### 手写版（com.example.agent）

```bash
mvn compile exec:java        # 全部 9 个模式剧本演示（ScriptedLlm，离线）
```

### 图版（com.example.langgraph4j）

```bash
mvn compile exec:java@graph          # 九个图版剧本演示（PatternsDemo）
mvn compile exec:java@article        # 文章五概念演示（ArticleDemo）
mvn compile exec:java@real           # 真实 LLM：ReAct + REWOO 图（需 KIMI_API_KEY）
mvn compile exec:java@realpatterns   # 真实 LLM：Reflection / Plan&Solve / Reflexion / LLMCompiler / LATS / Self-Discover / STORM / Multi-Agent
                                     # 可用 -Dexec.args="reflection,compiler" 只跑部分
mvn compile exec:java@realadvanced   # 真实 LLM 进阶：HITL 审批中断 + 客户端层 $web_search
```

真实模型环境变量：`KIMI_API_KEY`（必填）、`KIMI_CHAT_BASE`（默认 `https://agent-gw.kimi.com/coding/v1`）、`KIMI_MODEL`（默认 `kimi-k2-0905-preview`）。

## 7. 踩坑记录

### 7.1 langgraph4j 通用

- `StateGraph.END` 的值是 `"__END__"`（大写），pathMap 的键必须与路由返回值精确匹配，否则报 `cannot find edge mapping`。
- `messages` 必须声明 `Channels.appender`；用 `Channels.base` 会被整体覆盖（等价于 Python 版忘加 `operator.add`）。
- `Channels.base(HashMap::new)` 有 Supplier/Reducer 重载歧义，需显式转型。
- 节点只返回要更新的字段，不要返回全量 State。
- langgraph4j 内部异步执行器是非 daemon 线程，命令行演示 main 末尾要 `System.exit(0)`。
- `stream().forEachAsync()` 在 END 后生成器不自动关闭会挂起，教学用同步 `invoke`。
- 图有默认步数护栏 `recursionLimit(25)`：STORM 逐章环 17 章需 36 步直接抛
  `Maximum number of iterations reached`——循环多的图要么放宽 recursionLimit，
  要么在图内限制运行期量（大纲章节数）的上限。
- `AgentState.value(key, default)` 双参重载在 1.9.6 已 `@Deprecated(forRemoval=true)`：
  全代码库已迁移到 `value(key).orElse(default)`（单参 Optional 版）；注意数值/布尔场景
  要显式类型见证 `this.<Integer>value("k").orElse(0)`，否则推断为 Object 编译失败。
- `CompiledGraph.invoke(Map, RunnableConfig)` 同样已弃用（forRemoval）：迁移为
  `invoke(GraphInput.args(map), config)`；`GraphInput.resume()` 恢复路径不受影响。
  注意 `Map.of("k","v")` 是 `Map<String,String>`，需 `Map.<String, Object>of(...)`
  显化类型参数才能传入 `args(Map<String, Object>)`。
- `Channels.base(() -> null)` 在建初始 State 时 `Collectors.toMap` 抛 NPE——channel 默认值不允许 null，可空语义用哨兵值（如 -1 表示「根」）。
- 挂了 `MemorySaver` 后每个节点结束都会把 State 深度序列化写 Checkpoint（`Serializer.cloneObject`），自定义 State 值要实现 `Serializable`，否则运行时才抛 `NotSerializableException`（LATS 的 `TreeNode` 踩到）。

### 7.2 网关 / 模型层

- 该网关模型只允许 `temperature=1`，请求体带 temperature 字段直接报错——`OpenAiChatModel` 的 temperature 是 `Double`，不设置即不发送。
- K2 是推理模型：纯推理时 `content` 为空、答案在 `reasoning_content`（langchain4j 对应 `AiMessage.thinking()`），客户端要做回退。
- K2 单次调用最长可达数分钟（网关拥塞时更明显）：请求超时放宽到 300s 并关闭重试，否则重试会放大等待。

### 7.3 工具层（WebSearchTool / MavenTool / WebSearchLlm）

- 手写 JSON 提取用 `(?:...)*` 正则扫长文本值会**递归爆栈（StackOverflowError）**——正则星号是递归实现，深度随匹配长度增长；多段 `[^。]*` 大正则还有灾难性回溯。全部改为手工扫描/按句切分。
- 手工拼 messages JSON 时初始串不能提前闭合数组尾 `]`——追加的消息会挂在数组外，网关报 HTTP 400。
- `$web_search` 是 Moonshot 私有扩展，langchain4j 的 ToolSpecification 只能发 `type:"function"`，会被网关忽略——`WebSearchLlm` 与 `KimiLlm` 一样手写 HTTP。
- 模型的工具使用以 **prompt 声明为准**：提示词里没写的工具，即使 API 注册了模型也不调用（两次踩到：图工具表只写 Calculator 时 $web_search 被拒用）。
- LLM 给的搜索查询带疑问词（「Google 成立于哪一年」）时 opensearch 命中率低，甚至跑偏命中 Google Chrome——查询逐级降级（原查询 → 去疑问词 → 纯字母专名优先）。
- 维基 REST 摘要不包含成立日期等关键事实——问「成立/年份/日期」类问题时再到全文导言定位「含关键词 + 四位年份」的句子，并置于输出最前（下游 solver 截断证据时也能保住答案）。
- REWOO 变量替换取证据中的「第一个数字」会提错（摘要里「北美4大云服务」的 4 抢在 1998 前面）——启发式改为优先四位年份。

## 8. 工程结论

- 九个图版 + 概念 ReAct 图均通过真实模型验证，图的环路由、条件边、通道合并策略与真实 LLM 的输出格式完全兼容。
- LATS 图版再次印证「运行期动态结构 → 静态图」的翻译范式：搜索树无法按层画成节点，就保留一个 search 节点做整轮迭代（expand→execute→evaluate→descend），树的形态数据存 State base 通道（TreeNode 列表 + currentId），回溯语义用 search→reflect→search 的环表达；真实 LLM 下三层搜索树正确收敛（1998 / 28 年），LLM 调用量为六模式之最。
- LLM 抽象收敛到 `LlmClient`（`String complete(String)`）一个接口是本轮所有改造的支点：换模型、换客户端（手写 HTTP / langchain4j）、脚本化回归都只动实现，不动图。
- 真实模型接入后，循环上限（maxRounds/maxTrials）、输出格式兜底（剥引号、参数提示）、请求超时三件事从「可有可无」变成「必须有」。
- Self-Discover 图版证明：阶段静态的模式（SELECT→ADAPT→IMPLEMENT）可以一对一翻译成节点链，无需任何「动态结构封存进节点」的技巧；唯一运行期量（步骤数）用条件边自环解决——这是当时最忠实于手写控制流的一张。STORM 图版补齐了双层循环的判定标准：外层循环体含 LLM 调用且有跨轮状态（逐章）→ 画成两节点环；内层循环静态固定（每章 3 视角）→ 收进节点内部。
- STORM 真实运行暴露一个工程事实：大纲章节数是模型自由产出（两次跑出 17 / 15 章），LLM 调用量随行数线性放大，图的步数护栏（recursionLimit 默认 25）比 LLM 循环上限更早成为瓶颈——跑长文生成要同时放宽图护栏或限制大纲长度。
- Multi-Agent 是九个图版里唯一「图优先」的模式：不存在手写循环版，角色/调度/黑板天然对应节点/条件边/appender 通道，也是唯一一张「画出来比写出来更短」的图——系列至此闭环：九个图版覆盖了「循环翻译成环」与「本身就是图」两类建模范式。

## 9. 后续方向

- 图版翻译已全部完成（八大模式 + Multi-Agent 补充）。当时列的后续：
  - HITL 从「中断-打印-恢复」升级为真实审批流（外部信号驱动 resume，含拒绝/改派路径）；
  - Checkpoint 持久化到磁盘（现为内存 MemorySaver，重启丢历史）；
  - Multi-Agent 动态角色（运行期按任务增减 worker，回到「动态结构存 State」的建模问题）；
  - 混合调度的质量护栏：客户端层 $web_search 结果与权威源（MavenTool）冲突时的校验策略。

**更新（2026-10-30）**：本项目之后未在此文续写，而是演进为
「Agent 工程实验场」。当时的方向已由后续阶段落地或另有答案：

- 动态角色/动态 harness → 新开了 `com.example.workflow` 包（Claude Code
  动态工作流六模式的 Java 版），比「动态结构存 State」更彻底：编排干脆
  由模型运行时现写，见 [LANGGRAPH4J.md](LANGGRAPH4J.md)「动态工作流」一节；
- 预算/审批/审计 → `com.example.harness` 治理层（Budget / ApprovalPolicy /
  RunRecord 档案 / ToolGate / RuleDistiller），真实模型验证过的生产语义；
- 图版 vs 原语版选型 → `com.example.agentloop` + loopbench 同剧本对比；
- 评估 → `com.example.langgraph4j.eval` 轨迹级 Pass@k。

后续主线一律以 [README](README.md) 的「四个层面」和
[LANGGRAPH4J.md](LANGGRAPH4J.md) 为准，本文作为第一阶段的历史记录封存。
