package com.mkpro.jarvis.core;

import java.util.function.Consumer;

public interface IEventBus {
    <T extends AgentEvent> void publish(T event);
    <T extends AgentEvent> void subscribe(Class<T> eventType, Consumer<T> subscriber);
    void shutdown();
}
