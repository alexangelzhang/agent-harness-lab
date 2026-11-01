package com.example.harness.sensor;

import java.util.List;

/**
 * 感知器：agent 行动<b>之后</b>观察产出、帮助自纠（Fowler：Sensors，
 * 反馈控制）。与 Guides（前馈，行动前引导）对称，缺一不可——
 * 只有 feedback 会重复犯同样的错，只有 feedforward 不知道规则有没有生效。
 *
 * <p>两类执行方式（文章的硬分界）：</p>
 * <ul>
 *   <li>{@link Kind#COMPUTATIONAL}：确定性、毫秒级、CPU 跑（测试/lint/
 *       结构检查）——便宜到每次变更都跑得起；</li>
 *   <li>{@link Kind#INFERENTIAL}：语义判断（LLM 评审）——更慢更贵更
 *       不确定，不适合每次提交都跑，抽样即可。kind 标在接口上，
 *       编排层按它决定时机（quality left）。</li>
 * </ul>
 */
public interface Sensor {

    /** 感知器的执行方式，决定它该在什么时机跑。 */
    enum Kind { COMPUTATIONAL, INFERENTIAL }

    String name();

    Kind kind();

    /** 检查一份变更，返回发现（可能为空——空是好的）。 */
    List<Finding> check(CodeChange change);
}
