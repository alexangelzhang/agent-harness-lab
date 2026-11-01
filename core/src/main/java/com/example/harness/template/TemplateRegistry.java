package com.example.harness.template;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 模板注册表 + 漂移检查：文章点名的头号挑战——实例化后与上游脱节。
 *
 * <p>工作区实例化时带上来源戳（模板名 + 版本），模板在注册表里继续
 * 演进（升版、修约定、补 sensor）。两边一对：工作区还拴在旧版上，
 * 上游的新约定它没有——漂移显形，不靠人翻 changelog 发现。</p>
 */
public final class TemplateRegistry {

    private final Map<String, HarnessTemplate> latest = new LinkedHashMap<>();

    public void register(HarnessTemplate template) {
        latest.put(template.name(), template);
    }

    public Optional<HarnessTemplate> latest(String name) {
        return Optional.ofNullable(latest.get(name));
    }

    /**
     * 漂移检查：工作区实例化自的模板版本 vs 注册表最新版本。
     *
     * @return 空 = 没漂；非空 = 漂移说明（工作区该 re-instantiate 了）
     */
    public Optional<String> driftCheck(TetheredWorkspace ws) {
        HarnessTemplate newest = latest.get(ws.templateName());
        if (newest == null) {
            return Optional.of("模板「" + ws.templateName()
                    + "」已下线或改名——" + ws.originTag() + " 的来源没了。");
        }
        if (newest.version() == ws.templateVersion()) {
            return Optional.empty();
        }
        return Optional.of(ws.originTag() + " 还拴在旧版，上游已发布 "
                + newest.name() + "@v" + newest.version() + "（新增约定 "
                + newest.conventions().size() + " 条）——re-instantiate "
                + "让工作区跟上，别让新约定只存在于模板里。");
    }
}
