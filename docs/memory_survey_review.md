# LLM Memory 综述解读对标

来源：AF智域心识《LLM Memory 趋势解读》（2026-10-31）：
https://mp.weixin.qq.com/s/KF-y31WFNzbeSVUubdTINg ，解读 Zhoubian et al.
《Memory for Large Language Models》综述（arXiv:2607.25380，2026-07）。

综述核心：记忆正从「计算过程的临时副产品」变成「需要独立设计的系统能力」；
瓶颈从存储迁移到**控制面**（写入/路由/整合/遗忘四动作）；评测从
「支持多长上下文」转向五维（capacity / fidelity / persistence /
interference robustness / efficiency）。

## 与本项目对账（harness.memory 已有前几轮的地基）

| 综述要点 | 本项目对应 | 状态 |
|------|-----------|------|
| 分层混合：不同记忆做不同时间跨度/保真要求的工作 | 短期=ContextWindow（高保真近窗）/ 长期=MemoryStore（可寻址 lookup）/ 外部=KnowledgeBase——分层已立 | ✅ |
| 控制面·写入：什么值得进记忆 | Consolidator 写回四操作（ADD/MERGE/SUPERSEDE/SKIP） | ✅ |
| 控制面·路由：一次任务调用哪种记忆 | MemoryTrigger（该查不该查）+ RecallPipeline 三路动态选择 | ✅ |
| 控制面·整合：重复/冲突/碎片压成稳定状态 | MERGE 合并 + 冲突抑制（旧的让位、理由留痕） | ✅ |
| 控制面·遗忘：释放资源，避免过期污染 | MemoryJanitor：到期置 EXPIRED、超保留期物理清除、报告可审计；`expire(id)`/`purge(id)` 补齐 | ✅ |
| 推理时更新的风险：错误写入长期记忆会持续污染 | 写入闸门：confidence < 0.6（模型推断）以 UNCONFIRMED 落库检索不出，`confirm(id)` 转正 | ✅ |
| 评测五维 | MemoryBench 五维离线 benchmark + 限容量 LRU 淘汰，一屏出分即断言 | ✅ |

## 补齐清单（2026-10-31 已全部落地，留档）

1. **MemoryJanitor（遗忘控制面）**：`sweep()` 一条流程跑完三种清理——
   validUntil 到期 → EXPIRED；SUPERSEDED 超过保留期 → 归档清除（释放位）；
   输出清理报告（各类释放条数）。文章原话：「存储空间能靠扩容获得，
   好的控制策略只能靠设计」——遗忘就是控制策略的一半。
2. **写入闸门（防错误写入污染）**：新增 UNCONFIRMED 状态——低置信
   （<0.6，模型推断而非用户确认）的新记忆先挂待确认，检索默认不出；
   `confirm(id)` / 复跑 Consolidator 命中后可转正 ACTIVE。文章原话：
   「运行时适应必须与控制能力一起出现：写入范围要有限，错误要能被发现
   和修正」。
3. **MemoryBench（评测五维）**：离线 benchmark 一屏出分——
   fidelity（写入 N 条逐条回查，top-1 命中率）、interference（同主题
   新旧事实并存时检索必须取新）、persistence（隔轮仍召回）、
   efficiency（平均注入条数/触发率）、capacity（写爆容量时淘汰策略生效、
   库不无限涨）。给 MemoryStore 加容量上限 + 最久未用淘汰挂点。
4. **设计原则进 README**：记忆的有效性 ≠ 重要性（上轮已进）；补
   「遗忘是控制面动作不是缺陷」。

### 落地结果

- `MemoryJanitor.sweep()` 一条流程跑完三种清理（到期置 EXPIRED /
  超保留期 purge / 报告三类各动多少、清了谁），幂等可定时跑；
  顺带补了 `expire(id)` / `purge(id)`，遗忘从「状态定义」变成「控制面动作」。
- 写入闸门：`Memory.Status` 新增 UNCONFIRMED，`MemoryStore` 12 参写入
  置信 < 0.6 挂起落库，`injectableFor` 只认 ACTIVE 所以检索天然不出；
  `confirm(id)` 人工/复跑验证后转正（置信抬到 ≥0.8）。
- `MemoryBench` 五维静态剧本 + 容量上限 / 最久未用淘汰（LRU）。
  bench 一跑就抓到一个真缺陷：同分记忆原先按插入序返回，旧事实顶在
  新事实前面——修成同分按创建时间取新 + 写入时钟严格递增
  （同一毫秒写多条 createdAt 也单调）。评测的价值兑现：不是打分，
  是抓伤。
- 遗忘在岗：`RecallPipeline` 每 8 次召回自动跑一轮 `sweep()` 再查，
  到期记忆不占检索位；清理计数（到期/归档）进 `MemoryMetrics`，
  指标面板能直接看到遗忘在干活。时钟对齐是暗坑：sweep 必须
  用 `MemoryStore.now()`（与写入同一时钟），否则严格递增时钟领先
  墙钟 1ms 时「超保留期」判断会漏 newly-written。
- 演示 `mvn exec:java@memorybench`：写入闸门 → 遗忘清理 → 五维出分
  → 清理在岗（第 8 次召回自动 sweep）四幕编排，异常断言打 `!!`。
  真实对照 `mvn exec:java@memoryreal -Dexec.args="[1-3]"`（需 KIMI_API_KEY）：
  真实模型带召回记忆写稿、从对话提取事实自评置信（推测项实测自评 0.4
  被闸门挂起）、8 次召回自动清理后过期记忆不进注入上下文——
  剧本证明机制对，真实闭环证明机制在真实输出下照旧对。

## 结论

综述对账全部闭环：控制面四动作（写入有闸门 / 路由有触发器 /
  整合有合并与冲突抑制 / 遗忘有 Janitor）齐了，五维评测有
benchmark 守。跑通：`mvn exec:java@memorybench`，
与 `@memory` / `@memorysystem` 构成「存 → 召回 → 治理 → 量」完整链路。
