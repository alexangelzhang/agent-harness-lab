package com.example.harness.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程内 Tracer：span 树（traceId / spanId / parentId）+ 事件日志，
 * 导出 JSONL——和 RunStore 底账同一套「一行一条、人可直接看」的哲学。
 *
 * <p>与底账的分工：{@code RunRecord} 是<b>业务</b>审计视图（终态、步、成本），
 * span 是<b>工程</b>执行视图（哪个节点跑了多久、内部又打了几次 LLM、
 * 哪一步挂了）——排障时先用报表定位「哪类运行失败多」，再用 traceId
 * 下钻到这一条的 span 树看时间花在哪。</p>
 *
 * <p>导出格式不发明轮子：每行一个 span 对象（traceId/spanId/parentId/
 * name/startMs/endMs/durationMs/tags），OpenTelemetry Collector 的
 * {@code filelog} receiver 或任何脚本都能直接消费；要接 OTLP 时只换
 * 导出端，span 模型不变。</p>
 */
public final class Tracer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<Span> spans = new CopyOnWriteArrayList<>();

    /** 开一条根 span（没有父级）。 */
    public SpanHandle startSpan(String name) {
        return startSpan(name, null);
    }

    /** 开一条 span，挂在父级下面（父级结束与否不影响子级）。 */
    public SpanHandle startSpan(String name, SpanHandle parent) {
        String traceId = parent == null ? UUID.randomUUID().toString() : parent.traceId();
        return new SpanHandle(UUID.randomUUID().toString(), traceId,
                parent == null ? null : parent.spanId(), name);
    }

    /** 已记录的 span 数（含未结束的——快照导出演示用）。 */
    public int spanCount() {
        return spans.size();
    }

    /** 全部 span 的不可变拷贝。 */
    public List<Span> spans() {
        return List.copyOf(spans);
    }

    /** 按 traceId 过滤。 */
    public List<Span> trace(String traceId) {
        return spans.stream().filter(s -> s.traceId().equals(traceId)).toList();
    }

    /**
     * 把 span 全量导出成 JSONL 文件（一行一条，和底账同格式哲学）。
     * 带跨进程文件锁，多个 JVM 导到同一文件也不互相踩——导出即冷数据，
     * 不走 RunStore 的轮转（trace 文件按 traceId 查询，不该被切散）。
     */
    public Path exportJsonl(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = channel.lock();
            try {
                channel.position(channel.size());
                for (Span span : spans) {
                    channel.write(java.nio.ByteBuffer.wrap(
                            (MAPPER.writeValueAsString(span) + "\n")
                                    .getBytes(StandardCharsets.UTF_8)));
                }
            } finally {
                lock.release();
            }
        }
        return file;
    }

    /** 一条 span：父子靠 spanId/parentId 串，tags 装业务维度。 */
    public record Span(String spanId, String traceId, String parentId, String name,
                       long startMs, long endMs, List<String> logs,
                       Map<String, String> tags) {

        public long durationMs() {
            return endMs - startMs;
        }
    }

    /**
     * span 句柄：try-with-resources 结束——{@code try (var s = tracer.startSpan("node")) { ... }}
     * 结束时自动落进 tracer。{@link #log(String)} 记带相对时间戳的事件
     * （「拿到 LLM 答复」「护栏放行」这类排障锚点）。
     */
    public final class SpanHandle implements AutoCloseable {
        private final String spanId;
        private final String traceId;
        private final String parentId;
        private final String name;
        private final long startMs;
        private final List<String> logs = new ArrayList<>();
        private final Map<String, String> tags = new LinkedHashMap<>();
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

        private SpanHandle(String spanId, String traceId, String parentId, String name) {
            this.spanId = spanId;
            this.traceId = traceId;
            this.parentId = parentId;
            this.name = name;
            this.startMs = System.currentTimeMillis();
        }

        public String traceId() {
            return traceId;
        }

        public String spanId() {
            return spanId;
        }

        /** 打标签（业务维度：runner=reflection-graph、node=generator）。 */
        public SpanHandle tag(String key, String value) {
            tags.put(key, value);
            return this;
        }

        /** 记事件：相对起点的毫秒数 + 消息。 */
        public SpanHandle log(String message) {
            logs.add("+" + (System.currentTimeMillis() - startMs) + "ms " + message);
            return this;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            spans.add(new Span(spanId, traceId, parentId, name, startMs,
                    System.currentTimeMillis(), List.copyOf(logs), Map.copyOf(tags)));
        }
    }
}
