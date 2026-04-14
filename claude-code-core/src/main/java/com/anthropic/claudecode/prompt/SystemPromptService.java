/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 * Java port of Claude Code constants/prompts.ts - System Prompt Service
 */
package com.anthropic.claudecode.prompt;

import com.anthropic.claudecode.Tool;
import com.anthropic.claudecode.constants.*;
import com.anthropic.claudecode.utils.*;
import com.anthropic.claudecode.services.mcp.McpTypes.*;

import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;

/**
 * SystemPromptService - Core service for building the complete system prompt.
 *
 * <p>This is the main entry point for system prompt generation, corresponding to
 * TypeScript's getSystemPrompt() in constants/prompts.ts.
 *
 * <p>The system prompt is constructed in layers:
 * <ol>
 *   <li>Static core sections (identity, rules, actions, tools, tone)</li>
 *   <li>Dynamic boundary marker (for prompt caching)</li>
 *   <li>Dynamic sections (memory, env info, language, MCP instructions)</li>
 * </ol>
 */
public final class SystemPromptService {

    private SystemPromptService() {}

    // Section cache for memoized sections
    private static final Map<String, String> sectionCache = new ConcurrentHashMap<>();

    /**
     * Configuration for system prompt generation.
     */
    public record SystemPromptConfig(
        String cwd,
        String model,
        List<Tool<?, ?, ?>> tools,
        String customSystemPrompt,
        String appendSystemPrompt,
        boolean hasOutputStyle,
        String language,
        Set<String> enabledTools,
        boolean verbose,
        String additionalWorkingDirectories,
        boolean isGit,
        String gitBranch,
        String gitMainBranch,
        String gitUser,
        String gitStatus,
        String gitRecentCommits,
        // MCP clients with instructions
        List<ConnectedMCPServer> mcpClients,
        // Skill tool commands
        List<String> skillCommands
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String cwd = System.getProperty("user.dir");
            private String model = "claude-sonnet-4-6";
            private List<Tool<?, ?, ?>> tools = new ArrayList<>();
            private String customSystemPrompt = null;
            private String appendSystemPrompt = null;
            private boolean hasOutputStyle = false;
            private String language = null;
            private Set<String> enabledTools = new HashSet<>();
            private boolean verbose = false;
            private String additionalWorkingDirectories = null;
            private boolean isGit = false;
            private String gitBranch = null;
            private String gitMainBranch = null;
            private String gitUser = null;
            private String gitStatus = null;
            private String gitRecentCommits = null;
            private List<ConnectedMCPServer> mcpClients = new ArrayList<>();
            private List<String> skillCommands = new ArrayList<>();

            public Builder cwd(String cwd) { this.cwd = cwd; return this; }
            public Builder model(String model) { this.model = model; return this; }
            public Builder tools(List<Tool<?, ?, ?>> tools) { this.tools = tools; return this; }
            public Builder customSystemPrompt(String prompt) { this.customSystemPrompt = prompt; return this; }
            public Builder appendSystemPrompt(String prompt) { this.appendSystemPrompt = prompt; return this; }
            public Builder hasOutputStyle(boolean has) { this.hasOutputStyle = has; return this; }
            public Builder language(String lang) { this.language = lang; return this; }
            public Builder enabledTools(Set<String> tools) { this.enabledTools = tools; return this; }
            public Builder verbose(boolean v) { this.verbose = v; return this; }
            public Builder additionalWorkingDirectories(String dirs) { this.additionalWorkingDirectories = dirs; return this; }
            public Builder isGit(boolean is) { this.isGit = is; return this; }
            public Builder gitBranch(String branch) { this.gitBranch = branch; return this; }
            public Builder gitMainBranch(String main) { this.gitMainBranch = main; return this; }
            public Builder gitUser(String user) { this.gitUser = user; return this; }
            public Builder gitStatus(String status) { this.gitStatus = status; return this; }
            public Builder gitRecentCommits(String commits) { this.gitRecentCommits = commits; return this; }
            public Builder mcpClients(List<ConnectedMCPServer> clients) { this.mcpClients = clients; return this; }
            public Builder skillCommands(List<String> commands) { this.skillCommands = commands; return this; }

            public SystemPromptConfig build() {
                return new SystemPromptConfig(
                    cwd, model, tools, customSystemPrompt, appendSystemPrompt,
                    hasOutputStyle, language, enabledTools, verbose,
                    additionalWorkingDirectories, isGit, gitBranch, gitMainBranch,
                    gitUser, gitStatus, gitRecentCommits, mcpClients, skillCommands
                );
            }
        }
    }

    /**
     * Get the complete system prompt.
     * This is the main entry point, corresponding to TypeScript's getSystemPrompt().
     */
    public static List<String> getSystemPrompt(SystemPromptConfig config) {
        List<String> sections = new ArrayList<>();

        // === STATIC SECTIONS (cached) ===

        // 1. Intro section (identity + cyber risk)
        sections.add(getCachedSection("intro", () ->
            Prompts.getSimpleIntroSection(config.hasOutputStyle())));

        // 2. System section (hooks, reminders, context)
        sections.add(getCachedSection("system", () ->
            Prompts.getSimpleSystemSection()));

        // 3. Doing tasks section
        sections.add(getCachedSection("doing_tasks", () ->
            Prompts.getSimpleDoingTasksSection()));

        // 4. Actions section (reversibility, blast radius)
        sections.add(getCachedSection("actions", () ->
            Prompts.getActionsSection()));

        // 5. Using your tools section
        sections.add(getCachedSection("using_tools", () ->
            Prompts.getUsingYourToolsSection(config.enabledTools())));

        // 6. Tone and style section
        sections.add(getCachedSection("tone_style", () ->
            Prompts.getSimpleToneAndStyleSection()));

        // 7. Output efficiency section
        sections.add(getCachedSection("output_efficiency", () ->
            Prompts.getOutputEfficiencySection()));

        // === DYNAMIC BOUNDARY ===
        // This marker is used for prompt caching - static sections above are cached,
        // dynamic sections below are re-computed each turn
        sections.add(Prompts.SYSTEM_PROMPT_DYNAMIC_BOUNDARY);

        // === DYNAMIC SECTIONS (re-computed each turn) ===

        // 8. Session guidance (current date)
        String currentDate = java.time.LocalDate.now().toString();
        sections.add(buildSessionGuidanceSection(currentDate));

        // 9. Memory prompt (CLAUDE.md files + auto memory)
        String memoryPrompt = buildMemoryPrompt(config.cwd());
        if (memoryPrompt != null && !memoryPrompt.isEmpty()) {
            sections.add(memoryPrompt);
        }

        // 10. Environment info (cwd, platform, model, git)
        sections.add(buildEnvInfoSection(config));

        // 11. Git status (if in git repo)
        if (config.isGit()) {
            String gitStatusSection = buildGitStatusSection(config);
            if (gitStatusSection != null && !gitStatusSection.isEmpty()) {
                sections.add(gitStatusSection);
            }
        }

        // 12. MCP server instructions (if any MCP clients with instructions)
        String mcpInstructions = buildMcpInstructionsSection(config.mcpClients());
        if (mcpInstructions != null && !mcpInstructions.isEmpty()) {
            sections.add(mcpInstructions);
        }

        // 13. Language preference
        if (config.language() != null && !config.language().isEmpty()) {
            sections.add(buildLanguageSection(config.language()));
        }

        // === CUSTOM/APPEND PROMPTS ===

        // 14. Custom system prompt (replaces default if provided)
        if (config.customSystemPrompt() != null && !config.customSystemPrompt().isEmpty()) {
            // In TypeScript, custom prompt can replace or append
            // Here we append it
            sections.add(config.customSystemPrompt());
        }

        // 15. Append system prompt (always appended at end)
        if (config.appendSystemPrompt() != null && !config.appendSystemPrompt().isEmpty()) {
            sections.add(config.appendSystemPrompt());
        }

        return sections;
    }

    /**
     * Build session guidance section with current date.
     */
    private static String buildSessionGuidanceSection(String currentDate) {
        StringBuilder sb = new StringBuilder();

        sb.append("# Session-specific guidance\n\n");
        sb.append(" - The date has changed. Today's date is now ").append(currentDate).append(". ");
        sb.append("DO NOT mention this to the user explicitly because they are already aware.\n");
        sb.append(" - If you do not understand why the user has denied a tool call, use the AskUserQuestion to ask them.\n");
        sb.append(" - If you need the user to run a shell command themselves (e.g., an interactive login like `gcloud auth login`), ");
        sb.append("suggest they type `! <command>` in the prompt — the `!` prefix runs the command in this session so its output lands directly in the conversation.\n");
        sb.append(" - Use the Agent tool with specialized agents when the task at hand matches the agent's description. ");
        sb.append("Subagents are valuable for parallelizing independent queries or for protecting the main context window from excessive results, ");
        sb.append("but they should not be used excessively when not needed.\n");
        sb.append(" - For simple, directed codebase searches (e.g., for a specific file/class/function) use the Glob or Grep directly.\n");
        sb.append(" - For broader codebase exploration and deep research, use the Agent tool with subagent_type=Explore.");

        // Add skill commands guidance if available
        sb.append("\n\n# Companion\n\n");
        sb.append("/<skill-name> (e.g., /commit, /review-pr) is shorthand for users to invoke a user-invocable skill. ");
        sb.append("When users reference a \"slash command\" they are referring to a skill. Use this tool with the skill name and optional arguments.\n");
        sb.append("IMPORTANT: Only use Skill for skills listed in its user-invocable skills section - do not guess or use for built-in CLI commands.");

        return sb.toString();
    }

    /**
     * Build MCP instructions section.
     */
    private static String buildMcpInstructionsSection(List<ConnectedMCPServer> mcpClients) {
        if (mcpClients == null || mcpClients.isEmpty()) {
            return null;
        }

        // Filter clients that have instructions
        List<ConnectedMCPServer> clientsWithInstructions = mcpClients.stream()
            .filter(client -> client.instructions() != null && !client.instructions().isEmpty())
            .toList();

        if (clientsWithInstructions.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# MCP Server Instructions\n\n");
        sb.append("The following MCP servers have provided instructions for how to use their tools and resources:\n\n");

        for (ConnectedMCPServer client : clientsWithInstructions) {
            sb.append("## ").append(client.name()).append("\n\n");
            sb.append(client.instructions()).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * Build memory prompt from CLAUDE.md files.
     */
    private static String buildMemoryPrompt(String cwd) {
        StringBuilder sb = new StringBuilder();

        // Load CLAUDE.md files
        List<ClaudeMd.MemoryFileInfo> memoryFiles = ClaudeMd.getMemoryFiles(false);

        if (!memoryFiles.isEmpty()) {
            sb.append("# Memory\n\n");
            sb.append("The user has provided the following memory files for context:\n\n");

            for (ClaudeMd.MemoryFileInfo file : memoryFiles) {
                String typeLabel = switch (file.type()) {
                    case USER -> "User memory (~/.claude/CLAUDE.md)";
                    case PROJECT -> "Project memory (CLAUDE.md)";
                    case LOCAL -> "Local memory (CLAUDE.local.md)";
                    case MANAGED -> "Managed memory";
                    default -> "Memory";
                };

                sb.append("## ").append(typeLabel).append("\n\n");
                sb.append("Path: ").append(file.path()).append("\n\n");
                sb.append(file.content()).append("\n\n");
            }
        }

        // Load auto memory system
        String autoMemoryPrompt = buildAutoMemoryPrompt(cwd);
        if (autoMemoryPrompt != null && !autoMemoryPrompt.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(autoMemoryPrompt);
        }

        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * Build auto memory prompt.
     */
    private static String buildAutoMemoryPrompt(String cwd) {
        String configDir = EnvUtils.getClaudeConfigHomeDir();
        Path projectMemoryDir = Paths.get(configDir, "projects", sanitizeProjectPath(cwd), "memory");
        Path memoryFile = projectMemoryDir.resolve("MEMORY.md");

        StringBuilder sb = new StringBuilder();

        sb.append("# auto memory\n\n");
        sb.append("You have a persistent, file-based memory system at `").append(projectMemoryDir).append("/`.\n");
        sb.append("This directory already exists — write to it directly with the Write tool.\n\n");

        sb.append("You should build up this memory system over time so that future conversations can have a complete picture of who the user is, ");
        sb.append("how they'd like to collaborate with you, what behaviors to avoid or repeat, and the context behind the work the user gives you.\n\n");

        sb.append("## Types of memory\n\n");
        sb.append("There are several discrete types of memory that you can store:\n\n");

        // user type
        sb.append("**user**: Contains information about the user's role, goals, responsibilities, and knowledge. ");
        sb.append("Great user memories help you tailor your future behavior to the user's preferences and perspective.\n\n");

        // feedback type
        sb.append("**feedback**: Guidance the user has given you about how to approach work — both what to avoid and what to keep doing. ");
        sb.append("Record from failure AND success: if you only save corrections, you will avoid past mistakes but drift away from approaches the user has already validated.\n\n");

        // project type
        sb.append("**project**: Information that you learn about ongoing work, goals, initiatives, bugs, or incidents within the project.\n\n");

        // reference type
        sb.append("**reference**: Stores pointers to where information can be found in external systems.\n\n");

        sb.append("## What NOT to save in memory\n\n");
        sb.append("- Code patterns, conventions, architecture, file paths, or project structure — these can be derived by reading the current project state.\n");
        sb.append("- Git history, recent changes, or who-changed-what — `git log` / `git blame` are authoritative.\n");
        sb.append("- Debugging solutions or fix recipes — the fix is in the code; the commit message has the context.\n");
        sb.append("- Anything already documented in CLAUDE.md files.\n");
        sb.append("- Ephemeral task details: in-progress work, temporary state, current conversation context.\n\n");

        sb.append("## How to save memories\n\n");
        sb.append("Saving a memory is a two-step process:\n\n");
        sb.append("**Step 1** — write the memory to its own file using this frontmatter format:\n\n");
        sb.append("```markdown\n");
        sb.append("---\n");
        sb.append("name: {{memory name}}\n");
        sb.append("description: {{one-line description}}\n");
        sb.append("type: {{user, feedback, project, reference}}\n");
        sb.append("---\n\n");
        sb.append("{{memory content — for feedback/project types, structure as: rule/fact, then **Why:** and **How to apply:** lines}}\n");
        sb.append("```\n\n");

        sb.append("**Step 2** — add a pointer to that file in `MEMORY.md`. `MEMORY.md` is an index, not a memory.\n\n");

        sb.append("## When to access memories\n\n");
        sb.append("- When memories seem relevant, or the user references prior-conversation work.\n");
        sb.append("- You MUST access memory when the user explicitly asks you to check, recall, or remember.\n\n");

        sb.append("## MEMORY.md\n\n");
        if (java.nio.file.Files.exists(memoryFile)) {
            try {
                String content = java.nio.file.Files.readString(memoryFile);
                sb.append(content);
            } catch (Exception e) {
                sb.append("File exists but could not be read.");
            }
        } else {
            sb.append("MEMORY.md does not yet exist — create it when you have memories to index.\n");
        }

        return sb.toString();
    }

    /**
     * Build environment info section.
     */
    private static String buildEnvInfoSection(SystemPromptConfig config) {
        String platform = System.getProperty("os.name");
        String shell = System.getenv("SHELL");
        if (shell == null) {
            shell = System.getenv("COMSPEC");
        }
        if (shell == null) {
            shell = "unknown";
        }

        String osVersion = getOsVersion();

        return Prompts.computeEnvInfo(
            config.cwd(),
            config.isGit(),
            platform,
            shell,
            osVersion,
            config.model(),
            config.additionalWorkingDirectories()
        );
    }

    /**
     * Build git status section.
     */
    private static String buildGitStatusSection(SystemPromptConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("gitStatus: This is the git status at the start of the conversation. ");
        sb.append("Note that this status is a snapshot in time, and will not update during the conversation.\n\n");

        sb.append("Current branch: ").append(config.gitBranch() != null ? config.gitBranch() : "unknown").append("\n\n");

        if (config.gitMainBranch() != null) {
            sb.append("Main branch (you will usually use this for PRs): ").append(config.gitMainBranch()).append("\n\n");
        }

        if (config.gitUser() != null) {
            sb.append("Git user: ").append(config.gitUser()).append("\n\n");
        }

        if (config.gitStatus() != null && !config.gitStatus().isEmpty()) {
            sb.append("Status:\n").append(config.gitStatus()).append("\n\n");
        } else {
            sb.append("Status: (clean)\n\n");
        }

        if (config.gitRecentCommits() != null && !config.gitRecentCommits().isEmpty()) {
            sb.append("Recent commits:\n").append(config.gitRecentCommits()).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * Build language preference section.
     */
    private static String buildLanguageSection(String language) {
        return "# Language preference\n\n" +
               "IMPORTANT: You MUST follow this:\n" +
               "When generating code, default to using the language specified below, unless the user explicitly specifies otherwise.\n" +
               "Language: " + language;
    }

    /**
     * Get cached section, computing if not cached.
     */
    private static String getCachedSection(String name, java.util.function.Supplier<String> compute) {
        if (sectionCache.containsKey(name)) {
            return sectionCache.get(name);
        }
        String value = compute.get();
        sectionCache.put(name, value);
        return value;
    }

    /**
     * Clear all section caches.
     * Called on /clear and /compact commands.
     */
    public static void clearSectionCache() {
        sectionCache.clear();
    }

    /**
     * Get OS version.
     */
    private static String getOsVersion() {
        String osName = System.getProperty("os.name");
        String osVersion = System.getProperty("os.version");
        String osArch = System.getProperty("os.arch");

        if (osName.toLowerCase().contains("mac")) {
            // macOS - try to get more specific version
            try {
                Process process = Runtime.getRuntime().exec("sw_vers -productVersion");
                process.waitFor(5, TimeUnit.SECONDS);
                if (process.exitValue() == 0) {
                    osVersion = new String(process.getInputStream().readAllBytes()).trim();
                }
            } catch (Exception e) {
                // Use default
            }
        }

        return osName + " " + osVersion + " (" + osArch + ")";
    }

    /**
     * Sanitize project path for memory directory.
     */
    private static String sanitizeProjectPath(String path) {
        if (path == null) return "default";
        // Replace problematic characters
        return path.replace("/", "-")
                   .replace("\\", "-")
                   .replace(":", "-")
                   .replace(" ", "-");
    }

    /**
     * Build system prompt blocks for API.
     * Each block can have cache_control for prompt caching.
     */
    public static List<SystemPromptBlock> buildSystemPromptBlocks(SystemPromptConfig config) {
        List<String> sections = getSystemPrompt(config);
        List<SystemPromptBlock> blocks = new ArrayList<>();

        // Find the dynamic boundary index
        int boundaryIndex = -1;
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).equals(Prompts.SYSTEM_PROMPT_DYNAMIC_BOUNDARY)) {
                boundaryIndex = i;
                break;
            }
        }

        // Split into prefix (cached) and suffix (dynamic)
        List<String> prefixSections = boundaryIndex >= 0
            ? sections.subList(0, boundaryIndex)
            : sections;
        List<String> suffixSections = boundaryIndex >= 0
            ? sections.subList(boundaryIndex + 1, sections.size())
            : Collections.emptyList();

        // Prefix blocks (can be cached globally)
        if (!prefixSections.isEmpty()) {
            String prefixText = String.join("\n\n", prefixSections);
            blocks.add(new SystemPromptBlock(prefixText, CacheScope.GLOBAL));
        }

        // Suffix blocks (dynamic, not cached)
        for (String section : suffixSections) {
            if (section != null && !section.isEmpty()) {
                blocks.add(new SystemPromptBlock(section, null));
            }
        }

        return blocks;
    }

    /**
     * System prompt block with optional cache scope.
     */
    public record SystemPromptBlock(
        String text,
        CacheScope cacheScope
    ) {}

    /**
     * Cache scope enum.
     */
    public enum CacheScope {
        GLOBAL,
        ORG,
        USER
    }
}