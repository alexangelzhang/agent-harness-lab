# 多 Agent 架构选型对标（要不要拆 / 四种模式 / 决策树）

来源：智能体AI《多 Agent 不是单 Agent 的升级版》（2026-10-31）：
https://mp.weixin.qq.com/s/w_-cNZpD2LFfmeIlwNgqag

核心论点：**多 Agent 不是升级，是拿协调成本换并行能力的一笔账**——
划不划算取决于任务结构，不取决于团队想不想赶时髦。分水岭是
**读密集 vs 写密集**：各自读不同信息、独立检索、并行分析 → 适合拆；
同时改同一份产出物、保持统一风格、频繁互改 → 协调成本比收益高，
优先单 Agent 或强协调。

## 四种模式一句话

| 模式 | 解决什么 | 最大优势 | 最大问题 |
|------|---------|---------|---------|
| Workflow | 工序固定吗 | 稳定便宜可预测 | 不会临场变通 |
| Supervisor | 谁来协调 | 通用好控制 | 上下文/吞吐瓶颈 |
| Hierarchical | 主管管不过来 | 管理复杂任务 | 层级深难调试 |
| Swarm | 下一步跟着谁走 | 灵活自然交接 | 可观测性/成本 |

决策树：工序固定 → Workflow；要中央协调 → Supervisor；大到分层 →
Hierarchical；对话焦点漂移 → Swarm。**默认立场：单 Agent 够用就不拆，
每加一层都得说清换来了什么。**

文章增量信息最多的一点：**Supervisor-as-Tools**——专家不必是平级
Agent，可以当主管手里的工具用：路由更清楚、控制权更集中、好调试。
Hierarchical 踩坑提示：委派链无上限会层层转包七八次，上下文传递
成本超过任务本身——护栏（最大委派层数/次数、超时、循环检测、预算）
不是可选项。

## 与本项目对账

| 文章要点 | 本项目对应 | 状态 |
|------|-----------|------|
| Workflow（控制流代码写死，Agent 只执行工序） | `workflow` 包是动态工作流；固定工序流水线补在 `multiagent.WorkflowPattern` | ✅ |
| Supervisor（中央主管拆解/分派/综合） | `multiagent.SupervisorPattern.dispatch`（langgraph4j 的 Multi-Agent 图是另一形态） | ✅ |
| Supervisor-as-Tools（专家当工具，不当平级 Agent） | `SupervisorPattern.dispatchViaTools`：专家包成 SchemaTool，demo 与平级分派并列对比 | ✅ |
| Hierarchical（分层 + 委派护栏：层数/次数上限、循环检测、预算） | `multiagent.HierarchicalPattern` + `DelegationGuard`（max-depth/handoffs/循环，与 Budget 同为护栏层） | ✅ |
| Swarm（Agent 自己决定 handoff 给谁，控制权不绕回中心） | `multiagent.SwarmPattern`：路由点名 + 护栏留痕 | ✅ |
| 决策树（四个问题依次问，默认不拆） | `multiagent.TopologyAdvisor`：任务画像六信号 → 可执行决策树 | ✅ |
| 读密集/写密集分水岭 | 画像信号 `independentReadSources` / `sharedWriteTargets`，写密集直接劝退 | ✅ |
| 每层要能说清换来了什么（加层成本意识） | 推荐理由强制输出「换来了什么」，护栏理由点明成本（延迟/上下文随层数放大） | ✅ |

## 缺口清单（建议）

> ✅ 已落地（2026-10-31）：新包 `com.example.multiagent`，落地结果见下文。

## 落地结果（2026-10-31）

包 `com.example.multiagent`，demo `mvn exec:java@topology`（ScriptedLlm
离线五幕，13 项断言全过，回归 + clean verify 全绿）：

