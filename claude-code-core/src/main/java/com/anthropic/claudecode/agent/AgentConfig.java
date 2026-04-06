/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code agent types
 */
package com.anthropic.claudecode.agent;

import java.util.*;

/**
 * AgentConfig - Configuration for agent execution.
 */
public record AgentConfig(
    AgentApiClient apiClient,
    AgentToolExecutor toolExecutor,
    int maxTurns,
    int maxTokens,
    boolean allowBackground
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private AgentApiClient apiClient;
        private AgentToolExecutor toolExecutor;
        private int maxTurns = 20;
        private int maxTokens = 4096;
        private boolean allowBackground = true;

        public Builder apiClient(AgentApiClient apiClient) {
            this.apiClient = apiClient;
            return this;
        }

        public Builder toolExecutor(AgentToolExecutor toolExecutor) {
            this.toolExecutor = toolExecutor;
            return this;
        }

        public Builder maxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder allowBackground(boolean allowBackground) {
            this.allowBackground = allowBackground;
            return this;
        }

        public AgentConfig build() {
            return new AgentConfig(apiClient, toolExecutor, maxTurns, maxTokens, allowBackground);
        }
    }
}