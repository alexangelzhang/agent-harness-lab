package com.example.harness.template;

import com.example.harness.sensor.CodeChange;
import com.example.harness.sensor.SensorSuite;

/**
 * 从模板实例化出的「拴住的工作区」：guides 已渲染、sensor 套件已配好、
 * 带来源戳（实例化自哪个模板的哪一版）。戳是漂移检查（drift check）的
 * 地基——文章点名的头号挑战就是「实例化后与上游模板脱节」。
 *
 * @param workspaceName   工作区名
 * @param templateName    来源模板
 * @param templateVersion 实例化时的模板版本（之后模板升版，这里不会自己变）
 * @param guides          渲染好的 AGENTS.md 片段（前馈层就位）
 * @param suite           配好 guides 撞车标注的 sensor 套件（反馈层就位）
 */
public record TetheredWorkspace(String workspaceName, String templateName,
                                int templateVersion, String guides,
                                SensorSuite suite) {

    TetheredWorkspace(String workspaceName, HarnessTemplate template) {
        this(workspaceName, template.name(), template.version(),
                template.renderGuides(), template.suite());
    }

    /** 来源戳：workspace@template@vN。 */
    public String originTag() {
        return workspaceName + "@" + templateName + "@v" + templateVersion;
    }

    /** 对一份产出跑模板的 sensor 套件（runIndex 从 1 计）。 */
    public SensorSuite.Report inspect(CodeChange change, int runIndex) {
        return suite.run(change, runIndex);
    }
}
