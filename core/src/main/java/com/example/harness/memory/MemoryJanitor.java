package com.example.harness.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * 记忆清理：遗忘是控制面动作，不是缺陷（Memory 综述：写入/路由/整合/遗忘
 * 四动作里的第四个——「遗忘释放资源，避免过期信息继续污染」）。
 *
 * <p>{@link #sweep} 一条流程跑完三种清理：</p>
 * <ol>
 *   <li><b>到期</b>：validUntil 过了还挂着 ACTIVE → 置 EXPIRED——占着
 *       检索位却永远不该被注入；</li>
 *   <li><b>归档</b>：SUPERSEDED/EXPIRED 超过保留期 → 物理清除（{@code purge}），
 *       位置让给新记忆；</li>
 *   <li><b>确认</b>：报告里明示三类各动了多少，遗忘必须可审计——
 *       「什么时候应该忘记」和「什么时候记」同样要留痕。</li>
 * </ol>
 *
 * <p>默认值：保留期 30 天（{@code supersededRetentionMs}）。</p>
 */
public final class MemoryJanitor {

    /** 一次清理的报告：每类各动了多少、清了谁。 */
    public record Report(int expired, int archived, List<String> archivedIds) {

        @Override
        public String toString() {
            return "到期置 EXPIRED × " + expired + "，超保留期归档 × "
                    + archived + archivedIds;
        }
    }

    private static final long DEFAULT_RETENTION_MS = 30L * 24 * 3600 * 1000;

    private final long supersededRetentionMs;

    public MemoryJanitor() {
        this(DEFAULT_RETENTION_MS);
    }

    public MemoryJanitor(long supersededRetentionMs) {
        this.supersededRetentionMs = supersededRetentionMs;
    }

    /** 跑一遍清理，返回报告。可随时调用，幂等。 */
    public Report sweep(MemoryStore store) {
        long now = MemoryStore.now();
        int expired = 0;
        List<String> archivedIds = new ArrayList<>();
        for (Memory m : store.all()) {
            boolean overdue = m.status() == Memory.Status.ACTIVE
                    && m.validUntilMs() > 0 && now > m.validUntilMs();
            if (overdue) {
                store.expire(m.id());
                expired++;
                continue;
            }
            boolean deletable = (m.status() == Memory.Status.SUPERSEDED
                    || m.status() == Memory.Status.EXPIRED)
                    && now - m.createdAtMs() >= supersededRetentionMs;
            if (deletable) {
                archivedIds.add(m.id());
            }
        }
        int archived = 0;
        for (String id : archivedIds) {
            if (store.purge(id)) {
                archived++;
            }
        }
        return new Report(expired, archived, archivedIds);
    }
}
