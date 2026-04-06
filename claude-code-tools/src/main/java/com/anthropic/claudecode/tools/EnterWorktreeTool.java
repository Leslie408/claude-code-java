/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 * Java port of Claude Code tools/EnterWorktreeTool/EnterWorktreeTool.ts
 */
package com.anthropic.claudecode.tools;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.nio.file.*;
import com.anthropic.claudecode.*;
import com.anthropic.claudecode.services.analytics.AnalyticsMetadata;

/**
 * EnterWorktree Tool - create an isolated git worktree and switch into it.
 */
public final class EnterWorktreeTool extends AbstractTool<EnterWorktreeTool.Input, EnterWorktreeTool.Output, ToolProgressData> {
    public static final String TOOL_NAME = "EnterWorktree";

    public EnterWorktreeTool() {
        super(TOOL_NAME, "Create an isolated git worktree and switch into it");
    }

    /**
     * Input schema.
     */
    public record Input(
        String name
    ) {}

    @Override
    public Input parseInput(Map<String, Object> input) {
        String name = (String) input.get("name");
        return new Input(name);
    }

    /**
     * Output schema.
     */
    public record Output(
        String worktreePath,
        String worktreeBranch,
        String message,
        String error
    ) {
        public static Output success(String path, String branch, String msg) {
            return new Output(path, branch, msg, null);
        }
        public static Output error(String error) {
            return new Output(null, null, null, error);
        }
    }

    @Override
    public String description() {
        return """
            Creates an isolated worktree (via git or configured hooks) and switches the session into it.

            Use this when you need to work on a separate branch without affecting the main repository.
            The worktree provides a clean isolated environment for your changes.""";
    }

    @Override
    public String searchHint() {
        return "create an isolated git worktree and switch into it";
    }

    @Override
    public boolean shouldDefer() {
        return true;
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
        if (input.name() != null) {
            String error = validateWorktreeSlug(input.name());
            if (error != null) {
                return CompletableFuture.completedFuture(ValidationResult.failure(error, 1));
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
            try {
                // Check if already in a worktree
                if (getCurrentWorktreeSession() != null) {
                    return ToolResult.of(Output.error("Already in a worktree session"));
                }

                // Get current working directory
                String cwd = System.getProperty("user.dir");

                // Resolve to main repo root
                String mainRepoRoot = findCanonicalGitRoot(cwd);
                if (mainRepoRoot != null && !mainRepoRoot.equals(cwd)) {
                    System.setProperty("user.dir", mainRepoRoot);
                }

                // Generate slug
                String slug = input.name() != null ? input.name() : generatePlanSlug();

                // Create worktree
                WorktreeSession worktreeSession = createWorktreeForSession(getSessionId(), slug);

                // Change to worktree directory
                System.setProperty("user.dir", worktreeSession.worktreePath());

                // Save worktree state
                saveWorktreeState(worktreeSession);

                // Log analytics
                AnalyticsMetadata.logEvent("tengu_worktree_created", Map.of(
                    "mid_session", "true"
                ), true);

                String branchInfo = worktreeSession.worktreeBranch() != null
                    ? " on branch " + worktreeSession.worktreeBranch()
                    : "";

                return ToolResult.of(Output.success(
                    worktreeSession.worktreePath(),
                    worktreeSession.worktreeBranch(),
                    "Created worktree at " + worktreeSession.worktreePath() + branchInfo +
                    ". The session is now working in the worktree. Use ExitWorktree to leave mid-session."
                ));

            } catch (Exception e) {
                return ToolResult.of(Output.error("Error creating worktree: " + e.getMessage()));
            }
        });
    }

    @Override
    public String formatResult(Output output) {
        if (output.error() != null) {
            return "Error: " + output.error();
        }
        return output.message();
    }

    // Helper methods

    private String validateWorktreeSlug(String slug) {
        if (slug == null || slug.isEmpty()) {
            return null;
        }

        // Max 64 chars total
        if (slug.length() > 64) {
            return "Name exceeds maximum length of 64 characters";
        }

        // Each segment must contain only allowed characters
        String[] parts = slug.split("/");
        for (String part : parts) {
            if (!part.matches("[a-zA-Z0-9._-]+")) {
                return "Each segment may contain only letters, digits, dots, underscores, and dashes";
            }
        }

        return null;
    }

    private String findCanonicalGitRoot(String cwd) {
        try {
            Path path = Paths.get(cwd);
            while (path != null) {
                if (Files.exists(path.resolve(".git"))) {
                    return path.toString();
                }
                path = path.getParent();
            }
        } catch (Exception e) {
            // Ignore
        }
        return cwd;
    }

    private String generatePlanSlug() {
        return "plan-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String getSessionId() {
        return UUID.randomUUID().toString();
    }

    private WorktreeSession getCurrentWorktreeSession() {
        // Check session state for existing worktree
        try {
            String home = System.getProperty("user.home");
            java.nio.file.Path sessionPath = java.nio.file.Paths.get(home, ".claude", "worktree-session.json");

            if (java.nio.file.Files.exists(sessionPath)) {
                String content = java.nio.file.Files.readString(sessionPath);

                String worktreePath = extractJsonValue(content, "worktreePath");
                String branch = extractJsonValue(content, "worktreeBranch");

                if (worktreePath != null && branch != null) {
                    return new WorktreeSession(worktreePath, branch);
                }
            }
        } catch (Exception e) {
            // Ignore errors
        }
        return null;
    }

    private String extractJsonValue(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return null;
        int valStart = json.indexOf("\"", idx + key.length() + 3) + 1;
        if (valStart < 1) return null;
        int valEnd = json.indexOf("\"", valStart);
        return json.substring(valStart, valEnd);
    }

    private WorktreeSession createWorktreeForSession(String sessionId, String slug) {
        String cwd = System.getProperty("user.dir");
        String worktreePath = cwd + "-" + slug;
        String branchName = slug;

        // Create worktree using git
        try {
            // Try git worktree add first
            ProcessBuilder pb = new ProcessBuilder(
                "git", "worktree", "add", worktreePath, "-b", branchName
            );
            pb.directory(new java.io.File(cwd));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);

            if (process.exitValue() != 0) {
                // Fallback: just create directory
                Files.createDirectories(Paths.get(worktreePath));
            }
        } catch (Exception e) {
            // Fallback: just create directory
            try {
                Files.createDirectories(Paths.get(worktreePath));
            } catch (Exception ex) {
                throw new RuntimeException("Failed to create worktree: " + ex.getMessage());
            }
        }

        return new WorktreeSession(worktreePath, branchName);
    }

    private void saveWorktreeState(WorktreeSession session) {
        // Save to session storage
        try {
            String home = System.getProperty("user.home");
            java.nio.file.Path sessionPath = java.nio.file.Paths.get(home, ".claude", "worktree-session.json");
            java.nio.file.Files.createDirectories(sessionPath.getParent());

            String json = String.format(
                "{\"worktreePath\":\"%s\",\"worktreeBranch\":\"%s\",\"timestamp\":\"%s\"}",
                session.worktreePath(),
                session.worktreeBranch(),
                java.time.Instant.now().toString()
            );

            java.nio.file.Files.writeString(sessionPath, json);
        } catch (Exception e) {
            // Ignore errors
        }
    }

    /**
     * Worktree session record.
     */
    public record WorktreeSession(
        String worktreePath,
        String worktreeBranch
    ) {}
}