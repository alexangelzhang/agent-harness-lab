package com.example.harness.toolbase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 哈希锚定编辑（OmO hashline-core 研读落地，见 docs/omo_orchestration_kibitzer.md）。
 *
 * <p>痛点：模型改代码靠逐字复述旧行，改不动 whitespace 差异、复述错一个字
 * 就整次编辑失败。这里的机制是给每行挂一个内容哈希——渲染给模型看的是
 * {@code 行号#哈希|内容}，编辑指令引用 {@code 行号#哈希} 而不是复述原文；
 * 落盘前逐条验锚：行号越界或哈希对不上当前内容（视图过期）整批拒绝，
 * 一个字符都不改（原子性）。</p>
 *
 * <p>与 OmO 原版的差异只有哈希算法：原版用 xxhash32 取模 256 查字典，
 * 这里用 FNV-1a 取模 256 出两位十六进制——机制不变：同一行内容同一个锚，
 * 内容变了锚就废；<b>全空白行用行号做种子</b>，不同位置的空行锚不同
 * （否则 replace 空行会撞锚）。编辑排序从文件尾部向头部应用，
 * 行号在应用过程中不失效；重叠区间在应用前检出拒绝。</p>
 */
public final class HashLineEditor {

    /** 一条行引用：行号（从 1 起）+ 两位哈希锚。 */
    public record LineRef(int lineNo, String hash) {

        private static final Pattern SYNTAX = Pattern.compile("(\\d+)#([0-9a-f]{2})");

        /** 解析 {@code "12#a3"} 形式的引用。 */
        public static LineRef parse(String text) {
            var m = SYNTAX.matcher(text.trim());
            if (!m.matches()) {
                throw new IllegalArgumentException("行引用格式应为 行号#哈希，收到：" + text);
            }
            return new LineRef(Integer.parseInt(m.group(1)), m.group(2));
        }
    }

    /** 编辑操作。end 只在 REPLACE 区间时用；APPEND/PREPEND 不带 pos 表示文件尾/头。 */
    public record Edit(Op op, LineRef pos, LineRef end, List<String> lines) {

        public enum Op { REPLACE, INSERT_AFTER, INSERT_BEFORE, APPEND, PREPEND }

        /** 替换单行。 */
        public static Edit replace(LineRef pos, List<String> lines) {
            return new Edit(Op.REPLACE, pos, null, lines);
        }

        /** 替换区间 [pos, end]。 */
        public static Edit replace(LineRef pos, LineRef end, List<String> lines) {
            return new Edit(Op.REPLACE, pos, end, lines);
        }

        /** 在 pos 行之后插入。 */
        public static Edit insertAfter(LineRef pos, List<String> lines) {
            return new Edit(Op.INSERT_AFTER, pos, null, lines);
        }

        /** 在 pos 行之前插入。 */
        public static Edit insertBefore(LineRef pos, List<String> lines) {
            return new Edit(Op.INSERT_BEFORE, pos, null, lines);
        }

        /** 文件尾部追加。 */
        public static Edit append(List<String> lines) {
            return new Edit(Op.APPEND, null, null, lines);
        }

        /** 文件头部前插。 */
        public static Edit prepend(List<String> lines) {
            return new Edit(Op.PREPEND, null, null, lines);
        }
    }

    /** 应用报告：新内容 + 空转编辑数（新旧一致，没动）。 */
    public record Report(String content, int noopEdits) {
    }

    /** 验锚失败：视图过期或行号越界，整批拒绝、原文不动。 */
    public static final class StaleEditException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        public StaleEditException(String message) {
            super(message);
        }
    }

    /** 编辑区间重叠。 */
    public static final class OverlapException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        public OverlapException(String message) {
            super(message);
        }
    }

    /**
     * 行哈希：去 \r、去行尾空白后做 FNV-1a；含字母/数字的行种子为 0，
     * 全空白行种子为行号（同内容空行在不同位置锚不同）。
     */
    public static String hashLine(int lineNo, String content) {
        String normalized = content.replace("\r", "").stripTrailing();
        boolean significant = normalized.codePoints()
                .anyMatch(cp -> Character.isLetterOrDigit(cp));
        int seed = significant ? 0 : lineNo;
        int h = 0x811c9dc5 ^ seed; // FNV-1a 32-bit，种子混入初始值
        for (int i = 0; i < normalized.length(); i++) {
            h ^= normalized.charAt(i);
            h *= 0x01000193;
        }
        return String.format("%02x", h & 0xff);
    }

    /** 渲染给模型看的带锚文本：{@code 行号#哈希|内容}。 */
    public static List<String> render(String content) {
        if (content.isEmpty()) {
            return List.of();
        }
        String[] raw = content.split("\n", -1);
        List<String> out = new ArrayList<>(raw.length);
        for (int i = 0; i < raw.length; i++) {
            out.add((i + 1) + "#" + hashLine(i + 1, raw[i]) + "|" + raw[i]);
        }
        return out;
    }

    /**
     * 应用一批编辑：先整批验锚（任何一条对不上就全拒，原文不动），
     * 再查区间重叠，最后按行号从尾到头应用。
     */
    public Report apply(String content, List<Edit> edits) {
        List<String> lines = content.isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(List.of(content.split("\n", -1)));

        // 第一关：整批验锚——原子性的来源
        List<String> stale = new ArrayList<>();
        for (Edit e : edits) {
            checkRef(lines, e.pos(), e.op().name(), stale);
            if (e.end() != null) {
                checkRef(lines, e.end(), e.op().name(), stale);
            }
        }
        if (!stale.isEmpty()) {
            throw new StaleEditException("编辑视图已过期，整批拒绝：" + String.join("；", stale));
        }

        // 第二关：REPLACE 区间互相重叠拒绝（应用前就检出，不改到一半才炸）
        List<Edit> replaces = edits.stream().filter(e -> e.op() == Edit.Op.REPLACE).toList();
        for (int i = 0; i < replaces.size(); i++) {
            for (int j = i + 1; j < replaces.size(); j++) {
                if (overlaps(replaces.get(i), replaces.get(j))) {
                    throw new OverlapException("替换区间重叠：第 "
                            + replaces.get(i).pos().lineNo() + " 行起 与 第 "
                            + replaces.get(j).pos().lineNo() + " 行起");
                }
            }
        }

        // 第三关：从尾到头应用，行号在应用过程中不失效
        List<Edit> sorted = new ArrayList<>(edits);
        sorted.sort(Comparator.comparingInt(HashLineEditor::sortLine).reversed());
        int noop = 0;
        for (Edit e : sorted) {
            List<String> before = List.copyOf(lines);
            switch (e.op()) {
                case REPLACE -> {
                    int from = e.pos().lineNo() - 1;
                    int to = (e.end() != null ? e.end().lineNo() : e.pos().lineNo()) - 1;
                    lines.subList(from, to + 1).clear();
                    lines.addAll(from, e.lines());
                }
                case INSERT_AFTER -> lines.addAll(e.pos().lineNo(), e.lines());
                case INSERT_BEFORE -> lines.addAll(e.pos().lineNo() - 1, e.lines());
                case APPEND -> lines.addAll(e.lines());
                case PREPEND -> lines.addAll(0, e.lines());
            }
            if (lines.equals(before)) {
                noop++;
            }
        }
        return new Report(String.join("\n", lines), noop);
    }

    private static void checkRef(List<String> lines, LineRef ref, String op,
                                 List<String> stale) {
        if (ref == null) {
            return;
        }
        if (ref.lineNo() < 1 || ref.lineNo() > lines.size()) {
            stale.add(op + " 引用的第 " + ref.lineNo() + " 行越界（文件共 "
                    + lines.size() + " 行）");
            return;
        }
        String actual = hashLine(ref.lineNo(), lines.get(ref.lineNo() - 1));
        if (!actual.equals(ref.hash())) {
            stale.add(op + " 第 " + ref.lineNo() + " 行锚对不上：期望 #" + ref.hash()
                    + "，当前 #" + actual + "（这行已被改动，重新渲染再改）");
        }
    }

    private static boolean overlaps(Edit a, Edit b) {
        int aFrom = a.pos().lineNo();
        int aTo = a.end() != null ? a.end().lineNo() : aFrom;
        int bFrom = b.pos().lineNo();
        int bTo = b.end() != null ? b.end().lineNo() : bFrom;
        return aFrom <= bTo && bFrom <= aTo;
    }

    private static int sortLine(Edit e) {
        if (e.pos() != null) {
            return e.pos().lineNo();
        }
        // APPEND 视同尾行之后（最大），PREPEND 视同第 1 行（最小）
        return e.op() == Edit.Op.APPEND ? Integer.MAX_VALUE : 1;
    }
}
