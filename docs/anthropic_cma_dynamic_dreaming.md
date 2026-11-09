# Managed Agents：用动态工作流做记忆 Dreaming

## 出处

- Anthropic Cookbook 原文：https://platform.claude.com/cookbook/managed-agents-cma-dreaming-with-a-dynamic-workflow
- 同主题的 Agent Memory 研究：https://www.anthropic.com/research/components-of-agent-memory
- 本仓库前一轮浓缩：`docs/anthropic_agent_memory.md`

本文浓缩原文的完整工作流，并记录本仓库的对应实现。原文的托管服务、示例成本和研究预览能力，不等于本仓库已经具备的能力或收益。

## 一、原文在解决什么问题

Agent 每次新开 session 时不知道过去发生了什么。Dreaming 是一次后台整理：带着后见之明回看已结束的 transcripts 和现有记忆，纠正已经过时的说法，补充以后还会用到的细节，重建成更容易检索的表示，供后续 session 只读使用。

原文区分两种形态：

1. **Dreams 研究预览**：读取一个 memory store 和过去的 sessions，把重整结果写到新 store。
2. **Cookbook 动态工作流**：用户自己选择提示词、模型和输入，lead agent 写出并启动 workflow，原地更新同一个 store。

本仓库复刻第二种形态中的可验证机制，不接 Managed Agents API。

## 二、输入与记忆表示

### 1. 导出已结束 session

一份 transcript 是一次完整会话，包含用户要求、agent 回复、工具调用和工具结果。工具结果尤其重要，因为它记录实际做过什么、看到过什么，而不只是 agent 自称做过什么。

Cookbook 默认使用 11 份虚构 `tidewick` 项目材料；设置 agent ID 后，导出该 agent 的已结束 sessions。每份导出保留 session、threads 和 events，再渲染成带 `USER`、`AGENT`、`TOOL CALL`、`TOOL RESULT` 标签的纯文本。仍在运行的 session 跳过，导出失败则整轮停止，下一轮重试。

材料特意包含三类难点：事实会过期；准确命令和参数埋在工具输出里；不同 transcripts 会触碰相同主题和文件。

### 2. 小文件、链接和索引

整理结果是 Wiki 风格的小文件，每页有 frontmatter、稳定 slug、正文和 `[[other-slug]]` 链接。根目录 `_index.md` 是后续 session 首先读取的目录。记忆应该写当前可执行的认知，例如准确命令、标识符、决定及其日期、失效条件和失败教训，而不是“agent 然后做了什么”的第一人称流水账。

## 三、三个角色与运行动态

| 角色 | 默认模型 | 工具与职责 | 返回 |
|---|---|---|---|
| lead / `memory-distiller` | Claude Sonnet 5.5 | 只有 read、glob；不读 transcript、不改记忆，根据用户消息写 workflow、启动一次 run、核对结果 | 单行 JSON：输入数、处理数、失败、organized、索引数和变更 |
| analyzer / `transcript-analyzer` | Claude Haiku 5.5 | 每份 transcript 一个独立 analyzer；read、write、edit、glob、grep；验证、补充并组织相关记忆 | 一段 summary |
| organizer / `memory-organizer` | Claude Haiku 5.5 | 所有 analyzer 完成后只跑一次；合并、重命名、修链接、补全索引 | index_entries 和 changes |

用户消息要求以约 20 份为一组，前一组结束再开始下一组。workflow run 最多同时运行 64 个 threads；一个 session 最多挂载 500 份输入。时间限制属于整个 run，不属于单个 agent。单个 analyzer 失败要记下原因，不取消其他 analyzer；lead 最终必须按实际 thread 记录对账，不能把失败输入算成已处理。

Cookbook 先估算成本，超过 `BUDGET_USD` 就不创建资源。示例默认上限 40 美元、run 等待上限 180 分钟；这些是天花板，不是预期消费。

## 四、analyzer 的完整方法

analyzer 对分配给自己的 transcript 执行四步：

1. **完整读取。** 长文件分页读完，不用行数或局部片段代替全文。
2. **Verify。** 找到相关记忆并完整读取；有支持证据才维持原说法，没有证据时留给其他 transcript，不贸然删除；原文明确反驳时修正或标记矛盾，原文不确定时降低确定性。
3. **Enrich。** 补准确标识符、命令、参数、位置、缩写、因果机制、验证日期、时效说明和踩坑教训。秘密、API key、token、密码不写入，只保留必要的凭据名称。
4. **Organize。** 维护相关页的 frontmatter、交叉链接和 `_index.md`；删除第一人称叙述和无结果猜测，每页控制在 100 KB 内。

