/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 * Java port of Claude Code tools/ExitWorktreeTool/ExitWorktreeTool.ts
 */
package com.anthropic.claudecode.tools;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.nio.file.*;
import com.anthropic.claudecode.*;
import com.anthropic.claudecode.services.analytics.AnalyticsMetadata;

/**
 * ExitWorktree Tool - exit a worktree session and return to the original directory.
 */
public final class ExitWorktreeTool extends AbstractTool<ExitWorktreeTool.Input, ExitWorktreeTool.Output, ToolProgressData> {
    public static final String TOOL_NAME = "ExitWorktree";

    public ExitWorktreeTool() {
        super(TOOL_NAME, "Exit a worktree session and return to the original directory");
    }

    /**
     * Input schema.
     */
    public record Input(
        String action,
        Boolean discard_changes
    ) {}

    @Override
    public Input parseInput(Map<String, Object> input) {
        String action = (String) input.get("action");
        Boolean discardChanges = (Boolean) input.get("discard_changes");
        return new Input(action, discardChanges);
    }

    /**
     * Output schema.
     */
    public record Output(
        String action,
        String originalCwd,
        String worktreePath,
        String worktreeBranch,
        String tmuxSessionName,
        Integer discardedFiles,
        Integer discardedCommits,
        String message
    ) {}

    @Override
    public String description() {
        return "Exits a worktree session created by EnterWorktree and restores the original working directory";
    }

    @Override
    public String searchHint() {
        return "exit a worktree session and return to the original directory";
    }

    @Override
    public boolean shouldDefer() {
        return true;
    }