| 缺口 | 落地 | 说明 |
|------|------|------|
| 决策树可执行化 | `TopologyAdvisor` | 任务画像六信号 → 推荐拓扑 + 说人话的理由；共享写出目标 > 0 优先劝退（流水线顺序写除外），全不命中默认「单 Agent」 |
| 委派护栏 | `DelegationGuard` | max-depth / max-handoffs / 循环检测三重检查，理由说人话进 trace；Hierarchical 每跳、Swarm 每次交接前必过 |
| Swarm 骨架 | `SwarmPattern` | 去中心化接力：当前 specialist 回复 → 路由点名下一棒 → 过护栏留痕交棒；客服转接 3 跳正常跑 + A↔B 循环被掐断 |
| Supervisor-as-Tools | `SupervisorPattern.dispatchViaTools` | 专家包成 SchemaTool，主管输出 `CALL <工具>：<问题>`；与平级分派 `dispatch` 在 demo 第三幕并列对比 |
| Workflow / Hierarchical 骨架 | `WorkflowPattern` / `HierarchicalPattern` | 工序固定走代码控制流；层级下派「完成：/委派：」协议，超深委派（5 层套 max-depth=3）被拦演示 |

五幕：①决策器给四种画像各出推荐（含写密集共享文档 → 建议不拆）；
②Workflow 报表流水线；③Supervisor 两种结构对比；④Hierarchical
正常 2 跳 + 超深被拦；⑤Swarm 接力 + 循环被拦。

真实对照组：`mvn exec:java@topologyreal -Dexec.args="[1-6]"`（需
KIMI_API_KEY）——①真实模型把任务描述翻译成画像信号进决策树，四种
画像推荐全对（中央协调 vs 分层在提示词里显式分野）；②Kimi 当主管
出规范「分派：」行；③Kimi 当总监走「委派：」协议，2 跳交付；④Kimi
扮演四位客服 3 跳接力干净收工（路由跳过持有者自己——接棒者提自己
领域的词不是交接信号，这个改进就是真实跑出来的）；⑤多人共写画像
被前置劝退；⑥双路由对照：`SwarmPattern.modelRouter`——调度决策交
LLM（读语义、不用背 cue 词，输出对不上花名册一律视为收工），与
`keywordRouter`（免费可解释，但接棒者必须说中 cue 词）同题对照，
护栏对两种路由一视同仁。另有 `SupervisorRealDemo`
（`mvn exec:java@supervisorreal -Dexec.args="[1-3]"`）：真实主管的
「分派：」/「CALL：」行全部命中花名册（trace 逐条校验）+ 全真实矩阵
（主管 1 次 + 专家 3 次 + 综合 1 次调用账）。三次运行同上留档。
另有 `RunJournal`：分派/交接 trace 收进 RunRecord.Step 追加进
`runs/runs.jsonl`——runner 分拓扑，GUARD_BLOCKED 为显式终态，
剧本版 + 两个真实版与单体 loop/状态图/动态工作流同一张审计底账。
真实跑通后决策树做了
一处升级：**写密集信号前置**——共享写出目标 &gt; 0 且工序不固定，
不问其他信号直接建议不拆（对应文章「读密集 vs 写密集是分水岭」的
原始立场）。

原文缺口清单（存档）：

新包 `harness.topology`——把「选型判断」本身做成项目里的一等能力
（呼应「不要从已有能力边界看，要从生产级视角补短板」）：

1. **`TopologyAdvisor`（决策树可执行化）**：输入任务画像信号——
   工序固定度、是否中央协调、规模/分组信号、对话焦点漂移、读密集
   vs 写密集（独立读源数 vs 共享写出目标数）——按文章决策树依次问，
   输出推荐拓扑 + 理由 + 「不拆（单 Agent）」永远是默认项。
   每层升级必须给出这一层换来的东西，说不清就退回上一层。
2. **`DelegationGuard`（委派护栏，Hierarchical/Swarm 的前置）**：
   最大委派层数、最大 handoff 次数、循环检测（A→B→A 交接环）、
   与 Budget 联动——超限 fail-closed，转主管或直接拒绝。文章原话：
   这些不是可选项。
3. **`HandoffRouter`（Swarm 最小骨架）**：Swarm 的核心语义——
   当前 Agent 自己决定把控制权交给谁（不绕回中心）；每次 handoff
   过 DelegationGuard、记 RunRecord（可观测性是该模式最大短板，
   留痕从骨架里长出来）。
4. **Supervisor-as-Tools 编排 demo**：主管 + 专家工具（SchemaTool
   套治理壳）组合，对比「专家平级 Agent」——路由清楚、控制权集中，
   文章点名的最大增量点落地。

demo：`mvn exec:java@topology`，四幕对应四个缺口；真实模型路径
可以后补（选型逻辑是确定性的，剧本足够）。
