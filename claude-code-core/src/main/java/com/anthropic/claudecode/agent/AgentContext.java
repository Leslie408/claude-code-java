/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code agent context
 */
package com.anthropic.claudecode.agent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AgentContext - Execution context for agents.
 */
public class AgentContext {

    private final AgentConfig config;
    private final Map<String, Object> state;
    private final String workingDirectory;
    private final List<String> outputFiles;

    public AgentContext(AgentConfig config) {
        this.config = config;
        this.state = new ConcurrentHashMap<>();
        this.workingDirectory = System.getProperty("user.dir");
        this.outputFiles = new ArrayList<>();
    }

    public AgentContext(AgentConfig config, String workingDirectory) {
        this.config = config;
        this.state = new ConcurrentHashMap<>();
        this.workingDirectory = workingDirectory;
        this.outputFiles = new ArrayList<>();
    }

    public AgentConfig config() {
        return config;
    }

    public String workingDirectory() {
        return workingDirectory;
    }

    public void setState(String key, Object value) {
        state.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getState(String key) {
        return (T) state.get(key);
    }

    public <T> T getState(String key, T defaultValue) {
        T value = getState(key);
        return value != null ? value : defaultValue;
    }

    public void addOutputFile(String path) {
        outputFiles.add(path);
    }

    public List<String> getOutputFiles() {
        return new ArrayList<>(outputFiles);
    }

    public Map<String, Object> getAllState() {
        return new HashMap<>(state);
    }
}