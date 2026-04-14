/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 * Test for SystemPromptService
 */
package com.anthropic.claudecode.prompt;

import com.anthropic.claudecode.Tool;
import com.anthropic.claudecode.utils.GitStatus;
import com.anthropic.claudecode.utils.GitStatus.GitStatusResult;
import com.anthropic.claudecode.services.mcp.McpTypes.ConnectedMCPServer;

import org.junit.jupiter.api.*;
import java.util.*;

/**
 * Test SystemPromptService functionality.
 */
public class SystemPromptServiceTest {

    @BeforeEach
    void clearCache() {
        // Clear section cache before each test to avoid cross-test contamination
        SystemPromptService.clearSectionCache();
    }

    @Test
    @DisplayName("Test system prompt generation - basic")
    void testBasicSystemPromptGeneration() {
        SystemPromptService.SystemPromptConfig config = SystemPromptService.SystemPromptConfig.builder()
            .cwd("/tmp/test-project")
            .model("claude-sonnet-4-6")
            .build();

        List<String> sections = SystemPromptService.getSystemPrompt(config);

        Assertions.assertFalse(sections.isEmpty(), "Sections should not be empty");

        // Verify key sections exist
        boolean hasIntro = sections.stream().anyMatch(s -> s.contains("You are an interactive agent"));
        boolean hasSystem = sections.stream().anyMatch(s -> s.contains("# System"));
        boolean hasDoingTasks = sections.stream().anyMatch(s -> s.contains("# Doing tasks"));
        boolean hasActions = sections.stream().anyMatch(s -> s.contains("# Executing actions with care"));
        boolean hasUsingTools = sections.stream().anyMatch(s -> s.contains("# Using your tools"));
        boolean hasTone = sections.stream().anyMatch(s -> s.contains("# Tone and style"));
        boolean hasEfficiency = sections.stream().anyMatch(s -> s.contains("# Output efficiency"));
        boolean hasEnvInfo = sections.stream().anyMatch(s -> s.contains("Working directory"));

        Assertions.assertTrue(hasIntro, "Should have intro section");
        Assertions.assertTrue(hasSystem, "Should have system section");
        Assertions.assertTrue(hasDoingTasks, "Should have doing tasks section");
        Assertions.assertTrue(hasActions, "Should have actions section");
        Assertions.assertTrue(hasUsingTools, "Should have using tools section");
        Assertions.assertTrue(hasTone, "Should have tone section");
        Assertions.assertTrue(hasEfficiency, "Should have output efficiency section");
        Assertions.assertTrue(hasEnvInfo, "Should have environment info section");
    }

    @Test
    @DisplayName("Test system prompt with git status")
    void testSystemPromptWithGitStatus() {
        // Get real git status
        String cwd = System.getProperty("user.dir");
        GitStatusResult gitStatus = GitStatus.getGitStatus(cwd);

        SystemPromptService.SystemPromptConfig config = SystemPromptService.SystemPromptConfig.builder()
            .cwd(cwd)
            .model("claude-sonnet-4-6")
            .isGit(gitStatus.isGitRepo())
            .gitBranch(gitStatus.currentBranch())
            .gitMainBranch(gitStatus.mainBranch())
            .gitUser(gitStatus.userName())
            .gitStatus(gitStatus.statusText())
            .gitRecentCommits(gitStatus.recentCommits())
            .build();

        List<String> sections = SystemPromptService.getSystemPrompt(config);

        if (gitStatus.isGitRepo()) {
            boolean hasGitStatus = sections.stream().anyMatch(s -> s.contains("gitStatus"));
            Assertions.assertTrue(hasGitStatus, "Should have git status section when in git repo");
        }
    }

    @Test
    @DisplayName("Test system prompt with custom append prompt")
    void testSystemPromptWithAppendPrompt() {
        String customAppend = "Always write unit tests for new code.";

        SystemPromptService.SystemPromptConfig config = SystemPromptService.SystemPromptConfig.builder()
            .cwd("/tmp/test-project")
            .model("claude-sonnet-4-6")
            .appendSystemPrompt(customAppend)
            .build();

        List<String> sections = SystemPromptService.getSystemPrompt(config);

        boolean hasCustom = sections.stream().anyMatch(s -> s.contains(customAppend));
        Assertions.assertTrue(hasCustom, "Should have appended custom prompt");
    }

    @Test
    @DisplayName("Test system prompt with language preference")
    void testSystemPromptWithLanguagePreference() {
        SystemPromptService.SystemPromptConfig config = SystemPromptService.SystemPromptConfig.builder()
            .cwd("/tmp/test-project")
            .model("claude-sonnet-4-6")
            .language("Chinese")
            .build();

        List<String> sections = SystemPromptService.getSystemPrompt(config);

        boolean hasLanguage = sections.stream().anyMatch(s -> s.contains("# Language preference") && s.contains("Chinese"));
        Assertions.assertTrue(hasLanguage, "Should have language preference section");
    }

    @Test
    @DisplayName("Test system prompt blocks for API")
    void testSystemPromptBlocksForApi() {
        SystemPromptService.SystemPromptConfig config = SystemPromptService.SystemPromptConfig.builder()
            .cwd("/tmp/test-project")
            .model("claude-sonnet-4-6")
            .build();

        List<SystemPromptService.SystemPromptBlock> blocks = SystemPromptService.buildSystemPromptBlocks(config);

        Assertions.assertFalse(blocks.isEmpty(), "Blocks should not be empty");

        // First block should be cached (global scope)
        SystemPromptService.SystemPromptBlock firstBlock = blocks.get(0);
        Assertions.assertNotNull(firstBlock.text(), "Block text should not be null");
        Assertions.assertTrue(firstBlock.text().length() > 100, "First block should be substantial");

        // Check that we have at least one cached block and one dynamic block
        boolean hasCachedBlock = blocks.stream().anyMatch(b -> b.cacheScope() != null);
        boolean hasDynamicBlock = blocks.stream().anyMatch(b -> b.cacheScope() == null);

        Assertions.assertTrue(hasCachedBlock, "Should have cached blocks");
        Assertions.assertTrue(hasDynamicBlock, "Should have dynamic blocks");
    }

