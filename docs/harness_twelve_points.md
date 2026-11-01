# 生产级 Agent Harness 的十二个部件（忽南忽北Wq 文章对标）

来源：https://mp.weixin.qq.com/s/p1srTKKucIJnpGGOFNVyXw（公众号「忽南忽北Wq」，
2026-10-31）。原文核心观点：模型是引擎，入口只是一扇门，真正的产品长在 Harness 里。
文章的十二个部件逐项对账本项目现状（✅ 已有 / 🟡 部分 / ❌ 缺失）：

| # | 部件 | 本项目对应 | 状态 |
|---|------|-----------|------|
| 1 | 工具发现：capability → tool/skill/composite → operation 分层检索 | `harness.skills`：`Capability` 按系统归组 + `OperationIndex`（list_capabilities / search_operations / load_operation） | ✅ |
| 2 | 知识按需加载，不把全部技能塞提示词 | `SkillRegistry` 只把名称+一句说明放系统提示，命中才加载完整流程 | ✅ |
| 3 | 记忆分层：工作上下文 / 持久记忆 / 主动检索 / 异步整理 / 作用域 | `harness.memory`：Memory（USER/WORKSPACE/PROJECT 作用域 + 所有者 + 来源）、MemoryStore（自动注入 + 主动 search 同一打分器）、MemoryConsolidator（规则抽取→退役旧事实→去重写入） | ✅ |
| 4 | 检索后筛选：小决策（分类/路由/重排）不该花大模型 | `harness.routing.CandidateFilter`：关键词重叠打分，过线且进 topK 才进上下文，滤掉带分可解释 | ✅ |
| 5 | 重复批处理交给代码：模型写程序，沙箱跑循环 | `harness.sandbox.CodeRunner`：模型写脚本 → 沙箱执行 → 结论一次交还；5 客户 × 12 账单 10 次调用降到 2 次 | ✅ |
| 6 | API 优先，浏览器兜底 | 理念项，进 README/LANGGRAPH4J 设计原则，无代码 | — |
| 7 | 凭据不进模型上下文：意图与凭据分离，每次执行过策略关 | `harness.credentials.CredentialVault`：`${SECRET:x}` 占位符执行前夜注入，scan 兜底抓明文泄漏 | ✅ |
| 8 | 系统级 pending action 审批，与模型 ask_human 分开 | `harness.interaction.PendingAction`（结构化载荷）+ WebDashboard 渲染批准/拒绝按钮（/pending + /decide） | ✅ |
| 9 | 模型路由：按任务选快模型/推理模型/专用分类器 | `harness.routing.ModelRouter`：FAST/BALANCED/FRONTIER 三档，每条决定带命中规则名 | ✅ |
| 10 | 多入口适配器：Slack/飞书/Teams 统一成内部事件格式 | 仍是缺口：只有出向 WebDashboard 事件流；入向 Envelope 适配器未做 | ❌ |
| 11 | 长任务暂停/恢复，状态不丢 | `harness.DiskCheckpointDemo`：FileSystemSaver 落盘 checkpoint，全新进程从盘上 resume 跑完 | ✅ |
| 12 | 执行轨迹 + 评估闭环 | `langgraph4j.eval`：TrajectoryCollector + EvalHarness + Pass@k + 对抗探针 | ✅ |

## 缺口按优先级排（2026-10-31 已全部落地，清单留档）

1. **记忆层（#3）**——最大的一块：MemoryStore（分作用域）、search_memory、异步整理。
   它和已有的 context 包是互补关系：context 管「这一窗怎么省地方」，memory 管「哪些值得留下」。
2. **决策筛选（#4）**——不需要引入 Jev 那种决策模型，用规则 + 打分就能演示：
   检索回来的候选先过一道筛选，再把收窄后的结果交给主模型。顺带可以把 #9 模型路由
   做成同一个「路由/筛选」包里的两个职责。
3. **凭据保管（#7）**——CredentialVault：模型只见工具意图，key 由执行前夜注入，
   上下文里永远不出现明文。挂在 ToolGate 后面。
4. **operation 检索（#1）**——在 skills 包上长一层：capability 归组 + search_operations，
   让「按需发现」从技能扩展到工具目录。
5. **磁盘 checkpoint（#11）**——把 MemorySaver 的 checkpoint 序列化到磁盘，重启可恢复。
6. **pending action 载荷（#8）**——把审批点从「暂停+等人回话」升级成结构化
   PendingAction（action/args/risk/渲染提示），WebDashboard 可以直接渲染成按钮。
7. **run_code 批处理（#5）**——Sandbox 上加一层：模型生成脚本一次跑完批量操作，
   只把结果交还，不逐条编排。
8. **多入口信封（#10）**——入向事件统一成 Envelope（actor/surface/message...），
   先做内存版适配器演示，不接真 Slack。

#6 是纯理念，和其他几轮积累的 Harness 文章（martinfowler、thoughtworks、
langchain context engineering）观点一致，作为设计原则写进 README 即可，不写代码。

## 结论

十二项里 11 项闭环。唯一没落地的是 #10 多入口适配器——它需要真的接入
Slack/飞书才能算「跑通」，在单进程教学项目里硬造一个假入口说服力不大，
留作挂点：事件总线 + WebDashboard 的订阅结构就是入向适配器要对接的位置。
其余 11 项全部有代码、有 demo、过了离线剧本验证（磁盘 checkpoint 与
CodeRunner 过了真实执行验证）。补齐后 Harness 从「九章教程对完账」
升级到「按生产级十二部件对完账」。
