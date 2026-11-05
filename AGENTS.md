# AGENTS.md——给在这个仓库里干活的 Agent

## 定位

Agent 工程实验场（Java）：从《Agent 的九种设计模式》出发，现已覆盖
编排模式（手写版 + langgraph4j 状态图版）、四种运行载体（手写 / 状态图 /
agentloop 原语 / workflow 动态工作流）、生产级治理层（harness 包）。
治理层的版图：记忆系统（触发 → 三路召回 → 融合 → 写回有闸门 →
遗忘在岗 → 五维 benchmark 出分 → 情境记忆）、Sensor 反馈层（计算/
推断分野、修正指引回流、质量分层、转向台账）、Harness 模板（拓扑
约定 + sensor 一捆 + 漂移检查）、加上预算/审批/凭据/注入防线/路径
验证/失败归因——牛津《AI Agents: The Definitive Guide》12 章对标
全闭环；多 Agent 拓扑（multiagent 包：四种组织形式 + 委派护栏 +
选型决策树，默认立场是不拆；生产深化：并行 fan-out、整轮时间盒、
LLM 流式输出含 SSE 与预算熔断）。剧本与真实模型双轨可跑。README 和
LANGGRAPH4J.md 是理解全貌的两张地图，动代码前先读对应章节。

## 构建与运行

- 两模块：`core/` 是库（`agent-core`，语义化版本，生产可用），`verify/` 是集成验证（54 个场景）。**铁律：core 不许依赖 verify 模块的任何类**（编译即验证）；新场景进 verify 模块、照抄相邻 pom execution 格式。
- Java 17 + Maven。编译挂 `-Werror -Xlint:all`：**unused import、
  缺 serialVersionUID 这类警告会直接挂构建**，别留。
- 场景入口走 pom 的 exec executions（登记在 verify/pom.xml）：
  `scripts/dev.sh <id>` 一条命令（先装 parent+core 再 exec，改 core 后无需手动 install）；
  等价形式 `mvn -q exec:java@<id> -pl verify`（首次或改 core 后先
  `mvn -q install -N && mvn -q install -pl core`）。**别用 `-pl verify -am` 跑
  exec**——goal 会落到 aggregator parent 上报 mainClass 缺失。
  id 全表以 verify/pom.xml 的 exec executions 为准（LANGGRAPH4J.md 各章节随
  能力引用）；新增场景两登记：pom execution + LANGGRAPH4J.md 对应章节
  运行行。README 运行一节只放快速上手入口，不再逐条罗列。
- 单测：`mvn -q test -pl core`（JUnit 5，纯逻辑无 LLM 依赖的类先铺——
  RunStore/MetricsRegistry/TelemetryLlm/Tracer/RateLimitedLlm/PromptFuzzer 已有）；
  `mvn -q clean verify` 会连带单测一起跑，是提交前的收尾底线。
- 配置统一走 `harness.config.AgentConfig`（`load()` 读环境 / `from(Map)`
  注入）：`LLM_API_KEY` / `LLM_BASE_URL` / `LLM_MODEL` / `AGENT_RUNS_DIR` /
  `AGENT_RULES_DIR`（旧名 `KIMI_*` 仍兼容回退，新部署用 `LLM_*`）。
  新增环境变量不许再散落直读。
- 剧本模式必须离线可跑（ScriptedLlm 预置回复）；真实模式 gated 在
  `LLM_API_KEY` 上（AgentConfig.requireApiKey()），缺 key 明确报错退出，
  不许悄悄退回剧本。

## 改完代码的收尾仪式

1. `mvn -q exec:java@loopbench`——图版/原语版应 2 轮 / 4 次调用打平；
2. `mvn -q clean verify` 全绿；
3. 文档同步：新能力更新 LANGGRAPH4J.md（标题形如
   `## 章节名（包名 包）`）；动了项目结构同步 README。

## 文案与风格

- 一律中文「说人话」：去 AI 味、去套话（「低温」「慢萃」这类空卖点词是
  反面教材），场景输出和文档都是；
