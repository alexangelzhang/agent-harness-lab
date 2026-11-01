# 工具底座 + 记忆系统两篇文章对标

来源：
- 尼恩《从0到1吃透Agent工具底座》（公众号「技术自由圈」，2026-10-31）：
  https://mp.weixin.qq.com/s/_fxfBaXB1Z21_-DdGy1Gzg ，拆解 QwenPaw 的
  注册/查找/注入/执行四段链路 + 三重门治理 + 技能热刷新。
- 智能体AI《Agent 记忆系统七个模块、16 个处理步骤》（2026-10-31）：
  https://mp.weixin.qq.com/s/TMnVMTMm0aKH8eWb9nxipQ ，记忆触发器 +
  三路召回 + 融合排序 + 写回 + 反馈闭环。

## 一、工具底座（尼恩/QwenPaw）对账（2026-10-31 全部闭环）

| 部件 | 文章做法 | 本项目对应 | 状态 |
|------|---------|-----------|------|
| 工具注册 + schema | FunctionTool(name/func/schema)，schema 是给 LLM 的入参出参契约 | `harness.toolbase.SchemaTool`：name/description/params 参数契约表/action，进提示词的是参数表不是散文 | ✅ |
| 治理壳 | PolicyGuardedTool：注册即挂 check_permissions + 审批闭环，工具内零权限逻辑 | `harness.toolbase.GovernedTool`：注册时声明 Level + Risk，调用自动过 ToolGate、NEEDS_APPROVAL 挂起发 PendingAction，业务零权限逻辑 | ✅ |
| 查找 fail-closed | 未注册工具 Phase0 直接 DENY（失败即关闭，不猜） | `harness.toolbase.ToolResolver.resolve`：未注册抛 ToolNotFound（消息即 DENIED 文本）+ 审计留痕，Optional 版探查同样留痕 | ✅ |
| 渠道准入 ChannelACL | 白名单直通 / 黑名单拒 / 陌生人进 pending 审批 | `harness.toolbase.ChannelAcl`：三态裁决 + pending 队列 + 管理员放行进白名单 | ✅ |
| 启动装配 | BootstrapFactory 统一注入 tools/hooks/modes，Web 和 CLI 入口装配一致 | `harness.toolbase.WorkspaceBootstrap` + `Workspace`（assemblyDigest 自检），demo 证明两入口装配一字不差 | ✅ |
| 技能热刷新 | Manager 推 MinIO，Worker 比 ETag 指纹热加载，进程不重启 | `harness.skills.SkillWatcher`：目录即管道，mtime+size 指纹（ETag 替身）轮询，新增/变更即注册或重载 | ✅ |
| 执行安全 | 三重门：未知工具 DENY / 权限校验 / 渠道 ACL | 三门齐了：ToolResolver（未知 DENY）+ ToolGate（权限）+ ChannelAcl（渠道） | ✅ |

## 二、记忆系统（智能体AI）对账（2026-10-31 全部闭环）

| 部件 | 文章做法 | 本项目对应 | 状态 |
|------|---------|-----------|------|
| 记忆触发器 | 判断该查不该查，不过度检索 | `harness.memory.MemoryTrigger`：召回信号/自足信号/长度兜底三档，按序匹配带规则名 | ✅ |
| 三路并行召回 | 短期记忆 / 长期记忆 / 外部知识，按任务动态选择 | `harness.memory.RecallPipeline` + `KnowledgeBase`（接口 + 内存实现 + NONE 空路）：三路召回按任务动态选 | ✅ |
| 融合排序五维 | 相关性 / 时效性 / 可信度 / 重要性 / 冲突关系 | `RecallPipeline` 融合分 = 相关性 ×(0.4+0.6·置信度)× 时效系数 + 重要性抬升；冲突对里旧的让位、理由留 notes，不硬合并 | ✅ |
| 记忆数据结构 | Confidence / Status（有效/过期/待确认/被替代）/ Valid Until / Permissions | `Memory` v2：confidence/status(ACTIVE/SUPERSEDED/EXPIRED)/validUntilMs/permissions 全到位，检索默认只出有效且权限够的 | ✅ |
| 写回三操作 | 新增 / 合并 / 更新（旧状态标记被替代，保留历史） | `MemoryConsolidator` 写回四操作：ADD / MERGE（共享标签并入更完整记录）/ SUPERSEDE（标记替代、历史留库）/ SKIP（重复不动） | ✅ |
| 反馈闭环指标 | 检索质量 / 任务效果 / 记忆准确性 / 成本 / 权限 | `harness.memory.MemoryMetrics`：触发率/三路命中/注入条数；任务级指标由 eval 包覆盖 | ✅ |

## 三、补齐清单（2026-10-31 已全部落地，留档）

1. **工具底座包 `harness.toolbase`**：
   - `SchemaTool`：给 Tool 补结构化参数 schema（name/description/params 列表），
     渲染进提示词的是契约不是散文；
   - `GovernedTool` 治理壳：注册时声明 Level + 风险，调用自动过 ToolGate，
     工具内零权限逻辑（文章核心思想：治理能力挂在壳上，不写在业务里）；
   - `ToolResolver`：fail-closed 查找——未注册工具 Phase0 DENY 并留痕，
     不猜不降级；
   - `ChannelAcl`：渠道准入三态（白名单直通/黑名单拒/陌生人 pending）；
   - `WorkspaceBootstrap`：统一装配点——tools + hooks + modes 一个工厂出，
     两个「入口」（demo 模拟 CLI/Web 两个装配调用点）拿到完全一致的东西。
2. **技能热刷新**：`SkillWatcher`——文件系统版
   ETag 指纹（mtime+size 就够）轮询，新技能出现即热加载，进程不重启；
   demo 现场写一个新 SKILL 文件进目录被自动捡起。
3. **记忆系统升级 `harness.memory`**：
   - `Memory` 字段补 confidence / status（ACTIVE/SUPERSEDED/EXPIRED）/
     validUntilMs / permissions，检索默认只看 ACTIVE；
   - `MemoryTrigger`：规则版触发器（「继续上次/按我之前的偏好」等信号
     触发检索，简单数学题不触发——文章原例）；
   - `KnowledgeBase` 接口 + 内存实现（外部事实源，与长期记忆语义分开）；
   - `RecallPipeline`：三路召回（短期=最近对话、长期=MemoryStore、
     外部=KnowledgeBase）并行 + 融合排序五维打分（相关性×时效×可信度×
     重要性，冲突时按来源权威+更新时间定当前有效值，不硬合并）；
   - Consolidator 写回三操作显式化：ADD / MERGE（相关记忆合并成更完整的
     记录）/ SUPERSEDE（旧状态标记被替代、保留历史可查）；
   - `MemoryMetrics`：检索触发率、注入条数、记忆被引用计数——反馈闭环的
     最小可观测面。
4. **设计原则进 README**：fail-closed（未注册不猜）、注册即治理、
   记忆有效性 ≠ 记忆重要性。

#6（API 优先浏览器兜底）已在上一轮进了 README 原则，这里不动。

## 结论

两篇文章的对账项全部闭环：工具底座七项（含三重门到齐）、记忆系统六项
（触发 → 三路召回 → 五维融合 → 写回四操作 → 指标）。跑通 demo：
`mvn exec:java@toolbase` / `@hotreload` / `@memorysystem`，
加上原有的 `@memory` / `@skills` 构成完整链路。
