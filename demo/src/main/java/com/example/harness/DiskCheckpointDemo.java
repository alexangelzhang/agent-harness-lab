package com.example.harness;

import com.example.langgraph4j.llm.CountingLlm;
import com.example.langgraph4j.llm.ScriptedLlm;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.FileSystemSaver;
import org.bsc.langgraph4j.serializer.std.StdStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 磁盘 checkpoint 演示：进程重启后从磁盘恢复挂起的长任务（文章 #11）。
 *
 * <p>MemorySaver 的 checkpoint 只活在堆里，进程一退全丢。
 * langgraph4j 自带的 {@link FileSystemSaver} 把 checkpoint 序列化到磁盘，
 * 这里演示完整闭环：第一轮跑到审批挂起点退出（模拟崩溃/下班）→
 * <b>全新进程</b>（新的图实例、新的 saver 实例）从同一个 checkpoint 文件
 * resume → 跑完拿到终态。</p>
 *
 * <p>状态序列化用 {@link StdStateSerializer} 的最简实现（Java 原生序列化）：
 * 生产换 JSON/Protobuf 是换 serializer 的事，骨架不变。</p>
 *
 * <p>运行：{@code mvn exec:java@checkpoint}。</p>
 */
public class DiskCheckpointDemo {

    private static final Path CKPT_DIR = Path.of("runs", "checkpoints", "demo");
    private static final String DRAFT = "draft";
    private static final String AWAIT = "await_signoff";
    private static final String FINALIZE = "finalize";

    /** 图状态：全部用 String/Boolean，天然可序列化。 */
    public static final class CkptState extends AgentState {
        static final Map<String, Channel<?>> SCHEMA = Map.of(
                "task", Channels.base(() -> ""),
                "draft", Channels.base(() -> ""),
                "approved", Channels.base(() -> false),
                "verdict", Channels.base(() -> ""));

        CkptState(Map<String, Object> initData) {
            super(initData);
        }

        String task() {
            return this.<String>value("task").orElse("");
        }
    }

    /** 最简状态序列化器：Java 原生序列化整个 state map。 */
    static final class DemoStateSerializer extends StdStateSerializer<CkptState> {
        DemoStateSerializer() {
            super(CkptState::new);
        }

        @Override
        public void writeData(Map<String, Object> data, ObjectOutput out) throws IOException {
            out.writeObject(new LinkedHashMap<>(data));
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> readData(ObjectInput in) throws IOException, ClassNotFoundException {
            return (Map<String, Object>) in.readObject();
        }
    }

    private final CountingLlm llm;

    DiskCheckpointDemo(CountingLlm llm) {
        this.llm = llm;
    }

    Map<String, Object> draft(CkptState state) {
        String draft = llm.complete("给任务起个草案：" + state.task());
        System.out.println("  [draft] 模型产出草案：" + draft.strip());
        return Map.of("draft", draft.strip());
    }

    /** 挂起节点：resume 后执行到这里 = 人工已签字。 */
    Map<String, Object> awaitSignoff(CkptState state) {
        System.out.println("  [await_signoff] 人工已签字，草案进入终稿");
        return Map.of("approved", true);
    }

    Map<String, Object> finalizeNode(CkptState state) {
        String verdict = llm.complete("把草案定稿：" + state.value("draft").orElse(""));
        System.out.println("  [finalize] 终稿：" + verdict.strip());
        return Map.of("verdict", verdict.strip());
    }

    StateGraph<CkptState> build() throws Exception {
        return new StateGraph<>(CkptState.SCHEMA, CkptState::new)
                .addNode(DRAFT, node_async(this::draft))
                .addNode(AWAIT, node_async(this::awaitSignoff))
                .addNode(FINALIZE, node_async(this::finalizeNode))
                .addEdge(StateGraph.START, DRAFT)
                .addEdge(DRAFT, AWAIT)
                .addEdge(AWAIT, FINALIZE)
                .addEdge(FINALIZE, StateGraph.END);
    }

    /** 每个「进程」都新建图 + saver 实例，只共享磁盘目录——这才是重启。 */
    private static void process(String role, CountingLlm llm) throws Exception {
        var compiled = new DiskCheckpointDemo(llm).build().compile(CompileConfig.builder()
                .checkpointSaver(new FileSystemSaver(CKPT_DIR, new DemoStateSerializer()))
                .interruptBefore(AWAIT)
                .build());
        var config = RunnableConfig.builder().threadId("long-task-1").build();
        if (role.equals("process-1")) {
            compiled.invoke(GraphInput.args(Map.of("task", "写支付中台季度复盘")), config)
                    .ifPresent(x -> System.out.println("  [挂起] 等人工签字，checkpoint 已落盘"));
        } else {
            compiled.invoke(GraphInput.resume(), config)
                    .ifPresent(x -> System.out.println("  [恢复完成] 终态=" + x.<String>value("verdict").orElse("")));
        }
    }

    public static void main(String[] args) throws Exception {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("磁盘 checkpoint：进程重启，挂起的长任务从盘上接着跑");
        System.out.println(line);

        // 清空旧 checkpoint，保证演示可重复
        if (Files.exists(CKPT_DIR)) {
            try (Stream<Path> walk = Files.walk(CKPT_DIR)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }

        var llm = new CountingLlm(new ScriptedLlm()
                .says("草案：季度复盘——流水涨 18%，事故 2 起，下季重点稳稳定性。")
                .says("终稿：流水涨 18%（新渠道贡献一半），两起事故均在 30 分钟内回滚，下季重心：稳定性专项 + 渠道成本复核。"));

        System.out.println("── 进程 1：起草案 → 挂起等签字 ──");
        process("process-1", llm);
        boolean fileOnDisk = Files.list(CKPT_DIR).anyMatch(p -> p.toString().endsWith("-saver.bin"));
        System.out.println("  [进程 1 退出] checkpoint 文件已落盘：" + fileOnDisk);
        System.out.println();

        System.out.println("── 进程 2（全新实例）：从磁盘 resume ──");
        process("process-2", llm);
        System.out.println();
        System.out.println(line);
        System.out.println(fileOnDisk
                ? "收尾：checkpoint 在盘上，进程只是临时工——人下班，任务不下班。"
                : "!! checkpoint 没落盘");
        System.out.println(line);
        System.exit(0);
    }
}
