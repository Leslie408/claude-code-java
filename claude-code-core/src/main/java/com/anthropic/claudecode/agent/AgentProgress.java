/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code agent types
 */
package com.anthropic.claudecode.agent;

/**
 * AgentProgress - Progress update during agent execution.
 */
public record AgentProgress(
    String agentId,
    String status,
    String message,
    int progress
) {
    public static AgentProgress starting(String agentId, String message) {
        return new AgentProgress(agentId, "starting", message, 0);
    }

    public static AgentProgress processing(String agentId, String message, int progress) {
        return new AgentProgress(agentId, "processing", message, progress);
    }

    public static AgentProgress toolCall(String agentId, String toolName) {
        return new AgentProgress(agentId, "tool", "Executing: " + toolName, -1);
    }

    public static AgentProgress complete(String agentId) {
        return new AgentProgress(agentId, "complete", "Task completed", 100);
    }
}