package com.example.harness.planning;

/**
 * Todo 拽回器（OmO 纪律层研读落地，见 docs/omo_orchestration_kibitzer.md）。
 *
 * <p>清单只是记录，纪律靠执行：模型在长任务里会空转——一轮过去了，
 * 清单上一项都没勾，它却觉得「差不多做完了」。本类盯住每一轮：
 * 一轮结束而清单仍有剩余且<b>这轮没有任何进展</b>，就产出一个
 * 拽回提示，把 agent 拽回清单。</p>
 *
 * <p>拽回不是无限唠叨：连续空转升级语气（先提醒、再严正），
 * 到 {@link #maxNags} 次封顶，之后闭嘴并留一条「需要人来看看」——
 * 拽不动说明任务本身卡住了，继续拽只是浪费 token。</p>
 */
public final class TodoEnforcer {

    private final TodoList todo;
    private final int maxNags;
    private int consecutiveDrift;
    private int totalNags;

    public TodoEnforcer(TodoList todo) {
        this(todo, 3);
    }

    /**
     * @param todo    被盯的清单
     * @param maxNags 连续空转的拽回上限；到顶后不再提示（任务卡死，不是态度问题）
     */
    public TodoEnforcer(TodoList todo, int maxNags) {
        this.todo = todo;
        this.maxNags = maxNags;
    }

    /** 清单有进展时由 harness 调用（勾掉一项、产出落盘等），空转计数清零。 */
    public void noteProgress() {
        consecutiveDrift = 0;
    }

    /**
     * 一轮结束时调用。返回 null 表示这轮不需要干预；
     * 返回文本就是要注入下一轮的拽回提示。
     */
    public String onTurnEnd() {
        if (todo.remaining() == 0) {
            return null; // 清单勾完了，没资格拽
        }
        consecutiveDrift++;
        if (consecutiveDrift > maxNags) {
            return null; // 拽了 maxNags 次还不动，闭嘴，让人来
        }
        totalNags++;
        String next = todo.items().stream()
                .filter(i -> !i.done())
                .findFirst().map(TodoList.Item::text).orElse("");
        if (consecutiveDrift == 1) {
            return "提醒：任务清单还剩 " + todo.remaining() + " 项没勾，"
                    + "下一项是「" + next + "」。继续干活，别提前宣布收工。";
        }
        if (consecutiveDrift == maxNags) {
            return "最后通牒：已连续 " + consecutiveDrift + " 轮没有进展，"
                    + "清单还剩 " + todo.remaining() + " 项。这一轮必须直接推进「"
                    + next + "」，否则按任务卡死上报。";
        }
        return "注意：已连续 " + consecutiveDrift + " 轮空转，清单还剩 "
                + todo.remaining() + " 项，先把「" + next + "」推进掉。";
    }

    /** 连续空转轮数（断言与台账用）。 */
    public int consecutiveDrift() {
        return consecutiveDrift;
    }

    /** 累计拽回次数。 */
    public int totalNags() {
        return totalNags;
    }

    /**
     * 是否已放弃拽回：拽到上限清单还没勾完——任务卡死，
     * 该由人而不是提示词介入。
     */
    public boolean givenUp() {
        return consecutiveDrift > maxNags && todo.remaining() > 0;
    }
}
