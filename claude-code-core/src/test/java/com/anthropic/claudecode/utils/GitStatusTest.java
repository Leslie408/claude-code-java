/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 * Test for GitStatus
 */
package com.anthropic.claudecode.utils;

import org.junit.jupiter.api.*;

/**
 * Test GitStatus functionality.
 */
public class GitStatusTest {

    @Test
    @DisplayName("Test git status in current directory")
    void testGitStatusCurrentDirectory() {
        String cwd = System.getProperty("user.dir");
        GitStatus.GitStatusResult result = GitStatus.getGitStatus(cwd);

        // This test runs in a git repo, so it should detect that
        Assertions.assertNotNull(result, "Result should not be null");

        if (result.isGitRepo()) {
            Assertions.assertNotNull(result.currentBranch(), "Branch should not be null in git repo");
            Assertions.assertNotNull(result.mainBranch(), "Main branch should not be null");

            System.out.println("Git info detected:");
            System.out.println("  Branch: " + result.currentBranch());
            System.out.println("  Main: " + result.mainBranch());
            System.out.println("  User: " + result.userName());
        }
    }

    @Test
    @DisplayName("Test git status in non-git directory")
    void testGitStatusNonGitDirectory() {
        String cwd = "/tmp/non-git-test-" + System.currentTimeMillis();

        GitStatus.GitStatusResult result = GitStatus.getGitStatus(cwd);

        Assertions.assertNotNull(result, "Result should not be null");
        Assertions.assertFalse(result.isGitRepo(), "Should not be a git repo");
    }

    @Test
    @DisplayName("Test git status format")
    void testGitStatusFormat() {
        GitStatus.GitStatusResult result = new GitStatus.GitStatusResult(
            true,
            "feature-branch",
            "main",
            "TestUser",
            "M file.txt\nA new.txt",
            "abc123 commit 1\ndef456 commit 2",
            true
        );

        String formatted = result.toSystemPromptFormat();

        Assertions.assertTrue(formatted.contains("Current branch: feature-branch"), "Should contain branch");
        Assertions.assertTrue(formatted.contains("Main branch"), "Should contain main branch reference");
        Assertions.assertTrue(formatted.contains("TestUser"), "Should contain user name");
        Assertions.assertTrue(formatted.contains("M file.txt"), "Should contain status");
        Assertions.assertTrue(formatted.contains("abc123"), "Should contain commits");
    }

    @Test
    @DisplayName("Test git status empty format")
    void testGitStatusEmptyFormat() {
        GitStatus.GitStatusResult result = GitStatus.GitStatusResult.notGit();

        String formatted = result.toSystemPromptFormat();

        Assertions.assertEquals("", formatted, "Non-git should return empty string");
    }

    @Test
    @DisplayName("Test isGitRepo function")
    void testIsGitRepoFunction() {
        // Test on current directory (should be git repo)
        String cwd = System.getProperty("user.dir");
        boolean isGit = GitStatus.isGitRepo(cwd);
        Assertions.assertTrue(isGit, "Current directory should be a git repo");

        // Test on /tmp (should not be git repo)
        boolean isTmpGit = GitStatus.isGitRepo("/tmp");
        Assertions.assertFalse(isTmpGit, "/tmp should not be a git repo");
    }
}