- 研读外部文章走三步，一步不省：① 全文浓缩存档到 `docs/`（文件名见现有
  约定，开头注明出处链接）；② 文章点名的能力落地成代码进对应包（机制
  对了不算完）；③ 剧本场景验证 + 登记 pom/LANGGRAPH4J，能接
  真实模型的再补真实对照组。只存档不落地的研读不算闭环；
- 运行档案落在 `runs/runs.jsonl`，多种载体共用；`rules/` 下三份草稿
  （AGENTS.draft.md 剧本造历史、AGENTS.real-draft.md 真实运行、
  AGENTS.outer-draft.md 一键外循环）都是蒸馏产物，机器生成、待人审，
  别手改——想看账先跑 `@report`，想一键蒸馏跑 `@outerloop`。

## 已知深坑

- Java 源码里连注释都过不了 `\uXXXX` 预扫描，注释中别写这个转义；
- JdkHttpLlm 的响应提取对转义敏感，改它要回归所有真实场景；
- 真实模型调用慢，单次命令有超时：真实模式的场景一律做分片
  （参考 workflow 的 `real [1-7]`）。
- 多 Agent 交接会绕圈（A→B→A 互踢皮球）：路由必须跳过当前持有者——
  接棒者提自己领域的词不是交接信号（topologyreal 场景四真实逼出）；
  仍绕圈说明分工有洞，把互转交场景合并给一个专员（distill 采纳检验
  已验证合并后不再被拦）；
- 层级委派别裸奔：max-depth 贴近真实层数，层级每深一层上下文丢一分，
  别让护栏天天响（超深被拦见 `@topology` 第四幕）；
- Swarm 交接次数要设上限：maxHandoffs 防用户陪跑，一次客服诉求传
  四五棒就是设计错了（真实触发见 `@distillreal`）；
- 写密集任务（多人共写一份产物）默认不拆：拆开后互相覆盖、协调成本
  超过并行收益；只有工序固定的流水线（顺序写）例外。
- 关键词路由的 cue 词会被「提及」误判成「交接」：接棒者复述上一棒的
  领域词（退款专员说「物流同事」）就会被路由交回去，话术里要像财务
  专员一样给中间棒配禁提词（topologyreal 场景四回归真实逼出）；
- 网关 $web_search 回传时 tool_calls 的 type 必须写 "function"（OpenAI
  标准），照抄响应里的 "builtin_function" 一律 400 tokenization failed
  （2026-11 curl 实测定位，见 WebSearchLlm 注释）；
- 真实模型首跳可能不吐 Action 行或直接 Finish：ReAct 解析器按「直接
  终答」兜底不炸循环；HITL 恢复前先确认真的停在工具前（lastAction
  非 Finish），别对不存在的 checkpoint 发 resume。
- 真实 ReAct 实验先教协议再谈行为：prompt 里没有「可用工具 + 每轮
  输出一行 Action」的说明时，真实模型会全程自然语言闲聊、一轮 Action
  不吐（realfoldeval 第一版 6/6 如此）——把「不知道格式」当成「跳过
  工具」会得到错误结论；协议补齐后行为观察才算数。新真实实验 wiring
  一律先过 `ProtocolProbe`（harness.probe）预检再开跑，探针挂直接
  fail fast，别烧额度；分派类协议的探针 prompt 必须把专员名单写进去，
  只写「拆给下面的专员」模型会照单自造名字（supervisorreal 接入时
  实测命中，用 ProtocolProbe.rosterProtocol 生成）。已接入：
  realfoldeval / real / realadvanced（HITL+混合调度）/ supervisorreal
  场景一 / topologyreal 场景二；客户端层 $web_search 无 Action 协议，
  刻意不接。
- 运行档案（runs.jsonl）已硬化（RunStore）：跨进程文件锁、超 8MB 轮转
  进 archive/、留最近 5 份归档、坏行跳过不拖垮读取。别再直接 Files.write
  往底账里怼，写必经 RunStore.append。
