package com.mkpro.jarvis.core;

public abstract class AgentEvent {
    private final long timestamp;

    public AgentEvent() {
        this.timestamp = System.currentTimeMillis();
    }

    public long getTimestamp() {
        return timestamp;
    }
}
