/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code agent types
 */
package com.anthropic.claudecode.agent;

/**
 * TokenUsage - Token usage statistics.
 */
public record TokenUsage(
    int inputTokens,
    int outputTokens,
    int totalTokens
) {
    public static TokenUsage of(int input, int output) {
        return new TokenUsage(input, output, input + output);
    }

    public static TokenUsage empty() {
        return new TokenUsage(0, 0, 0);
    }
}