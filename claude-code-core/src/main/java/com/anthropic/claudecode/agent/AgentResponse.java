/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code agent types
 */
package com.anthropic.claudecode.agent;

import java.util.*;

/**
 * AgentResponse - Response from the API for an agent turn.
 */
public record AgentResponse(
    String id,
    String content,
    List<ToolCall> toolCalls,
    boolean isComplete,
    TokenUsage tokenUsage
) {
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public static AgentResponse complete(String content, TokenUsage usage) {
        return new AgentResponse(null, content, List.of(), true, usage);
    }

    public static AgentResponse withToolCalls(String id, String content, List<ToolCall> toolCalls, TokenUsage usage) {
        return new AgentResponse(id, content, toolCalls, false, usage);
    }
}