package com.apop.investigation.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AgentControls {

    private final AtomicBoolean enabled;
    private final AtomicBoolean modelAvailable = new AtomicBoolean(true);

    public AgentControls(@Value("${investigation.agent.enabled:true}") boolean enabled) {
        this.enabled = new AtomicBoolean(enabled);
    }

    public boolean enabled() {
        return enabled.get();
    }

    public void setEnabled(boolean value) {
        enabled.set(value);
    }

    public boolean modelAvailable() {
        return modelAvailable.get();
    }

    public void setModelAvailable(boolean value) {
        modelAvailable.set(value);
    }
}
