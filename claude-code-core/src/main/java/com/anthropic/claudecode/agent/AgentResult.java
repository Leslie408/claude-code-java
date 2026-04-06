/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code agent result
 */
package com.anthropic.claudecode.agent;

import java.util.*;

/**
 * AgentResult - Result of agent execution.
 */
public record AgentResult(
    String agentId,
    boolean success,
    String content,
    String error,
    int turns,
    long durationMs,
    TokenUsage tokenUsage,
    List<String> outputFiles
) {
    public static AgentResult success(String agentId, String content, int turns, TokenUsage tokenUsage) {
        return new AgentResult(agentId, true, content, null, turns, 0, tokenUsage, List.of());
    }

    public static AgentResult failure(String agentId, String error) {
        return new AgentResult(agentId, false, null, error, 0, 0, null, List.of());
    }

    public AgentResult withDuration(long durationMs) {
        return new AgentResult(agentId, success, content, error, turns, durationMs, tokenUsage, outputFiles);
    }

    public AgentResult withOutputFiles(List<String> files) {
        return new AgentResult(agentId, success, content, error, turns, durationMs, tokenUsage, files);
    }

    public String toResultString() {
        if (success) {
            return content != null ? content : "Task completed successfully";
        }
        return "Error: " + (error != null ? error : "Unknown error");
    }
}