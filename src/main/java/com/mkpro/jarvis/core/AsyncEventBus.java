package com.mkpro.jarvis.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class AsyncEventBus implements IEventBus {
    private final Map<Class<?>, CopyOnWriteArrayList<Consumer<Object>>> subscribers = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    public <T extends AgentEvent> void publish(T event) {
        List<Consumer<Object>> handlers = subscribers.get(event.getClass());
        if (handlers == null) {
            return;
        }
        for (Consumer<Object> handler : handlers) {
            executor.submit(() -> handler.accept(event));
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends AgentEvent> void subscribe(Class<T> eventType, Consumer<T> subscriber) {
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add((Consumer<Object>) (Consumer<?>) subscriber);
    }

    @Override
    public void shutdown() {
        executor.shutdownNow();
    }
}
