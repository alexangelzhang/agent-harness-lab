package com.example.harness.memory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆的可导航表示：每页都能回到来源记忆，原始 transcript 仍是证据层。
 * 页面正文总字节数受原始语料压缩比约束，链接修复不接受新的空目标。
 */
public final class MemoryWiki {

    /** 一张 Wiki 页；链接只存页面 id，是否存在由审计清单说明。 */
    public record Page(String id, String content, List<String> sourceMemoryIds,
                       List<String> links) {
        public Page {
            sourceMemoryIds = List.copyOf(sourceMemoryIds);
            links = List.copyOf(links);
        }
    }

    /** 页面写入或断链修复的决定。 */
    public record Decision(boolean accepted, String reason) {
    }

    private final MemoryStore store;
    private final long maxContentBytes;
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private long contentBytes;

    public MemoryWiki(MemoryStore store, MemoryTranscript transcript, double maxCorpusRatio) {
        this.store = store;
        long corpusBytes = transcript.entries().stream()
                .mapToLong(MemoryWiki::utf8Bytes).sum();
        this.maxContentBytes = (long) Math.floor(corpusBytes * maxCorpusRatio);
    }

    /** 建页前检查来源存在性和全库正文预算；失败时不留下半张页面。 */
    public Decision createPage(String id, String content, List<String> sourceMemoryIds,
                               List<String> links) {
        if (sourceMemoryIds.isEmpty()) {
            return new Decision(false, "Wiki 页面必须引用至少一条来源记忆");
        }
        if (sourceMemoryIds.stream().anyMatch(sourceId -> store.get(sourceId) == null)) {
            return new Decision(false, "Wiki 页面引用了不存在的来源记忆");
        }
        long pageBytes = utf8Bytes(content);
        Page previous = pages.get(id);
        long previousBytes = previous == null ? 0 : utf8Bytes(previous.content());
        long replacedBytes = contentBytes - previousBytes + pageBytes;
        if (replacedBytes > maxContentBytes) {
            return new Decision(false, "Wiki 正文超过配置的原始语料压缩比");
        }
        Page page = new Page(id, content, sourceMemoryIds, links);
        pages.put(id, page);
        contentBytes = replacedBytes;
        return new Decision(true, "页面已建立，来源与链接可审计");
    }

    /** 把断链重定向到已经存在的页面，其他目标一律拒绝。 */
    public Decision repairLink(String fromPageId, String missingTarget, String redirectTarget) {
        Page from = pages.get(fromPageId);
        if (from == null || !from.links().contains(missingTarget)) {
            return new Decision(false, "待修复断链不存在");
        }
        if (!pages.containsKey(redirectTarget)) {
            return new Decision(false, "重定向目标页面不存在");
        }
        List<String> links = new ArrayList<>(from.links());
        links.replaceAll(target -> target.equals(missingTarget) ? redirectTarget : target);
        pages.put(fromPageId, new Page(from.id(), from.content(), from.sourceMemoryIds(), links));
        return new Decision(true, "断链已重定向到现有页面");
    }

    public Page page(String id) {
        return pages.get(id);
    }

    /** 当前页面快照，供集中整理重建完整索引。 */
    public List<Page> pages() {
        return List.copyOf(pages.values());
    }

    /** 当前断链审计清单，格式为「来源页 -> 缺失页」。 */
    public List<String> brokenLinks() {
        List<String> broken = new ArrayList<>();
        for (Page page : pages.values()) {
            for (String target : page.links()) {
                if (!pages.containsKey(target)) {
                    broken.add(page.id() + " -> " + target);
                }
            }
        }
        return List.copyOf(broken);
    }

    private static long utf8Bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
