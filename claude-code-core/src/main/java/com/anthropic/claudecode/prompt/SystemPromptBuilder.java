/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code system prompt builder
 */
package com.anthropic.claudecode.prompt;

import java.util.*;
import java.util.stream.Collectors;

import com.anthropic.claudecode.Tool;
import com.anthropic.claudecode.permission.PermissionMode;

/**
 * SystemPromptBuilder - Dynamically builds the system prompt.
 *
 * <p>The system prompt is constructed from multiple sections:
 * <ul>
 *   <li>Base instructions</li>
 *   <li>Tool descriptions</li>
 *   <li>Environment context</li>
 *   <li>Permission mode instructions</li>
 *   <li>Custom instructions from CLAUDE.md</li>
 * </ul>
 */
public class SystemPromptBuilder {

    private String cwd;
    private List<Tool<?, ?, ?>> tools;
    private PermissionMode permissionMode;
    private Map<String, Object> context;
    private List<String> customInstructions;
    private boolean verbose;

    public SystemPromptBuilder() {
        this.context = new HashMap<>();
        this.customInstructions = new ArrayList<>();
        this.verbose = false;
    }

    public SystemPromptBuilder cwd(String cwd) {
        this.cwd = cwd;
        return this;
    }

    public SystemPromptBuilder tools(List<Tool<?, ?, ?>> tools) {
        this.tools = tools;
        return this;
    }

    public SystemPromptBuilder permissionMode(PermissionMode mode) {
        this.permissionMode = mode;
        return this;
    }

    public SystemPromptBuilder context(String key, Object value) {
        this.context.put(key, value);
        return this;
    }

    public SystemPromptBuilder customInstruction(String instruction) {
        if (instruction != null && !instruction.isEmpty()) {
            this.customInstructions.add(instruction);
        }
        return this;
    }

    public SystemPromptBuilder verbose(boolean verbose) {
        this.verbose = verbose;
        return this;
    }

    /**
     * Build the complete system prompt.
     */
    public String build() {
        StringBuilder sb = new StringBuilder();

        // Section 1: Base identity
        sb.append(buildBaseSection());

        // Section 2: Environment context
        sb.append("\n\n").append(buildEnvironmentSection());

        // Section 3: Tool descriptions
        if (tools != null && !tools.isEmpty()) {
            sb.append("\n\n").append(buildToolsSection());
        }

        // Section 4: Permission mode instructions
        if (permissionMode != null) {
            sb.append("\n\n").append(buildPermissionSection());
        }

        // Section 5: Custom instructions
        if (!customInstructions.isEmpty()) {
            sb.append("\n\n").append(buildCustomSection());
        }

        return sb.toString();
    }

    /**
     * Build base identity section.
     */
    private String buildBaseSection() {
        return """
            You are Claude Code, Anthropic's official CLI for Claude.
            You are an interactive agent that helps users with software engineering tasks.
            Use the available tools to accomplish the user's goals.
            Always be helpful, direct, and thorough.

            IMPORTANT: Assist with authorized security testing, defensive security, CTF challenges, and educational contexts.
            Refuse requests for destructive techniques, DoS attacks, mass targeting, supply chain compromise, or detection evasion for malicious purposes.

            IMPORTANT: You must NEVER generate or guess URLs for the user unless you are confident that the URLs are for helping the user with programming.
            """;
    }

    /**
     * Build environment context section.
     */
    private String buildEnvironmentSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Environment\n\n");

        if (cwd != null) {
            sb.append("- Working directory: ").append(cwd).append("\n");
        }

        sb.append("- Platform: ").append(System.getProperty("os.name")).append("\n");
        sb.append("- Shell: ").append(System.getenv("SHELL") != null ? System.getenv("SHELL") : "unknown").append("\n");
        sb.append("- Java version: ").append(System.getProperty("java.version")).append("\n");

        // Git status if available
        String gitBranch = getGitBranch();
        if (gitBranch != null) {
            sb.append("- Git branch: ").append(gitBranch).append("\n");
        }

        return sb.toString();
    }

    /**
     * Build tools section.
     */
    private String buildToolsSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Available Tools\n\n");
        sb.append("You have access to the following tools:\n\n");

        for (Tool<?, ?, ?> tool : tools) {
            sb.append("- **").append(tool.name()).append("**");
            if (tool.description() != null) {
                String desc = tool.description();
                if (desc.length() > 100) {
                    desc = desc.substring(0, 100) + "...";
                }
                sb.append(": ").append(desc.replace("\n", " "));
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Build permission mode section.
     */
    private String buildPermissionSection() {
        return switch (permissionMode != null ? permissionMode : PermissionMode.DEFAULT) {
            case DEFAULT -> """
                # Permissions
                You are in default permission mode. Ask for confirmation before:
                - Running potentially destructive commands
                - Modifying files outside the current directory
                - Making network requests
                """;

            case ACCEPT_EDITS -> """
                # Permissions
                You are in accept-edits mode. File modifications are automatically approved.
                Still ask for confirmation for:
                - Shell commands
                - Network requests
                """;

            case BYPASS_PERMISSIONS -> """
                # Permissions
                You are in bypass mode. All operations are automatically approved.
                Be careful with destructive operations.
                """;

            case PLAN -> """
                # Permissions
                You are in plan mode. Create a detailed plan before making changes.
                Do not execute any tools that modify files.
                """;

            case AUTO -> """
                # Permissions
                You are in auto mode. Make reasonable decisions autonomously.
                """;

            default -> "";
        };
    }

    /**
     * Build custom instructions section.
     */
    private String buildCustomSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Custom Instructions\n\n");

        for (String instruction : customInstructions) {
            sb.append(instruction).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * Get current git branch.
     */
    private String getGitBranch() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "branch", "--show-current");
            pb.directory(new java.io.File(cwd != null ? cwd : "."));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (process.exitValue() == 0) {
                String branch = new String(process.getInputStream().readAllBytes()).trim();
                return branch.isEmpty() ? null : branch;
            }
        } catch (Exception e) {
            // Ignore git errors
        }
        return null;
    }

    /**
     * Build a minimal system prompt for simple cases.
     */
    public static String buildMinimal() {
        return """
            You are Claude Code, Anthropic's official CLI for Claude.
            You are an interactive agent that helps users with software engineering tasks.
            Use the available tools to accomplish the user's goals.
            Always be helpful, direct, and thorough.
            """;
    }
}