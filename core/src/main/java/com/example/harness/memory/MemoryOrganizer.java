package com.example.harness.memory;

import java.util.ArrayList;
import java.util.List;

/** 受控集中整理：去重写记忆，并提供 Wiki 索引、移动存根与冲突保留操作。 */
public final class MemoryOrganizer implements MemoryDreamingWorkflow.Organizer {

    private final MemoryStore store;
    private final MemoryWiki wiki;

    public MemoryOrganizer(MemoryStore store, MemoryWiki wiki) {
        this.store = store;
        this.wiki = wiki;
    }

    /** 相同正文只保留一条，来源不再重复追加。 */
    @Override
    public void organize(List<MemoryDreamingWorkflow.Analysis> candidates) {
        for (MemoryDreamingWorkflow.Analysis candidate : candidates) {
            boolean duplicate = store.all().stream()
                    .anyMatch(memory -> memory.content().equals(candidate.content()));
            if (!duplicate) {
                store.add(Memory.Scope.PROJECT, "memory-dreaming", candidate.content(),
                        candidate.inputId(), List.of("dreaming"));
            }
        }
    }

    /** 移动页面但保留原地址，旧页正文只指向新页。 */
    public boolean movePage(String fromPageId, String targetPageId) {
        MemoryWiki.Page from = wiki.page(fromPageId);
        if (from == null || wiki.page(targetPageId) != null) {
            return false;
        }
        MemoryWiki.Decision created = wiki.createPage(targetPageId, from.content(),
                from.sourceMemoryIds(), from.links());
        if (!created.accepted()) {
            return false;
        }
        List<String> stubLinks = new ArrayList<>(from.links());
        if (!stubLinks.contains(targetPageId)) {
            stubLinks.add(targetPageId);
        }
        return wiki.createPage(fromPageId, "MOVED TO " + targetPageId,
                from.sourceMemoryIds(), stubLinks).accepted();
    }

    /** 重建有效页索引，排除索引自身、已经消失的目标和移动存根。 */
    public boolean rebuildIndex(String indexPageId, List<String> sourceMemoryIds) {
        List<String> targets = wiki.pages().stream()
                .filter(page -> !page.id().equals(indexPageId))
                .filter(page -> !page.content().startsWith("MOVED TO "))
                .map(MemoryWiki.Page::id)
                .toList();
        String content = targets.isEmpty() ? "空索引" : String.join("\n", targets);
        return wiki.createPage(indexPageId, content, sourceMemoryIds, targets).accepted();
    }

    /** 无法判定时并列保留双方值、日期和来源，不生成第三个猜测值。 */
    public boolean recordConflict(String pageId, String leftValue, String rightValue,
                                  String date, String source, List<String> sourceMemoryIds) {
        String content = "未判定冲突\n左值：" + leftValue + "\n右值：" + rightValue
                + "\n日期：" + date + "\n来源：" + source;
        return wiki.createPage(pageId, content, sourceMemoryIds, List.of()).accepted();
    }
}
