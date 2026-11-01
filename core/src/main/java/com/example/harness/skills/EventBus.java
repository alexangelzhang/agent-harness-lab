package com.example.harness.skills;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 事件总线：发布方与订阅者之间的解耦层（教程第 10 章）。
 *
 * <p>渲染、审计、指标、提问都不该写死在 agent 主循环里——
 * 它们订阅事件，agent 只负责 {@link #publish}。
 * 同步派发、单进程、无队列（生产换 Kafka/NATS 是另一回事，
 * 但「发布方不认识订阅方」这条语义先立住）。</p>
 *
 * <p>订阅按事件类型匹配（含超类型订阅），同一事件可发多个订阅者；
 * {@link #publishedCounts} 给出每类事件的发布计数，demo 收尾与排障都用得上。</p>
 */
public final class EventBus {

    private final Map<Class<?>, List<Consumer<?>>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, Long> publishedCounts = new ConcurrentHashMap<>();

    /** 订阅某类（含子类）事件。 */
    public <T> void on(Class<T> type, Consumer<T> subscriber) {
        subscribers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(subscriber);
    }

    /** 发布事件：所有匹配类型的订阅者按序收到；发布计数入账。 */
    @SuppressWarnings("unchecked")
    public void publish(Object event) {
        publishedCounts.merge(event.getClass().getSimpleName(), 1L, Long::sum);
        subscribers.forEach((type, subs) -> {
            if (type.isInstance(event)) {
                for (Consumer<?> sub : subs) {
                    ((Consumer<Object>) sub).accept(event);
                }
            }
        });
    }

    /** 每类事件的发布计数。 */
    public Map<String, Long> publishedCounts() {
        return Map.copyOf(publishedCounts);
    }
}
