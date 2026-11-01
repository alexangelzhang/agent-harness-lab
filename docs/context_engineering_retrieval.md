# Context engineering starts with retrieval（LangChain blog）

来源：https://www.langchain.com/blog/context-engineering-starts-with-retrieval

> 注：存档时原文抓取在「retrieve, offload, delegate」图 3 处截断，第 3 节（无关/过期上下文）之后的小节未收录。

## 要点

- Agent 的上下文窗口有限，检索会在三处出问题：低效搜索、一次性涌入过多上下文、无关或过期的上下文。
- Deep Agents harness 默认让搜索保持小步：内置文件系统工具先搜后读——glob 按文件名匹配、grep 找匹配行、read_file 分页读；超大工具结果会被卸载：全文存到文件，agent 只拿到路径和预览。
- 自定义检索工具（如 RAG）用「retrieve, offload, delegate」：结果存文件、返回路径、让子 agent 去读，主 agent 只看到相关部分。

## 检索的三类问题

把 agent 比作开卷考试：答案都在书里，但怎么高效找到对的那个并不简单。agent 检索时会遇到：

1. **低效搜索**：每次搜索都花 token 和时间。笨拙的搜索（打开每个文件看内容、反复改写同一个查询）会在找到有用信息之前就用搜索结果填满上下文。
2. **一次性涌入过多上下文**：一次搜索吐出完整数据导出或十几份文档，一步就填满窗口，没地方干活了。
3. **无关或过期上下文**：过时或离题的信息对模型来说和对的答案一样有说服力。一旦进了上下文窗，agent 会基于它继续构建，错误会传导到之后的每一步。

harness 的工作就是策略性地运用技术，让模型在正确的时机拿到正确的上下文。

## 示例：竞品分析 agent

每周一研究数百个竞品、按团队模板写报告。需要：找到上周关于 A 公司的笔记（文件系统检索）；在巨大的市场数据导出里查一个营收数字（大文件检索）；在新闻档案里搜新动向（语义检索）。三种检索、三类问题。

## 1. 低效搜索

最好的搜索是 agent 不用重复的搜索，给每种活配对的工具。

**先用内置文件系统工具**：按名字和关键词先筛（像开卷考试先翻索引而不是逐页读）。glob 按文件名模式匹配（如 `**/company-a*.md`），grep 在文件内部搜索而不加载进上下文（可返回匹配文件、匹配行、计数）。每个 deep agent 都自带这些工具（glob、grep、ls、read_file、write_file、edit_file），无论文件存在哪（agent state、本地盘、持久存储、沙箱）都走同一套后端。

**关键词不够时加检索工具**：grep 只匹配字面文本，会漏掉语义相关但措辞不同的内容（grep "Company A funding" 找不到标题为 "Company A closes $50M Series B" 的文章）。这时加一个坐在向量库上的检索工具，按含义匹配。检索工具也可以坐在 SQL 数据库（结构化查询）、搜索 API（网络新信息）、MCP server（别人建好的文档搜索）之上。

**让 agent 自己决定何时搜**：很多检索配置在开头搜一次然后作答，FAQ 机器人（两步 RAG）够用，研究 agent 不行——研究到一半发现新产品、需要它事先不知道的定价信息时，一次性检索就会漏。研究 agent 需要 agentic retrieval：agent 自己决定何时搜、搜什么、要不要继续。Deep Agents 默认就是这样：agent 在循环里跑，工具想调几次调几次。

**把搜索经验打包成 skill**：哪份索引、怎么措辞、引用格式——包成 skill。agent 平时只看到每个 skill 的名字和一行描述，任务用到时才加载完整指令（progressive disclosure），小众经验随用随取，不占日常上下文。

## 2. 一次性涌入过多上下文

对一次查询：agent 只需要市场导出里的一个数字（B 公司季度营收），加载全部 4 万行会填满窗口；检索工具每次返回 4 篇文章 × 5 家公司也会迅速膨胀。

- **大文件分页读**：read_file 默认一页 100 行，agent 可用 offset（从哪行开始）和 limit（读几行）跳到需要的部分——先 grep 定位，再只读匹配行附近的几行。
- **harness 卸载超大结果**：工具结果超过约 2 万 token 时，harness 把它存到 /large_tool_results/ 下的文件，只给 agent 路径加首尾几行预览；agent 再用 read_file 的 offset/limit 读回需要的部分。
- **自己的检索工具返回文件路径**：同样思路前置：每个结果写一个文件，只返回路径——「retrieve, offload, delegate」的第一部分。