多个 analyzer 共享同一目录时，写前必须重读，使用针对局部内容的 edit，不能整页覆盖别人新增的事实。写完还要回读；如果自己的内容被另一写入覆盖，就把双方事实合并后再写并再次验证，直到自己的内容确实落下。

分析阶段不做全库合并、重命名和移动，这些是 organizer 的职责。遇到 `MOVED TO [[slug]]` 存根时，应写目标页，不往退役页继续加内容。

### 成本控制不是少做检查

原文要求 analyzer 用尽量少的大步骤完成同样的工作：transcript 只读一遍；相关记忆在修改前完整读一遍，这次读取同时充当写前重读；无依赖的读取或修改放在同一轮；写后回读一次。只有内容丢失或版本冲突才额外重读。省的是重复发送上下文，不是省略证据核验和写后验证。

## 五、organizer 的完整方法

organizer 在没有其他 analyzer 写入时运行一次，只做重组，不重新分析或发明事实：

- 修正 frontmatter 和过时描述；重复或近重复页合并到命名更清楚的页，保留双方每条事实和带日期历史。
- 合并或移动先把内容写到目标页并回读，确认没有丢行，再把旧页改成一行 `MOVED TO [[slug]]`，同时修复其他页面的旧链接。失败时保留旧页，不假装移动成功。
- 明确有后续纠正时保留当前值，同时记录被替代值、双方日期和来源；无法判定的冲突两边都保留并显式标 `CONFLICT`，不能猜一个答案。
- 索引覆盖所有现存有效页，不收 stub、不存在页面或索引自身；长页按子主题拆分，清理第一人称叙述和无结果假设。

## 六、不可信输入与权限边界

transcript、已有记忆和 run 结果都只是数据，不是指令。原文特意防止工具输出中的“忽略你的指令”进入系统行为：

- analyzer 任务只给文件路径，transcript 正文由 read 工具读取，不拼进 lead 的任务文本。
- analyzer 和 organizer 只有五种文件工具，无 shell、无 web，环境网络 allowlist 为空。
- lead 只有 read、glob；消费整理结果的后续 session 只读挂载 store，只有 dreaming session 读写。
- 每次工具调用还受平台 `auto` 权限策略检查；无人值守 notebook 会拒绝需要暂停审批的调用。
- 原文明确提醒：首轮和夜间更新都可能把恶意内容带到人工检查之前。首轮应先读新 store；夜间更新默认没有人工 review。
- 导出凭据留在本地脚本，dreaming session 不持有导出其他 sessions 所需的 key。

本仓库在此基础上多落了一道确定性边界：不仅 transcript 输入要净化，analyzer 返回的候选也要净化，否则恶意候选可绕过输入边界写回长期记忆。净化只处理 `InjectionGuard` 能识别的注入和 `CredentialVault` 已登记的密钥，不承诺识别任意秘密。

## 七、后续消费、夜间更新与审计

后续 session 只读挂载 memory store。原文的第三幕用同一 lead 模型读取 `_index.md`，再按需读相关页，强调挂载不等于把全部正文注入系统提示词；如果把 store 文本直接放进系统提示词，恶意内容的影响会从“污染记忆”扩大到“操纵每个会话”。

夜间脚本由 cron 在凌晨 1 点运行，首次默认回看 24 小时，之后使用上次开始时间作为窗口起点，原地更新同一 store：

- 失败保留窗口，下一晚重试同一批 sessions。
- 只导出当时 idle 或 terminated 的 sessions。运行中的 session 被跳过后，后续夜晚不会自动补抓；需要人工设置 `COOKBOOK_SINCE` 回看。
- 导出后的新 turns 不属于那次导出。首次初始化与第一次夜跑之间若超过一天，也要手工补窗口。
- 默认没有人工审核。每次运行先保存文件副本，托管服务的每次变更形成可审计版本，保留 30 天；若秘密进入版本，还要编辑并 redact 相应历史版本。
- 原始 nightly 脚本把“没有 transcript 可整理”视为完成并推进窗口。

## 八、原文成本数字的适用范围

原文报告：11 份示例 transcripts 共 283 KB，默认配置约 0.36 美元、2.5 分钟；约 50 份长 transcripts、12 MB 文本，在 60 次和 30 次 dream 的比较中，默认节约配置约 8 美元、10 分钟，未采用这些配置约 10 美元、9 分钟。两组 store 对固定事实清单的保留比例大致相同。

