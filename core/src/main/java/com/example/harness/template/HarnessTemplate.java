package com.example.harness.template;

import com.example.harness.sensor.DiffSizeSensor;
import com.example.harness.sensor.FitnessSensor;
import com.example.harness.sensor.PlaceholderSensor;
import com.example.harness.sensor.RepeatPatternSensor;
import com.example.harness.sensor.Sensor;
import com.example.harness.sensor.SensorSuite;

import java.util.ArrayList;
import java.util.List;

/**
 * Harness 模板：一捆 guides + sensors，把 agent 拴在某个服务拓扑的
 * 结构、约定和技术栈上（Fowler：企业里少数几种拓扑覆盖 80% 需求，
 * service template 进化成 harness template）。
 *
 * <p>Ashby 必要多样性定律是模板的理论依据：<b>承诺一个拓扑 = 削减
 * 模型可产生空间的多样性</b>，约定越多，模型可跑偏的方向越少，
 * 全面 harness 才覆盖得过来。所以模板的核心数据就是两样：</p>
 * <ul>
 *   <li>{@link #conventions}：guides——这个拓扑不 negotiable 的约定
 *       （渲染成 AGENTS.md 段落，前馈）；</li>
 *   <li>{@link #sensors}：sensors——把约定里能执行的部分前馈成检查
 *       （反馈， FitnessSensor 的 rules 与 conventions 一一对应）。</li>
 * </ul>
 *
 * <p>文章点名的挑战也留着：实例化后与上游脱节（{@link TemplateRegistry}
 * 的 driftCheck）、版本与贡献问题（{@link #version} 只是起点）。</p>
 */
public final class HarnessTemplate {

    private final String name;
    private final int version;
    private final List<String> conventions;
    private final List<Sensor> sensors;

    public HarnessTemplate(String name, int version,
                           List<String> conventions, List<Sensor> sensors) {
        this.name = name;
        this.version = version;
        this.conventions = List.copyOf(conventions);
        this.sensors = List.copyOf(sensors);
    }

    public String name() {
        return name;
    }

    public int version() {
        return version;
    }

    /** 拓扑约定（guides 原料）。 */
    public List<String> conventions() {
        return conventions;
    }

    /** 从模板实例化一个拴住的工作区：guides 渲染 + sensor 套件配好。 */
    public TetheredWorkspace instantiate(String workspaceName) {
        return new TetheredWorkspace(workspaceName, this);
    }

    /** 渲染成 AGENTS.md 片段：模板头 + 逐条约定。 */
    public String renderGuides() {
        StringBuilder sb = new StringBuilder()
                .append("# ").append(name).append("（").append(versionTag())
                .append("）\n\n本项目属于「").append(name)
                .append("」拓扑，以下约定不 negotiable：\n");
        for (int i = 0; i < conventions.size(); i++) {
            sb.append(i + 1).append(". ").append(conventions.get(i)).append("\n");
        }
        return sb.toString();
    }

    String versionTag() {
        return name + "@v" + version;
    }

    /** 模板自带的 sensor 套件：guides 文本注入（撞车标注可用），
     *  计算型 sensor 全部注册进 PRE_COMMIT 层。 */
    SensorSuite suite() {
        SensorSuite suite = new SensorSuite(renderGuides(), 5);
        sensors.forEach(suite::add);
        return suite;
    }

    // ---- 三种企业级拓扑模板（覆盖 80% 需求的那几种）----

    /** API 业务服务：对外接口的契约、性能、可观测性。 */
    public static HarnessTemplate apiService() {
        List<String> conventions = List.of(
                "对外接口必须声明超时（性能红线：不许无限等）",
                "对外接口必须打 requestId 日志（可观测性约定）",
                "对外接口必须做参数校验（API 契约）",
                "交付物不许残留 TODO/FIXME 占位符");
        return new HarnessTemplate("api-service", 1, conventions, List.of(
                new PlaceholderSensor(),
                new DiffSizeSensor(),
                FitnessSensor.standard()));
    }

    /** 事件处理：消费端的可靠性约定。 */
    public static HarnessTemplate eventProcessor() {
        List<String> conventions = List.of(
                "消息消费必须幂等（重投不炸）",
                "消费失败必须重试并退避（不许静默丢消息）",
                "交付物不许残留 TODO/FIXME 占位符");
        return new HarnessTemplate("event-processor", 1, conventions, List.of(
                new PlaceholderSensor(),
                new FitnessSensor("事件适应度", List.of(
                        new FitnessSensor.FitnessRule(
                                "消息消费必须幂等（重投不炸）", "幂等"),
                        new FitnessSensor.FitnessRule(
                                "消费失败必须重试并退避", "重试")))));
    }

    /** 数据看板：查询类服务的约定。 */
    public static HarnessTemplate dashboard() {
        List<String> conventions = List.of(
                "查询必须带时间范围（不许全表拉）",
                "指标口径变动必须写进看板说明",
                "交付物不许残留 TODO/FIXME 占位符");
        List<Sensor> sensors = new ArrayList<>(List.of(
                new PlaceholderSensor(),
                new RepeatPatternSensor()));
        return new HarnessTemplate("dashboard", 1, conventions, sensors);
    }
}
