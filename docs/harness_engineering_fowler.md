# Harness engineering for coding agent users

来源：https://martinfowler.com/articles/harness-engineering.html

作者：Birgitta Böckeler（Thoughtworks Distinguished Engineer），2026-04-02。此版更新并取代了 2026 年 2 月的初版 memo。

## 什么是 harness

「harness」已成为一个速记词，指 AI agent 中除模型以外的一切：**Agent = Model + Harness**。在 coding agent 的有限上下文里，harness 一部分是内置的（系统 prompt、代码检索机制、编排系统），另一部分是用户为自己的用例和系统搭建的**外层 harness**。一个搭得好的外层 harness 有两个目标：提高 agent 一次做对概率，并提供反馈环在问题到达人眼前尽量自纠——最终减少评审负担、提高系统质量、少浪费 token。

## 前馈（Guides）与反馈（Sensors）

- **Guides（前馈控制）**：预判 agent 行为、在它行动前引导，提高第一次就做对的概率。如 AGENTS.md、Skills。
- **Sensors（反馈控制）**：在 agent 行动后观察、帮助自纠。特别强大的是输出为 LLM 消费优化的信号，比如自带修正指引的自定义 linter 消息——一种「正向 prompt injection」。

只有 feedback 的 agent 会重复犯同样的错；只有 feedforward 的 agent 编码了规则却从不知道规则有没有生效。两者都要。

## 计算型 vs 推断型

两类执行方式：

- **Computational（计算型）**：确定性、快、CPU 跑——测试、linter、类型检查、结构分析，毫秒到秒级，结果可靠。
- **Inferential（推断型）**：语义分析、AI 代码评审、「LLM as judge」，GPU/NPU 跑，更慢更贵、结果更不确定。

计算型 sensor 便宜到可以每次变更都跑；推断型用强模型时尤其能增加信任，但不适合每次提交都跑。

例子：编码约定（前馈/推断型 → AGENTS.md、Skills）；引导指令（前馈/两者）；代码修改（前馈/计算型 → OpenRewrite）；结构测试（反馈/计算型 → ArchUnit 检查模块边界违规）；评审指引（反馈/推断型 → Skills）。

## 转向循环（The steering loop）

人的工作是**通过迭代 harness 来转向 agent**：每当一个问题出现多次，就改进前馈和反馈控制，让问题将来更不容易发生。AI 也可以参与改进 harness：帮写结构测试、从观察到的模式生成规则草稿、搭自定义 linter、从代码考古生成 how-to 指南。

## 时机：quality left

检查要沿交付时间线按成本、速度和关键度分布：快的（linter、快测试、基础代码评审 agent）尽量左移，甚至在提交前；贵的（变异测试、大局观的代码评审）放在集成后的流水线。还有第三类：持续运行的漂移/健康 sensor（死代码检测、测试覆盖质量分析、依赖扫描），以及运行时反馈（监控 SLO 退化、AI judge 持续抽样响应质量）。

## 调节类别

harness 像 cybernetic 调速器，按「调节什么」分三类：

1. **可维护性 harness**：调节内部代码质量。目前最容易，现成工具多。计算型 sensor 可靠抓住结构性问题（重复代码、圈复杂度、缺测试、架构漂移、风格违规）；LLM 能部分处理需要语义判断的问题（语义重复、冗余测试、暴力修复、过度设计），但昂贵且 probabilistic；**误判问题、过度设计、误解指令这类高影响问题两者都抓不可靠**。如果人没把想要什么说清楚，正确性不在任何 sensor 的职权范围内。
2. **架构适应度 harness**：fitness function——把性能需求前馈为 Skill、用性能测试反馈；把可观测性约定写成 Skill、让 agent 反思可用的日志质量。
3. **行为 harness**：难点所在。目前多数人靠「功能规格（前馈）+ AI 生成的测试全绿/高覆盖（反馈）+ 手动测试」——这对 AI 生成的测试过于信任，还不够好。同事在「approved fixtures」模式上看到好结果，但只能选择性使用。

## Harnessability（可驾驭性）

不是所有代码库同样容易套上 harness：强类型语言自带类型检查 sensor；清晰的模块边界 afford 架构约束规则；Spring 这类框架隐式提高成功率。Ned Letcher 称之为「ambient affordances」：环境本身让 agent 可读、可导航、可处理的结构属性。新项目从第一天就能烤进去；遗留系统+技术债是最需要 harness 也最难建 harness 的地方。

## Harness 模板

企业里少数几种服务拓扑（API 业务服务、事件处理、数据看板）覆盖 80% 需求，本已固化成 service template，未来可能进化成 **harness 模板**：一捆 guides + sensors，把 coding agent 拴在某个拓扑的结构、约定和技术栈上。团队选技术栈时可能部分取决于已有哪些 harness。Ashby 必要多样性定律：调节器的多样性必须不少于被调节系统；承诺一个拓扑 = 削减模型可产生空间的多样性，让全面 harness 更可达成。挑战与 service template 类似：实例化后与上游脱节、版本与贡献问题，非确定性的 guides/sensors 更难测试。

## 人的角色

人类开发者本身就是隐性 harness：内化的约定、对复杂度的直觉、「我们这里不这么干」的判断、组织对齐（哪些债为业务原因被容忍）。Agent 没有这些：没有社会责任感、没有对 300 行函数的审美厌恶、没有组织记忆。Harness 是把人类经验外部化的尝试，但只能走这么远。目标不是完全消灭人的输入，而是**把人的输入引导到最重要的地方**。

## 起点与开放问题

OpenAI 团队的 harness：自定义 linter + 结构测试强制分层架构，定期「垃圾回收」扫描漂移并让 agent 提修复建议，结论「最难的挑战现在集中在设计环境、反馈环和控制系统」。Stripe Minions：pre-push 钩子按启发式跑相关 linter，强调 shift feedback left，blueprint 把 feedback sensor 编排进 agent 工作流。

开放问题：harness 长大过程中怎么保持 guides 和 sensors 一致不互相矛盾；指令与反馈信号方向不同时 agent 的取舍能信到什么程度；sensor 从不触发是质量好还是检测机制不够；怎么评估 harness 的覆盖与质量（类似代码覆盖率和变异测试之于测试）。

**Harness 工程正在成为一种持续的工程实践，不是一次性的配置。**
