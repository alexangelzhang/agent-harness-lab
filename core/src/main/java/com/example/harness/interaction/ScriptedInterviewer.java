package com.example.harness.interaction;

import java.util.Map;

/**
 * 剧本问答：按题目 id 回放预设的人的回答。
 *
 * <p>离线演示「人」这一侧：答案必须能被 {@link StructuredQuestion#resolve}
 * 校验——剧本里写错答案会当场炸，和真实 UI 的校验路径一致。</p>
 */
public final class ScriptedInterviewer implements Interviewer {

    private final Map<String, String> answers;

    public ScriptedInterviewer(Map<String, String> answers) {
        this.answers = Map.copyOf(answers);
    }

    @Override
    public String ask(StructuredQuestion question) {
        String answer = answers.get(question.id());
        if (answer == null) {
            throw new IllegalStateException("剧本没有准备题目 " + question.id() + " 的人答");
        }
        question.resolve(answer); // 校验：不在选项里就炸
        return answer;
    }
}
