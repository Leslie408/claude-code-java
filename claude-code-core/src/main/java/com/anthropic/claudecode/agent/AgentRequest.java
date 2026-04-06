/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code agent request/response types
 */
package com.anthropic.claudecode.agent;

import java.util.*;

/**
 * AgentRequest - Request to execute an agent task.
 */
public record AgentRequest(
    String agentType,
    String prompt,
    String description,
    String model,
    List<String> tools,
    String workingDirectory
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String agentType = "general-purpose";
        private String prompt;
        private String description;
        private String model;
        private List<String> tools;
        private String workingDirectory;

        public Builder agentType(String agentType) {
            this.agentType = agentType;
            return this;
        }

        public Builder prompt(String prompt) {
            this.prompt = prompt;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder tools(List<String> tools) {
            this.tools = tools;
            return this;
        }

        public Builder workingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public AgentRequest build() {
            return new AgentRequest(agentType, prompt, description, model, tools, workingDirectory);
        }
    }
}