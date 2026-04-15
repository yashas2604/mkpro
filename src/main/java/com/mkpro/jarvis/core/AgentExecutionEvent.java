package com.mkpro.jarvis.core;

public class AgentExecutionEvent extends AgentEvent {
    private final String message;

    public AgentExecutionEvent(String message) {
        super();
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
