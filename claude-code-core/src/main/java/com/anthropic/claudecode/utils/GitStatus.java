/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 * Java port of Claude Code context.ts - Git Status utilities
 */
package com.anthropic.claudecode.utils;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * GitStatus - Utilities for getting git repository status.
 *
 * <p>Corresponds to TypeScript's getGitStatus() in context.ts.
 */
public final class GitStatus {
    private GitStatus() {}

    private static final int MAX_STATUS_LENGTH = 5000;
    private static final int MAX_COMMITS_LENGTH = 5000;
    private static final int TIMEOUT_SECONDS = 5;

    /**
     * Git status result.
     */
    public record GitStatusResult(
        boolean isGitRepo,
        String currentBranch,
        String mainBranch,
        String userName,
        String statusText,
        String recentCommits,
        boolean hasUncommittedChanges
    ) {
        public static GitStatusResult empty() {
            return new GitStatusResult(false, null, null, null, null, null, false);
        }

        public static GitStatusResult notGit() {
            return new GitStatusResult(false, null, null, null, "(clean)", null, false);
        }

        /**
         * Format for system prompt.
         */
        public String toSystemPromptFormat() {
            if (!isGitRepo) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("gitStatus: This is the git status at the start of the conversation.\n");
            sb.append("Note that this status is a snapshot in time, and will not update during the conversation.\n\n");

            sb.append("Current branch: ").append(currentBranch != null ? currentBranch : "unknown").append("\n");

            if (mainBranch != null) {
                sb.append("\nMain branch (you will usually use this for PRs): ").append(mainBranch).append("\n");
            }

            if (userName != null) {
                sb.append("\nGit user: ").append(userName).append("\n");
            }

            sb.append("\nStatus:\n");
            if (statusText != null && !statusText.isEmpty()) {
                sb.append(statusText);
            } else {
                sb.append("(clean)");
            }
            sb.append("\n");

            if (recentCommits != null && !recentCommits.isEmpty()) {
                sb.append("\nRecent commits:\n").append(recentCommits).append("\n");
            }

            return sb.toString();
        }
    }

    /**
     * Get git status for a directory.
     */
    public static GitStatusResult getGitStatus(String cwd) {
        if (cwd == null) {
            cwd = System.getProperty("user.dir");
        }

        Path gitDir = Paths.get(cwd, ".git");
        if (!Files.exists(gitDir)) {
            // Check if it's a git repo by running git rev-parse
            if (!isGitRepo(cwd)) {
                return GitStatusResult.notGit();
            }
        }

        String currentBranch = getCurrentBranch(cwd);
        String mainBranch = getMainBranch(cwd);
        String userName = getGitUserName(cwd);
        String statusText = getStatusText(cwd);
        String recentCommits = getRecentCommits(cwd);
        boolean hasUncommittedChanges = statusText != null && !statusText.isEmpty() && !statusText.contains("nothing to commit");

        return new GitStatusResult(
            true,
            currentBranch,
            mainBranch,
            userName,
            truncate(statusText, MAX_STATUS_LENGTH),
            truncate(recentCommits, MAX_COMMITS_LENGTH),
            hasUncommittedChanges
        );
    }

    /**
     * Check if directory is a git repository.
     */
    public static boolean isGitRepo(String cwd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--is-inside-work-tree");
            pb.directory(new File(cwd));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (process.exitValue() == 0) {
                String output = new String(process.getInputStream().readAllBytes()).trim();
                return "true".equals(output);
            }
        } catch (Exception e) {
            // Not a git repo or git not available
        }
        return false;
    }

    /**
     * Get current branch name.
     */
    public static String getCurrentBranch(String cwd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "branch", "--show-current");
            pb.directory(new File(cwd));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (process.exitValue() == 0) {
                String branch = new String(process.getInputStream().readAllBytes()).trim();
                return branch.isEmpty() ? null : branch;
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    /**
     * Get main branch (origin/main or main or master).
     */
    public static String getMainBranch(String cwd) {
        // Try to get from origin remote first
        String originMain = getSymbolicRef(cwd, "refs/remotes/origin/HEAD");
        if (originMain != null && originMain.contains("/")) {
            return originMain.substring(originMain.lastIndexOf("/") + 1);
        }

        // Check for main branch
        if (branchExists(cwd, "main")) {
            return "main";
        }

        // Check for master branch
        if (branchExists(cwd, "master")) {
            return "master";
        }

        // Default to main
        return "main";
    }

    /**
     * Get symbolic ref.
     */
    private static String getSymbolicRef(String cwd, String ref) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "symbolic-ref", ref);
            pb.directory(new File(cwd));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (process.exitValue() == 0) {
                return new String(process.getInputStream().readAllBytes()).trim();
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    /**
     * Check if branch exists.
     */
    private static boolean branchExists(String cwd, String branch) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "show-ref", "--verify", "--quiet", "refs/heads/" + branch);
            pb.directory(new File(cwd));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get git user name.
     */
    public static String getGitUserName(String cwd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "config", "user.name");
            pb.directory(new File(cwd));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (process.exitValue() == 0) {
                String name = new String(process.getInputStream().readAllBytes()).trim();
                return name.isEmpty() ? null : name;
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    /**
     * Get git status text.
     */
    public static String getStatusText(String cwd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "status", "--short");
            pb.directory(new File(cwd));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (process.exitValue() == 0) {
                String status = new String(process.getInputStream().readAllBytes()).trim();
                if (status.isEmpty()) {
                    return null;
                }
                return status;
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    /**
     * Get recent commits.
     */
    public static String getRecentCommits(String cwd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "git", "log", "--oneline", "-n", "5"
            );
            pb.directory(new File(cwd));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (process.exitValue() == 0) {
                String commits = new String(process.getInputStream().readAllBytes()).trim();
                return commits.isEmpty() ? null : commits;
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    /**
     * Truncate text to max length.
     */
    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "... (truncated)";
    }
}