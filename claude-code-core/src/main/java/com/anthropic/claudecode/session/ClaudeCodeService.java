/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code service management
 */
package com.anthropic.claudecode.session;

import com.anthropic.claudecode.*;
import com.anthropic.claudecode.message.*;
import com.anthropic.claudecode.permission.PermissionMode;
import com.anthropic.claudecode.types.SDKMessage;
import com.anthropic.claudecode.tools.*;

import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Claude Code service for managing API interactions and sessions.
 */
public class ClaudeCodeService {

    private final ClaudeCodeConfig config;
    private final Map<String, ClaudeSession> sessions = new ConcurrentHashMap<>();

    public ClaudeCodeService(ClaudeCodeConfig config) {
        this.config = config;
    }

    /**
     * Create a new session.
     */
    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        ClaudeSession session = new ClaudeSession(
            sessionId,
            config.apiKey(),
            config.model(),
            config.tools(),
            config.systemPrompt()
        );
        sessions.put(sessionId, session);
        return sessionId;
    }

    /**
     * Get session by ID.
     */
    public ClaudeSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * End a session.
     */
    public void endSession(String sessionId) {
        ClaudeSession session = sessions.remove(sessionId);
        if (session != null) {
            session.end();
        }
    }

    /**
     * Send a message in a session.
     */
    public Flux<SDKMessage> sendMessage(String sessionId, String prompt) {
        ClaudeSession session = sessions.get(sessionId);
        if (session == null) {
            return Flux.error(new IllegalArgumentException("Session not found: " + sessionId));
        }

        // Convert session messages to SDK messages
        return session.sendMessage(prompt)
            .map(msg -> SDKMessage.fromMessage(msg));
    }

    /**
     * Send tool results in a session.
     */
    public Flux<SDKMessage> sendToolResults(String sessionId, List<ContentBlock.ToolResult> toolResults) {
        ClaudeSession session = sessions.get(sessionId);
        if (session == null) {
            return Flux.error(new IllegalArgumentException("Session not found: " + sessionId));
        }

        return session.sendToolResults(toolResults)
            .map(msg -> SDKMessage.fromMessage(msg));
    }

    /**
     * Get configuration.
     */
    public ClaudeCodeConfig getConfig() {
        return config;
    }

    /**
     * Configuration for Claude Code service.
     */
    public static class ClaudeCodeConfig {
        private final String apiKey;
        private final String model;
        private final String systemPrompt;
        private final List<Tool<?, ?, ?>> tools;
        private final Integer maxTurns;
        private final Integer maxTokens;
        private final PermissionMode permissionMode;

        private ClaudeCodeConfig(Builder builder) {
            this.apiKey = builder.apiKey;
            this.model = builder.model;
            this.systemPrompt = builder.systemPrompt;
            this.tools = builder.tools;
            this.maxTurns = builder.maxTurns;
            this.maxTokens = builder.maxTokens;
            this.permissionMode = builder.permissionMode;
        }

        public String apiKey() { return apiKey; }
        public String model() { return model; }
        public String systemPrompt() { return systemPrompt; }
        public List<Tool<?, ?, ?>> tools() { return tools; }
        public Integer maxTurns() { return maxTurns; }
        public Integer maxTokens() { return maxTokens; }
        public PermissionMode permissionMode() { return permissionMode; }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String apiKey;
            private String model = "glm-5";
            private String systemPrompt;
            private List<Tool<?, ?, ?>> tools = List.of();
            private Integer maxTurns = 10;
            private Integer maxTokens = 4096;
            private PermissionMode permissionMode = PermissionMode.DEFAULT;

            public Builder apiKey(String apiKey) {
                this.apiKey = apiKey;
                return this;
            }

            public Builder model(String model) {
                this.model = model;
                return this;
            }

            public Builder systemPrompt(String systemPrompt) {
                this.systemPrompt = systemPrompt;
                return this;
            }

            public Builder tools(List<Tool<?, ?, ?>> tools) {
                this.tools = tools;
                return this;
            }

            public Builder maxTurns(Integer maxTurns) {
                this.maxTurns = maxTurns;
                return this;
            }

            public Builder maxTokens(Integer maxTokens) {
                this.maxTokens = maxTokens;
                return this;
            }

            public Builder permissionMode(PermissionMode permissionMode) {
                this.permissionMode = permissionMode;
                return this;
            }

            public ClaudeCodeConfig build() {
                return new ClaudeCodeConfig(this);
            }
        }
    }
}