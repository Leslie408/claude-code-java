/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code agent registry
 */
package com.anthropic.claudecode.agent;

import java.util.*;

/**
 * AgentRegistry - Registry of agent definitions.
 */
public final class AgentRegistry {

    private AgentRegistry() {}

    private static final Map<String, AgentDefinition> DEFINITIONS = new LinkedHashMap<>();

    static {
        // Register built-in agent types
        registerBuiltInAgents();
    }

    private static void registerBuiltInAgents() {
        // General-purpose agent
        register(AgentDefinition.builder()
            .type("general-purpose")
            .name("General Purpose Agent")
            .description("General-purpose agent for complex multi-step tasks")
            .systemPrompt("""
                You are a specialized agent working on a specific task.
                Focus on completing the task efficiently and thoroughly.
                Use available tools as needed.
                Report your findings clearly when done.
                """)
            .defaultModel("glm-5")
            .defaultTools(List.of("Read", "Write", "Edit", "Glob", "Grep", "Bash"))
            .maxTurns(20)
            .build());

        // Explore agent
        register(AgentDefinition.builder()
            .type("Explore")
            .name("Code Explorer")
            .description("Fast agent for exploring codebases")
            .systemPrompt("""
                You are an exploration agent. Your job is to quickly search and understand codebases.
                Use Glob and Grep to find relevant files and patterns.
                Read files to understand their purpose.
                Summarize your findings clearly.
                Be thorough but efficient.
                """)
            .contextInstructions("""
                Explore the codebase to answer the user's question.
                Use "quick" thoroughness for simple lookups.
                Use "medium" thoroughness for moderate exploration.
                Use "very thorough" for comprehensive analysis.
                """)
            .defaultModel("glm-5")
            .defaultTools(List.of("Read", "Glob", "Grep"))
            .maxTurns(15)
            .build());

        // Plan agent
        register(AgentDefinition.builder()
            .type("Plan")
            .name("Planning Agent")
            .description("Software architect agent for implementation planning")
            .systemPrompt("""
                You are a planning agent. Your job is to analyze requirements and create implementation plans.
                Consider architectural trade-offs.
                Break down complex tasks into clear steps.
                Identify critical files and potential issues.
                Return a structured plan that can be executed.
                """)
            .defaultModel("glm-5")
            .defaultTools(List.of("Read", "Glob", "Grep"))
            .maxTurns(15)
            .build());

        // Claude Code guide agent
        register(AgentDefinition.builder()
            .type("claude-code-guide")
            .name("Claude Code Guide")
            .description("Agent for answering questions about Claude Code")
            .systemPrompt("""
                You are a helpful guide for Claude Code CLI.
                Answer questions about features, commands, and usage.
                Be concise and helpful.
                """)
            .defaultModel("glm-5")
            .defaultTools(List.of())
            .maxTurns(10)
            .build());
    }

    /**
     * Register an agent definition.
     */
    public static void register(AgentDefinition definition) {
        DEFINITIONS.put(definition.type(), definition);
    }

    /**
     * Get an agent definition by type.
     */
    public static AgentDefinition getDefinition(String type) {
        return DEFINITIONS.get(type);
    }

    /**
     * Check if an agent type exists.
     */
    public static boolean hasAgent(String type) {
        return DEFINITIONS.containsKey(type);
    }

    /**
     * Get all registered agent types.
     */
    public static Set<String> getAgentTypes() {
        return new HashSet<>(DEFINITIONS.keySet());
    }

    /**
     * Get all registered definitions.
     */
    public static Collection<AgentDefinition> getAllDefinitions() {
        return new ArrayList<>(DEFINITIONS.values());
    }
}