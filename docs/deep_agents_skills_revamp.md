# 抄作业！！！LangChain 重构 Agent Skills：工具绑定、固定加载、会话热更新

来源：https://zhuanlan.zhihu.com/p/2098424671364445602

LangChain 工程师 Sydney Runkle 在 2026 年 10 月 7 日介绍了 Deep Agents 对 Skills 机制的一次重构。企业的 Skill 库从几十个扩大到数千个后，只靠名称和描述做渐进式披露已经不够用了。Agent 读取 Skill 时还要拿到配套工具；用户明确点名 Skill 时，不该再走一遍自动匹配；会话持续很久时，也要能发现刚刚更新的 Skill。

这次更新加入了工具绑定（tool binding）、固定 Skill（pinned skill）和会话内重载（mid-thread reloading）。它们解决了三个很具体的问题：工具与指令各走各的、显式调用仍要多跑一轮模型、长会话看不到 Skill 的最新变化。Deep Agents 还处理了 prompt 缓存和运行时权限。经过这次重构，Skills 已经从一种文件组织方式，变成了大型 Agent 系统运行时的一部分。

来源：Revamping Skills in Deep Agents

## Skill 与渐进式披露

Skill 是一个以 SKILL.md 为入口的目录（YAML 前置元数据填 name 和 description，目录里可放 scripts/、references/、assets/），遵循 Agent Skills 开放标准，基础 Skill 不一定非要由工程师编写。

它通过渐进式披露控制上下文，加载分三层：

1. 发现（discovery）：Agent 启动时只在系统 prompt 中看到所有 Skill 的 name 和 description。
2. 激活（activation）：任务匹配后，用 read_file 读取完整 SKILL.md。
3. 执行（execution）：按 Skill 指令工作，只在确有需要时读取脚本或参考文件。

一个尚未使用的 Skill 通常只占系统 prompt 中的一行，所以 Skill 库即使不断扩充，也不用把全部内容提前塞进上下文。

## 改动一：工具绑定（tool binding）

问题：很多 Skill 同时说明「任务怎么做」和「特定工具怎么调用」，有些工具模型读完说明后才能正确使用。过去 Skills 和工具分别披露，模型可能先找到工具过一会儿才读到说明，也可能读完 Skill 还得再搜一次工具。

做法：Skill 在 metadata.include_tools 中声明工具名或工具组标签。Agent 读取 Skill 之前看不到这些工具（提前调用会被当成未知工具）；读取完成后，Deep Agents 才把工具加入会话。模型先读使用方法，再拿到工具权限。

工具要交给 SkillsMiddleware 而不是直接传给 Agent：sources 指定扫描 Skills 的位置，tools 列出可能被引用的工具，中间件检查 include_tools，等相应 Skill 被读取后再开放工具。

缓存处理：较新的 Anthropic 和 OpenAI 模型允许会话中途增加工具。Skill 被读取后，Deep Agents 用一条新的系统消息加入绑定工具，不改前面已缓存的内容；不支持的模型仍把工具追加到下一次请求中。

简单场景直接列工具名；工具随 MCP 服务或用户权限动态变化时，可以写 call_tools、crm_tools 等标签，再由 SkillsMiddleware 调用解析函数：解析函数可以返回整个 MCP Server 的工具，也可以读取当前用户权限（经理能读能写预测，普通用户只能读）。权限在两处收紧：Skill 先选出任务需要的工具组，解析函数再按运行环境返回当前用户有权使用的工具。

## 改动二：固定 Skill（pinned skill）

问题：用户明确输入 /meeting-prep 之类命令时，如果还让模型读描述、判断匹配、调用 read_file，就多出一次模型往返，还可能选错 Skill。

做法：应用自己从消息或界面解析出 Skill 名称，通过 pinned_skills 交给中间件；下一次模型调用前，中间件把完整指令加入上下文。Deep Agents 不规定命令语法，应用可以用斜杠命令、按钮或其他界面。

固定后第一次调用时上下文里就有 Skill 指令，绑定工具同时加入，少跑一次模型，执行路径更稳定。每个固定 Skill 只作为带标签的消息加入一次，不改旧消息，prompt 缓存仍然有效。

## 改动三：会话内重载（mid-thread reloading）

问题：Deep Agents 默认在线程开始时扫描 Skills，元数据保存在 Agent 状态中，后面轮次复用。会话期间新增、修改、删除的 Skill 不会被发现。

做法：调用 Agent 时把 skills_metadata 设为 None（表示清除缓存的元数据，不是 Skill 列表为空），下一次运行重新扫描所有 Skill 来源，最新结果写回 Agent 状态。用户留在当前线程继续工作，不用重开会话。

代价：Skill 列表变化会改变系统 prompt，旧 prompt 缓存随之失效。但闲置线程的缓存通常早已超过提供商的有效期（几分钟到一小时），重新建立缓存的代价有限。刷新时机可以由应用控制：Skill 发布后、线程闲置一段时间后、用户主动 /reload 时。

## 总结

工具绑定只在 Skill 真正需要时进入上下文；工作流明确依赖哪个 Skill 时可以提前加载（固定）；会话持续很久也能跟上 Skill 库的变化（重载）。这些能力已进入最新版 deepagents。Agent Skills 采用开放标准，同一套 Skill 目录可以跨模型和 Agent 使用；工具绑定、固定加载和重载则由 Deep Agents 在运行时提供。
