/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code agent types
 */
package com.anthropic.claudecode.agent;

/**
 * AgentToolResult - Result of a tool execution for agent.
 */
public record AgentToolResult(
    String toolId,
    String content,
    boolean isError
) {
    public static AgentToolResult success(String toolId, String content) {
        return new AgentToolResult(toolId, content, false);
    }

    public static AgentToolResult error(String toolId, String error) {
        return new AgentToolResult(toolId, error, true);
    }
}