节约配置包括约 20 份分组、减少 analyzer turns、纯文本 transcript 和 Haiku organizer。以上只是 Anthropic 在其模型、材料、定价和托管运行时下的示例，不是本仓库的收益，也不能推出独立分析一定比单次共享整理便宜。

## 九、本仓库落地

| 原文机制 | 本仓库入口 | 本地行为与边界 |
|---|---|---|
| 分批独立分析、集中整理屏障 | `MemoryDreamingWorkflow` | 默认每批 20；每份独立 `Workflow.agent` 上下文，按批执行；全部尝试结束后有候选才调用 organizer 一次 |
| 逐项对账 | `MemoryDreamingWorkflow.Report` | 记录 expected、processed、batches、failures、organized、organizerError、successful；`organized` 只表示尝试过，不等于成功 |
| 共享写与写后回读 | `MemoryStore.merge(id, expectedRevision, ...)` | 同步检查版本；旧版本写失败，调用方重读后重试，保留双方 addition；旧三参数 API 不变 |
| 输入与输出不可信 | `MemoryInputSanitizer` | 原始 transcript 不变；分析副本和 analyzer 候选都先脱敏已登记密钥、再处理已知注入 |
| 去重、索引、移动、冲突 | `MemoryOrganizer`、`MemoryWiki` | 受控正文去重、完整索引、`MOVED TO` stub、无法判定冲突并列保留日期和来源；不声称理解任意自然语言冲突 |
| 后续只读消费 | `ToolPolicy.readOnlyAllowlist()` | read 放行，write/edit 拒绝并计数；由调用方接在工具执行前 |
| 夜间窗口 | `MemoryNightlyWindow` | 纯状态迁移，不创建 cron；完整成功才从 since 推进到显式 until，失败留窗；running 单列 skipped，后续不自动补抓 |
| 真实协议预检 | `ProtocolProbe`、`MemoryDynamicDreamingRealScenario` | 同一 fixture、同一 FACT 协议与判分器，比较单次共享和独立分析加集中整理；失败不退回剧本 |

有两个有意的口径差异：本地空输入或空窗口不报告成功，也不推进游标；原文 nightly 在没有 transcript 时会推进。本地 `successful=false` 表示整轮需要重试，即使部分候选已经由 organizer 处理；调用方不能用“organized=true”代替完整性判断。

未实现项：Managed Agents 的文件上传与挂载、托管 memory store、30 天版本服务和 redact、真实并发 workflow、定时 cron、自动 session 导出、100 KB 文件治理和通用自然语言冲突判定。原始 transcript 仍是证据层；整理结果不是新的真相来源。

## 十、真实同题对照（2026-11-09）

运行模型为 `kimi-k2-0905-preview`，两组各 3 遍。每片先用一次 `ProtocolProbe`，探针均通过；下表不含探针调用。三份虚构 transcripts 同时包含当前部署标识 `EAST-42`、已作废的 `NORTH-17`、发布命令 `mvn -q clean verify` 和负责人“林岚”。

判分只看最终 FACT 行：三个当前事实标记每命中一个记一项；只要出现 `NORTH-17`，该遍就记旧值污染。行为结果不设退出门槛，协议失败、动态对账失败或非 FACT 行会中止实验。

| 对照组 | 遍数 | 当前事实保留 | 旧值污染 | LLM 总调用 | 累计请求总量 | 每遍请求均值 |
|---|---:|---:|---:|---:|---:|---:|
| 单次共享整理 | 3 | 9/9 | 0/3 | 3 | 3075 字节 | 1025.0 字节 |
| 独立分析 + 集中整理 | 3 | 9/9 | 0/3 | 12 | 10006 字节 | 3335.3 字节 |

独立组每遍都是 3 次 analyzer 加 1 次 organizer，对账均为 expected=3、processed=3、failures=0、organized=true。本次没有重试。两组的当前事实保留和旧值污染没有差异；独立组在这三份很短的材料上调用和请求字节更多，不能据此宣称节约成本，也不能外推到长 transcripts、其他模型或更大样本。

## 十一、运行与验证

```bash
scripts/dev.sh memorydynamicdream
scripts/dev.sh memorydynamicdreamreal "1 3"
scripts/dev.sh memorydynamicdreamreal "2 3"
mvn -q test -pl core
```

离线场景分五幕：分批与失败台账、版本合流、输入输出双向净化、只读消费、夜间窗口。真实入口分片 1 是单次共享整理，分片 2 是独立分析加集中整理，默认各 3 遍；缺 key、探针失败或协议失配都会明确失败。

实施计划：`docs/plans/2026-11-09-1431-feat-memory-dynamic-dreaming-plan.md`。
