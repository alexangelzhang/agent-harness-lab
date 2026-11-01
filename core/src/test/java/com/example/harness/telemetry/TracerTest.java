package com.example.harness.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
/** Tracer 单测：span 父子链、traceId 过滤、JSONL 导出、双关幂等。 */
class TracerTest {

    @TempDir
    Path dir;

    @Test
    void spanTreeLinksParentAndChildrenById() {
        Tracer tracer = new Tracer();
        try (Tracer.SpanHandle root = tracer.startSpan("run").tag("runner", "test")) {
            root.log("开始");
            try (Tracer.SpanHandle child = tracer.startSpan("llm-call", root)) {
                child.log("拿到答复");
            }
        }
        assertEquals(2, tracer.spanCount());
        Tracer.Span rootSpan = tracer.spans().stream()
                .filter(s -> s.parentId() == null).findFirst().orElseThrow();
        Tracer.Span childSpan = tracer.spans().stream()
                .filter(s -> s.parentId() != null).findFirst().orElseThrow();
        assertEquals("run", rootSpan.name());
        assertEquals("llm-call", childSpan.name());
        assertEquals(rootSpan.spanId(), childSpan.parentId());
        assertEquals(rootSpan.traceId(), childSpan.traceId());
        assertTrue(rootSpan.durationMs() >= 0);
        assertEquals(1, rootSpan.logs().size());
        assertTrue(rootSpan.logs().get(0).startsWith("+"), "事件带相对时间戳前缀");
        assertTrue(rootSpan.logs().get(0).contains("开始"));
        assertEquals("test", rootSpan.tags().get("runner"));
    }

    @Test
    void traceFilterReturnsOnlyOneChain() {
        Tracer tracer = new Tracer();
        String firstTraceId;
        try (Tracer.SpanHandle root = tracer.startSpan("a")) {
            firstTraceId = root.traceId();
        }
        try (Tracer.SpanHandle root = tracer.startSpan("b")) {
            tracer.startSpan("b-child", root).close();
        }
        assertEquals(3, tracer.spanCount());
        assertEquals(1, tracer.trace(firstTraceId).size());
        assertEquals(2, tracer.trace(tracer.spans().get(2).traceId()).size());
    }

    @Test
    void exportJsonlWritesOneSpanPerLineAndAppendsAcrossCalls() throws Exception {
        Tracer tracer = new Tracer();
        try (Tracer.SpanHandle root = tracer.startSpan("run")) {
            tracer.startSpan("child", root).close();
        }
        Path out = dir.resolve("traces.jsonl");
        tracer.exportJsonl(out);
        tracer.exportJsonl(out);
        List<String> lines = Files.readAllLines(out);
        assertEquals(4, lines.size(), "导出走追加语义：两次导出各两条 span");
        // span 按结束顺序落盘（子先父后）：内容断言不看行序，整份文件一起查
        String all = String.join("\n", lines);
        assertTrue(all.contains("\"name\":\"run\""));
        assertTrue(all.contains("\"name\":\"child\""));
    }

    @Test
    void doubleCloseAddsSpanOnlyOnce() {
        Tracer tracer = new Tracer();
        Tracer.SpanHandle span = tracer.startSpan("once");
        span.close();
        span.close();
        assertEquals(1, tracer.spanCount());
    }

    @Test
    void unclosedSpanIsNotVisibleUntilClose() {
        Tracer tracer = new Tracer();
        Tracer.SpanHandle open = tracer.startSpan("pending");
        assertEquals(0, tracer.spanCount());
        assertNotNull(open.traceId());
        open.close();
        assertEquals(1, tracer.spanCount());
    }
}
