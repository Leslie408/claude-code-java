/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code agent types
 */
package com.anthropic.claudecode.agent;

import java.util.*;

/**
 * ToolCall - Represents a tool use request from the agent.
 */
public record ToolCall(
    String id,
    String name,
    Map<String, Object> input
) {
    public static ToolCall of(String id, String name, Map<String, Object> input) {
        return new ToolCall(id, name, input != null ? input : Map.of());
    }
}