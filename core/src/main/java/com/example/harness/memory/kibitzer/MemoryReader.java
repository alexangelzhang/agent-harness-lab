package com.example.harness.memory.kibitzer;

import com.example.harness.memory.Memory;
import com.example.harness.memory.MemoryStore;
import java.util.List;

/**
 * 侧车眼里的记忆库：只有检索和审计两个读口。
 *
 * <p>Kibitzer 的「零写入」不靠约定靠类型——它拿到的依赖就是这个接口，
 * {@link MemoryStore} 的 add / merge / supersede / purge 在编译期就够不到。
 * 断言「侧车全程零写入」因此是结构性的，不需要运行时埋计数器。</p>
 */
public interface MemoryReader {

    /** 关键词检索（与主链路同一个打分器）。 */
    List<MemoryStore.Hit> search(String query);

    /** 全量记忆（含历史状态，自证与审计用）。 */
    List<Memory> all();

    /** 把真实记忆库包成只读视图。 */
    static MemoryReader of(MemoryStore store) {
        return new MemoryReader() {
            @Override
            public List<MemoryStore.Hit> search(String query) {
                return store.search(query);
            }

            @Override
            public List<Memory> all() {
                return store.all();
            }
        };
    }
}
