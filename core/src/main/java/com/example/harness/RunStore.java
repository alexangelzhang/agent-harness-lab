package com.example.harness;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 运行档案库：JSONL 追加写、全量读回、按 runId 定位。
 * 一条运行一行 JSON——人可以直接打开文件看，也可以按行流式处理，
 * 不用建数据库就有了审计底账（生产环境再换正规存储，接口不变）。
 *
 * <p>生产硬化（2026-11-01，单测缺席期的机制底线）：</p>
 * <ul>
 *   <li><b>跨进程安全</b>：append 走 {@link FileChannel} 独占锁——多个 JVM
 *       共写一个底账文件也不会串行；进程内另有 synchronized 兜底；</li>
 *   <li><b>轮转</b>：活动文件超过 {@code rotateBytes}（默认 8MB）就改名进
 *       {@code archive/}，从空文件接着写，单文件不会无限膨胀；</li>
 *   <li><b>保留策略</b>：只留最近 {@code retainArchives} 份归档（默认 5），
 *       更老的删掉——审计有纵深，磁盘不爆；</li>
 *   <li><b>坏行容忍</b>：读时跳过解析不了的行并告警计数，一行坏数据
 *       不拖垮整个底账的读取。</li>
 * </ul>
 *
 * <p>观测与韧性共用同一套基建：档案里 steps + terminal 是审计视图，
 * 图版 checkpoint 存的是 state 快照——两者互补，前者答「发生了什么」，
 * 后者答「现场长什么样」。</p>
 */
public final class RunStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final long DEFAULT_ROTATE_BYTES = 8L * 1024 * 1024;
    private static final int DEFAULT_RETAIN_ARCHIVES = 5;

    private final Path file;
    private final long rotateBytes;
    private final int retainArchives;

    public RunStore(Path file) {
        this(file, DEFAULT_ROTATE_BYTES, DEFAULT_RETAIN_ARCHIVES);
    }

    public RunStore(Path file, long rotateBytes, int retainArchives) {
        this.file = file;
        this.rotateBytes = rotateBytes;
        this.retainArchives = retainArchives;
    }

    /** 追加一条运行档案。返回落盘路径（演示用）。 */
    public synchronized Path append(RunRecord run) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        rotateIfNeeded();
        ByteBuffer payload = ByteBuffer.wrap(
                (MAPPER.writeValueAsString(run) + "\n").getBytes(StandardCharsets.UTF_8));
        // 跨进程独占锁：锁住了才写，别的 JVM 在锁外排队（synchronized 只管进程内）
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = channel.lock();
            try {
                channel.position(channel.size());
                while (payload.hasRemaining()) {
                    channel.write(payload);
                }
            } finally {
                lock.release();
            }
        }
        return file;
    }

    /** 读回全部运行档案（活动文件 + 归档，按时间先后）。文件不存在视为空库。 */
    public List<RunRecord> readAll() throws IOException {
        List<RunRecord> out = new ArrayList<>();
        int skipped = 0;
        for (Path part : partsOldestFirst()) {
            skipped += readInto(part, out);
        }
        if (skipped > 0) {
            System.err.println("[RunStore] 跳过 " + skipped + " 条解析失败的档案行（"
                    + file + "）——底账其余部分不受影响，建议人工看一眼坏行。");
        }
        return out;
    }

    /** 按 runId 定位一条运行（回放/审计的入口）。 */
    public Optional<RunRecord> find(String runId) throws IOException {
        return readAll().stream().filter(r -> r.runId().equals(runId)).findFirst();
    }

    /** 清空档案：活动文件 + 全部归档一起删（演示从干净状态开始；生产慎用）。 */
    public void clear() throws IOException {
        Files.deleteIfExists(file);
        Path archive = archiveDir();
        if (Files.isDirectory(archive)) {
            try (Stream<Path> files = Files.list(archive)) {
                for (Path f : files.toList()) {
                    Files.deleteIfExists(f);
                }
            }
        }
    }

    public Path file() {
        return file;
    }

    // ---- 轮转与保留 ---------------------------------------------------------------

    /** 活动文件超阈值就归档，让位新文件。 */
    private void rotateIfNeeded() throws IOException {
        if (!Files.exists(file) || Files.size(file) < rotateBytes) {
            return;
        }
        Path archive = archiveDir();
        Files.createDirectories(archive);
        Path target = archive.resolve(file.getFileName() + "-" + STAMP.format(LocalDateTime.now()));
        for (int n = 1; Files.exists(target); n++) {
            target = archive.resolve(file.getFileName() + "-" + STAMP.format(LocalDateTime.now()) + "-" + n);
        }
        Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
        enforceRetention(archive);
    }

    /** 只留最近 retainArchives 份归档，更老的删。 */
    private void enforceRetention(Path archive) throws IOException {
        try (Stream<Path> files = Files.list(archive)) {
            List<Path> archives = files
                    .filter(p -> p.getFileName().toString().startsWith(file.getFileName().toString() + "-"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            for (int i = 0; i < archives.size() - retainArchives; i++) {
                Files.deleteIfExists(archives.get(i));
            }
        }
    }

    private Path archiveDir() {
        return file.getParent() == null
                ? Path.of("archive") : file.getParent().resolve("archive");
    }

    // ---- 读取 ---------------------------------------------------------------------

    /** 待读文件：归档（旧→新）在前，活动文件最后。 */
    private List<Path> partsOldestFirst() throws IOException {
        List<Path> parts = new ArrayList<>();
        Path archive = archiveDir();
        if (Files.isDirectory(archive)) {
            try (Stream<Path> files = Files.list(archive)) {
                files.filter(p -> p.getFileName().toString().startsWith(file.getFileName() + "-"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .forEach(parts::add);
            }
        }
        if (Files.exists(file)) {
            parts.add(file);
        }
        return parts;
    }

    /** 读一个文件进列表，返回跳过的坏行数。 */
    private int readInto(Path part, List<RunRecord> out) throws IOException {
        int skipped = 0;
        try (var lines = Files.lines(part)) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    out.add(MAPPER.readValue(line, RunRecord.class));
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    skipped++;
                }
            }
        }
        return skipped;
    }
}
