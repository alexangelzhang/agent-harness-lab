package com.example.harness.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryWikiTest {

    @Test
    void pageRequiresExistingSources() {
        MemoryStore store = new MemoryStore();
        MemoryTranscript transcript = new MemoryTranscript(List.of("出口与重试的原始记录"));
        MemoryWiki wiki = new MemoryWiki(store, transcript, 1.0);

        assertFalse(wiki.createPage("empty", "没有来源", List.of(), List.of()).accepted());
        assertFalse(wiki.createPage("ghost", "来源不存在", List.of("missing"), List.of()).accepted());

        Memory first = store.add(Memory.Scope.PROJECT, "owner", "出口在东侧",
                "run-1", List.of("出口"));
        Memory second = store.add(Memory.Scope.PROJECT, "owner", "失败后调整节奏",
                "run-1", List.of("重试"));
        assertTrue(wiki.createPage("index", "出口与重试经验",
                List.of(first.id(), second.id()), List.of("missing")).accepted());
        assertEquals(List.of(first.id(), second.id()), wiki.page("index").sourceMemoryIds());
        assertEquals(List.of("index -> missing"), wiki.brokenLinks());
    }

    @Test
    void wikiCannotGrowBeyondConfiguredCorpusRatio() {
        MemoryStore store = new MemoryStore();
        Memory source = store.add(Memory.Scope.PROJECT, "owner", "来源", "run-1", List.of());
        MemoryTranscript transcript = new MemoryTranscript(List.of("原始语料"));
        MemoryWiki wiki = new MemoryWiki(store, transcript, 0.1);

        MemoryWiki.Decision decision = wiki.createPage("large", "超过一个字节",
                List.of(source.id()), List.of());

        assertFalse(decision.accepted());
        assertTrue(decision.reason().contains("压缩比"));
    }

    @Test
    void replacingPageCountsOnlyItsFinalBytes() {
        MemoryStore store = new MemoryStore();
        Memory source = store.add(Memory.Scope.PROJECT, "owner", "来源", "run-1", List.of());
        MemoryTranscript transcript = new MemoryTranscript(List.of("原始语料"));
        MemoryWiki wiki = new MemoryWiki(store, transcript, 1.0);
        wiki.createPage("index", "三三三", List.of(source.id()), List.of());

        MemoryWiki.Decision replacement = wiki.createPage("index", "四四四",
                List.of(source.id()), List.of());

        assertTrue(replacement.accepted());
        assertEquals("四四四", wiki.page("index").content());
    }

    @Test
    void brokenLinkCanOnlyRedirectToExistingPage() {
        MemoryStore store = new MemoryStore();
        Memory source = store.add(Memory.Scope.PROJECT, "owner", "来源", "run-1", List.of());
        MemoryTranscript transcript = new MemoryTranscript(List.of("足够长的原始语料，用来容纳索引页面"));
        MemoryWiki wiki = new MemoryWiki(store, transcript, 1.0);
        wiki.createPage("index", "索引", List.of(source.id()), List.of());
        wiki.createPage("detail", "细节", List.of(source.id()), List.of("missing"));

        assertFalse(wiki.repairLink("detail", "missing", "still-missing").accepted());
        assertEquals(List.of("missing"), wiki.page("detail").links());
        assertEquals(List.of("detail -> missing"), wiki.brokenLinks());

        assertTrue(wiki.repairLink("detail", "missing", "index").accepted());
        assertEquals(List.of("index"), wiki.page("detail").links());
        assertTrue(wiki.brokenLinks().isEmpty());
    }
}