    @Test
    @DisplayName("Test dynamic boundary marker")
    void testDynamicBoundaryMarker() {
        SystemPromptService.SystemPromptConfig config = SystemPromptService.SystemPromptConfig.builder()
            .cwd("/tmp/test-project")
            .model("claude-sonnet-4-6")
            .build();

        List<String> sections = SystemPromptService.getSystemPrompt(config);

        // Should have dynamic boundary marker
        boolean hasBoundary = sections.contains("__SYSTEM_PROMPT_DYNAMIC_BOUNDARY__");
        Assertions.assertTrue(hasBoundary, "Should have dynamic boundary marker");
    }

    @Test
    @DisplayName("Test section cache clearing")
    void testSectionCacheClearing() {
        // Generate prompt once
        SystemPromptService.SystemPromptConfig config = SystemPromptService.SystemPromptConfig.builder()
            .cwd("/tmp/test-project")
            .model("claude-sonnet-4-6")
            .build();

        List<String> sections1 = SystemPromptService.getSystemPrompt(config);

        // Clear cache
        SystemPromptService.clearSectionCache();

        // Generate again - should re-compute
        List<String> sections2 = SystemPromptService.getSystemPrompt(config);

        // Both should have same content (sections are the same)
        Assertions.assertEquals(sections1.size(), sections2.size(), "Section count should be same after clear");
    }

    @Test
    @DisplayName("Test output style section")
    void testOutputStyleSection() {
        // With output style
        SystemPromptService.SystemPromptConfig configWithStyle = SystemPromptService.SystemPromptConfig.builder()
            .cwd("/tmp/test-project")
            .model("claude-sonnet-4-6")
            .hasOutputStyle(true)
            .build();

        List<String> sectionsWithStyle = SystemPromptService.getSystemPrompt(configWithStyle);

        // Intro should mention "Output Style" - exact match as shown in debug output
        boolean hasOutputStyle = sectionsWithStyle.stream()
            .anyMatch(s -> s.contains("Output Style"));

        Assertions.assertTrue(hasOutputStyle, "Should mention Output Style when hasOutputStyle=true");
    }

    @Test
    @DisplayName("Test full prompt join")
    void testFullPromptJoin() {
        SystemPromptService.SystemPromptConfig config = SystemPromptService.SystemPromptConfig.builder()
            .cwd("/tmp/test-project")
            .model("claude-sonnet-4-6")
            .language("Chinese")
            .build();

        List<String> sections = SystemPromptService.getSystemPrompt(config);
        String fullPrompt = String.join("\n\n", sections);

        // Verify full prompt is substantial
        Assertions.assertTrue(fullPrompt.length() > 5000, "Full prompt should be substantial ( > 5000 chars)");

        // Verify key elements - check for "authorized security" instead of "cyber"
        Assertions.assertTrue(fullPrompt.contains("authorized security"), "Should contain security instruction");
        Assertions.assertTrue(fullPrompt.toLowerCase().contains("hook"), "Should mention hooks");
        // Note: gitStatus format only appears when isGit=true
    }

    @Test
    @DisplayName("Test MCP instructions injection")
    void testMcpInstructionsInjection() {
        // Create mock MCP client with instructions
        ConnectedMCPServer mockClient = new ConnectedMCPServer(
            "test-server",
            null,  // config
            null,  // capabilities
            null,  // serverInfo
            "This is a test MCP server. Use tools carefully.",
            () -> {}  // cleanup
        );

        SystemPromptService.SystemPromptConfig config = SystemPromptService.SystemPromptConfig.builder()
            .cwd("/tmp/test-project")
            .model("claude-sonnet-4-6")
            .mcpClients(List.of(mockClient))
            .build();

        List<String> sections = SystemPromptService.getSystemPrompt(config);

        boolean hasMcpInstructions = sections.stream()
            .anyMatch(s -> s.contains("# MCP Server Instructions") && s.contains("test-server"));

        Assertions.assertTrue(hasMcpInstructions, "Should have MCP instructions section");
    }

    @Test
    @DisplayName("Test knowledge cutoff in env info")
    void testKnowledgeCutoffInEnvInfo() {
        SystemPromptService.SystemPromptConfig config = SystemPromptService.SystemPromptConfig.builder()
            .cwd("/tmp/test-project")
            .model("claude-sonnet-4-6")
            .build();

        List<String> sections = SystemPromptService.getSystemPrompt(config);

        boolean hasKnowledgeCutoff = sections.stream()
            .anyMatch(s -> s.contains("knowledge cutoff") || s.contains("August 2025"));

        Assertions.assertTrue(hasKnowledgeCutoff, "Should mention knowledge cutoff date");
    }

    @Test
    @DisplayName("Test skill commands in session guidance")
    void testSkillCommandsInSessionGuidance() {
        SystemPromptService.SystemPromptConfig config = SystemPromptService.SystemPromptConfig.builder()
            .cwd("/tmp/test-project")
            .model("claude-sonnet-4-6")
            .build();

        List<String> sections = SystemPromptService.getSystemPrompt(config);

        boolean hasSkillGuidance = sections.stream()
            .anyMatch(s -> s.contains("/<skill-name>") || s.contains("slash command"));

        Assertions.assertTrue(hasSkillGuidance, "Should have skill command guidance");
    }
}