    @Override
    public boolean isDestructive(Input input) {
        return "remove".equals(input.action());
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public Class<Output> outputType() {
        return Output.class;
    }

    @Override
    public CompletableFuture<ValidationResult> validateInput(Input input, ToolUseContext context) {
        WorktreeSession session = getCurrentWorktreeSession();
        if (session == null) {
            return CompletableFuture.completedFuture(ValidationResult.failure(
                "No-op: there is no active EnterWorktree session to exit. " +
                "This tool only operates on worktrees created by EnterWorktree in the current session.",
                1
            ));
        }

        if ("remove".equals(input.action()) && !Boolean.TRUE.equals(input.discard_changes())) {
            ChangeSummary summary = countWorktreeChanges(
                session.worktreePath(),
                session.originalHeadCommit()
            );

            if (summary == null) {
                return CompletableFuture.completedFuture(ValidationResult.failure(
                    "Could not verify worktree state at " + session.worktreePath() + ". " +
                    "Refusing to remove without explicit confirmation. Re-invoke with discard_changes: true.",
                    3
                ));
            }

            if (summary.changedFiles > 0 || summary.commits > 0) {
                List<String> parts = new ArrayList<>();
                if (summary.changedFiles > 0) {
                    parts.add(summary.changedFiles + " uncommitted " +
                        (summary.changedFiles == 1 ? "file" : "files"));
                }
                if (summary.commits > 0) {
                    parts.add(summary.commits + " " +
                        (summary.commits == 1 ? "commit" : "commits") +
                        " on " + (session.worktreeBranch() != null ? session.worktreeBranch() : "the worktree branch"));
                }
                return CompletableFuture.completedFuture(ValidationResult.failure(
                    "Worktree has " + String.join(" and ", parts) + ". " +
                    "Removing will discard this work permanently. Confirm with the user, " +
                    "then re-invoke with discard_changes: true.",
                    2
                ));
            }
        }

        return CompletableFuture.completedFuture(ValidationResult.success());
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
        Input input,
        ToolUseContext context,
        CanUseToolFn canUseTool,
        AssistantMessage parent,
        Consumer<ToolProgress<ToolProgressData>> onProgress
    ) {
        return CompletableFuture.supplyAsync(() -> {
            WorktreeSession session = getCurrentWorktreeSession();
            if (session == null) {
                throw new RuntimeException("Not in a worktree session");
            }

            String originalCwd = session.originalCwd();
            String worktreePath = session.worktreePath();
            String worktreeBranch = session.worktreeBranch();
            String tmuxSessionName = session.tmuxSessionName();

            // Count changes for output
            ChangeSummary summary = countWorktreeChanges(
                worktreePath,
                session.originalHeadCommit()
            );
            int changedFiles = summary != null ? summary.changedFiles : 0;
            int commits = summary != null ? summary.commits : 0;

            if ("keep".equals(input.action())) {
                // Keep worktree - just restore session state
                restoreWorktreeSession(null);
                System.setProperty("user.dir", originalCwd);

                AnalyticsMetadata.logEvent("tengu_worktree_kept", Map.of(
                    "mid_session", "true",
                    "commits", String.valueOf(commits),
                    "changed_files", String.valueOf(changedFiles)
                ), true);

                String tmuxNote = tmuxSessionName != null
                    ? " Tmux session " + tmuxSessionName + " is still running; reattach with: tmux attach -t " + tmuxSessionName
                    : "";

                return ToolResult.of(new Output(
                    "keep",
                    originalCwd,
                    worktreePath,
                    worktreeBranch,
                    tmuxSessionName,
                    null,
                    null,
                    "Exited worktree. Your work is preserved at " + worktreePath +
                    (worktreeBranch != null ? " on branch " + worktreeBranch : "") +
                    ". Session is now back in " + originalCwd + "." + tmuxNote
                ));
            }

            // action === 'remove'
            if (tmuxSessionName != null) {
                killTmuxSession(tmuxSessionName);
            }

            // Remove worktree
            removeWorktree(worktreePath);
            restoreWorktreeSession(null);
            System.setProperty("user.dir", originalCwd);

            AnalyticsMetadata.logEvent("tengu_worktree_removed", Map.of(
                "mid_session", "true",
                "commits", String.valueOf(commits),
                "changed_files", String.valueOf(changedFiles)
            ), true);

            List<String> discardParts = new ArrayList<>();
            if (commits > 0) {
                discardParts.add(commits + " " + (commits == 1 ? "commit" : "commits"));
            }
            if (changedFiles > 0) {
                discardParts.add(changedFiles + " uncommitted " + (changedFiles == 1 ? "file" : "files"));
            }
            String discardNote = discardParts.isEmpty() ? ""
                : " Discarded " + String.join(" and ", discardParts) + ".";

            return ToolResult.of(new Output(
                "remove",
                originalCwd,
                worktreePath,
                worktreeBranch,
                tmuxSessionName,
                changedFiles,
                commits,
                "Exited and removed worktree at " + worktreePath + "." + discardNote +
                " Session is now back in " + originalCwd + "."
            ));
        });
    }

    @Override
    public String formatResult(Output output) {
        return output.message();
    }

    // Helper methods

    /**
     * Worktree session record.
     */
    public record WorktreeSession(
        String originalCwd,
        String worktreePath,
        String worktreeBranch,
        String tmuxSessionName,
        String originalHeadCommit
    ) {}

    private static WorktreeSession currentSession = null;

    private WorktreeSession getCurrentWorktreeSession() {
        return currentSession;
    }

    private void restoreWorktreeSession(WorktreeSession session) {
        currentSession = session;
    }

    private void removeWorktree(String worktreePath) {
        try {
            Files.deleteIfExists(Paths.get(worktreePath));
        } catch (Exception e) {
            // Ignore
        }
    }

    private static class ChangeSummary {
        final int changedFiles;
        final int commits;

        ChangeSummary(int changedFiles, int commits) {
            this.changedFiles = changedFiles;
            this.commits = commits;
        }
    }

    private ChangeSummary countWorktreeChanges(String worktreePath, String originalHeadCommit) {
        try {
            // Run git status
            ProcessBuilder statusPb = new ProcessBuilder("git", "-C", worktreePath, "status", "--porcelain");
            Process statusProcess = statusPb.start();
            String stdout = new String(statusProcess.getInputStream().readAllBytes());
            int statusExit = statusProcess.waitFor();

            if (statusExit != 0) {
                return null;
            }

            int changedFiles = 0;
            for (String line : stdout.split("\n")) {
                if (!line.trim().isEmpty()) {
                    changedFiles++;
                }
            }

            if (originalHeadCommit == null) {
                return null;
            }

            // Run git rev-list
            ProcessBuilder revPb = new ProcessBuilder("git", "-C", worktreePath,
                "rev-list", "--count", originalHeadCommit + "..HEAD");
            Process revProcess = revPb.start();
            String revStdout = new String(revProcess.getInputStream().readAllBytes());
            int revExit = revProcess.waitFor();

            if (revExit != 0) {
                return null;
            }

            int commits = Integer.parseInt(revStdout.trim(), 10);

            return new ChangeSummary(changedFiles, commits);
        } catch (Exception e) {
            return null;
        }
    }

    private void killTmuxSession(String sessionName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("tmux", "kill-session", "-t", sessionName);
            pb.start().waitFor();
        } catch (Exception e) {
            // Ignore
        }
    }
}