package com.example.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RunStore 单测：追加/读回、轮转、归档保留、坏行容忍、清空。 */
class RunStoreTest {

    @TempDir
    Path dir;

    private static RunRecord record(String runId) {
        return new RunRecord(runId, "agentloop", "任务-" + runId, "SUCCESS",
                List.of(new RunRecord.Step(1, "成果", "PASS", "", 5)),
                3, 100, System.currentTimeMillis());
    }

    @Test
    void appendAndReadBackRoundTrips() throws IOException {
        RunStore store = new RunStore(dir.resolve("runs.jsonl"));
        store.append(record("r1"));
        store.append(record("r2"));
        List<RunRecord> all = store.readAll();
        assertEquals(2, all.size());
        Optional<RunRecord> found = store.find("r2");
        assertTrue(found.isPresent());
        assertEquals("任务-r2", found.get().task());
        assertEquals(1, found.get().steps().size());
        assertEquals("成果", found.get().steps().get(0).output());
    }

    @Test
    void readEmptyStoreReturnsEmptyList() throws IOException {
        RunStore store = new RunStore(dir.resolve("nope").resolve("runs.jsonl"));
        assertTrue(store.readAll().isEmpty());
        assertTrue(store.find("any").isEmpty());
    }

    @Test
    void rotationArchivesOldFileButKeepsAllRecordsReadable() throws IOException {
        // 阈值压到极小：第二条追加前必定触发轮转
        RunStore store = new RunStore(dir.resolve("runs.jsonl"), 10, 5);
        store.append(record("r1"));
        store.append(record("r2"));
        store.append(record("r3"));
        Path archive = dir.resolve("archive");
        assertTrue(Files.isDirectory(archive), "超出阈值的活动文件必须转进 archive/");
        assertTrue(Files.list(archive).count() >= 1);
        // 读回横跨活动文件 + 归档，一条不丢、顺序按时间先后
        List<RunRecord> all = store.readAll();
        assertEquals(3, all.size());
        assertEquals("r1", all.get(0).runId());
        assertEquals("r3", all.get(2).runId());
    }

    @Test
    void retentionKeepsOnlyNewestArchives() throws IOException {
        RunStore store = new RunStore(dir.resolve("runs.jsonl"), 10, 2);
        for (int i = 1; i <= 6; i++) {
            store.append(record("r" + i));
        }
        List<Path> archives;
        try (var files = Files.list(dir.resolve("archive"))) {
            archives = files.filter(p -> p.getFileName().toString().startsWith("runs.jsonl-")).toList();
        }
        assertEquals(2, archives.size(), "保留上限 = 2，更老的归档被清掉");
        // 但读回仍完整：被删归档里的记录读不回来是「保留策略」而非丢数据——
        // 至少活动文件里的最近记录一定在
        assertFalse(store.readAll().isEmpty());
    }

    @Test
    void corruptLineSkippedWithoutCrashingWholeRead() throws IOException {
        Path file = dir.resolve("runs.jsonl");
        Files.writeString(file, """
                {"runId":"good","runner":"agentloop","task":"t","terminal":"SUCCESS","steps":[],"llmCalls":1,"durationMs":10,"timestamp":1}
                {这不是合法 JSON
                {"runId":"good2","runner":"agentloop","task":"t2","terminal":"SUCCESS","steps":[],"llmCalls":2,"durationMs":20,"timestamp":2}
                """);
        RunStore store = new RunStore(file);
        List<RunRecord> all = store.readAll();
        assertEquals(2, all.size(), "一条坏行跳过，其余照常读回");
        assertEquals("good2", all.get(1).runId());
    }

    @Test
    void clearRemovesActiveFileAndArchives() throws IOException {
        RunStore store = new RunStore(dir.resolve("runs.jsonl"), 10, 5);
        store.append(record("r1"));
        store.append(record("r2"));
        assertFalse(Files.list(dir.resolve("archive")).findAny().isEmpty());
        store.clear();
        assertTrue(store.readAll().isEmpty());
        assertFalse(Files.exists(store.file()));
        assertFalse(Files.isDirectory(dir.resolve("archive"))
                && Files.list(dir.resolve("archive")).findAny().isPresent());
    }
}
