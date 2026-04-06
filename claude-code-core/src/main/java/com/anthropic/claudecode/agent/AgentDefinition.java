/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code agent definition
 */
package com.anthropic.claudecode.agent;

import java.util.*;

/**
 * AgentDefinition - Defines an agent type's capabilities and behavior.
 */
public record AgentDefinition(
    String type,
    String name,
    String description,
    String systemPrompt,
    String contextInstructions,
    String defaultModel,
    List<String> defaultTools,
    List<Map<String, Object>> tools,
    int maxTurns,
    boolean supportsBackground,
    Map<String, Object> options
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String type;
        private String name;
        private String description;
        private String systemPrompt;
        private String contextInstructions;
        private String defaultModel = "glm-5";
        private List<String> defaultTools = List.of("Read", "Glob", "Grep");
        private List<Map<String, Object>> tools;
        private int maxTurns = 20;
        private boolean supportsBackground = true;
        private Map<String, Object> options = new HashMap<>();

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder contextInstructions(String contextInstructions) {
            this.contextInstructions = contextInstructions;
            return this;
        }

        public Builder defaultModel(String defaultModel) {
            this.defaultModel = defaultModel;
            return this;
        }

        public Builder defaultTools(List<String> defaultTools) {
            this.defaultTools = defaultTools;
            return this;
        }

        public Builder tools(List<Map<String, Object>> tools) {
            this.tools = tools;
            return this;
        }

        public Builder maxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        public Builder supportsBackground(boolean supportsBackground) {
            this.supportsBackground = supportsBackground;
            return this;
        }

        public Builder options(Map<String, Object> options) {
            this.options = options;
            return this;
        }

        public AgentDefinition build() {
            return new AgentDefinition(
                type, name, description, systemPrompt, contextInstructions,
                defaultModel, defaultTools, tools, maxTurns, supportsBackground, options
            );
        }
    }
}