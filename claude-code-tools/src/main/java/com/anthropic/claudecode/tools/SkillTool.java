/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code SkillTool
 */
package com.anthropic.claudecode.tools;

import com.anthropic.claudecode.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * SkillTool - Execute skills (slash commands) within conversations.
 *
 * <p>Corresponds to SkillTool in services/tools/SkillTool/.
 *
 * <p>Usage notes:
 * - Execute a skill within the main conversation
 * - Skills provide specialized capabilities and domain knowledge
 * - When users reference "/<something>" (e.g. "/commit", "/review-pr")
 *   they are referring to a skill - invoke it using this tool
 * - Available skills are listed in system-reminder messages
 * - This is a BLOCKING REQUIREMENT: invoke the relevant skill tool
 *   BEFORE generating any other response about the task
 * - NEVER mention a skill without actually calling this tool
 */
public class SkillTool extends AbstractTool<SkillTool.Input, SkillTool.Output, SkillTool.Progress> {

    public static final String NAME = "Skill";

    // Built-in skills
    private static final Set<String> BUILTIN_SKILLS = Set.of(
            "commit",
            "review-pr",
            "pdf",
            "help",
            "fast"
    );

    public SkillTool() {
        super(NAME, List.of("skill", "slash"), createSchema());
    }

    private static Map<String, Object> createSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> skillProp = new LinkedHashMap<>();
        skillProp.put("type", "string");
        skillProp.put("description", "The skill name to invoke (e.g. 'commit', 'review-pr')");
        properties.put("skill", skillProp);

        Map<String, Object> argsProp = new LinkedHashMap<>();
        argsProp.put("type", "string");
        argsProp.put("description", "Optional arguments for the skill");
        properties.put("args", argsProp);

        schema.put("properties", properties);
        schema.put("required", List.of("skill"));
        return schema;
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input input,
            ToolUseContext context,
            CanUseToolFn canUseTool,
            AssistantMessage parentMessage,
            Consumer<ToolProgress<Progress>> onProgress) {

        return CompletableFuture.supplyAsync(() -> {
            String skillName = input.skill();

            // Check if skill exists
            if (!BUILTIN_SKILLS.contains(skillName) && !skillName.contains(":")) {
                return ToolResult.of(new Output(
                        "",
                        "Unknown skill: " + skillName,
                        skillName,
                        true
                ));
            }

            // In real implementation, would load and execute the skill
            String result = "Skill [" + skillName + "] executed with args: " +
                           (input.args() != null ? input.args() : "none");

            return ToolResult.of(new Output(
                    result,
                    "",
                    skillName,
                    false
            ));
        });
    }

    @Override
    public CompletableFuture<String> describe(Input input, ToolDescribeOptions options) {
        return CompletableFuture.completedFuture("Execute skill: " + input.skill());
    }

    @Override
    public boolean isReadOnly(Input input) {
        // Some skills modify things (commit), some don't (help)
        String skill = input.skill();
        return "help".equals(skill) || "fast".equals(skill);
    }

    @Override
    public boolean isDestructive(Input input) {
        // commit skill creates commits, review-pr reads PRs
        return "commit".equals(input.skill());
    }

    @Override
    public boolean isConcurrencySafe(Input input) {
        return false; // Skills should execute sequentially
    }

    @Override
    public String getActivityDescription(Input input) {
        return "Running " + input.skill() + " skill";
    }

    @Override
    public String getToolUseSummary(Input input) {
        return "/" + input.skill() + (input.args() != null ? " " + input.args() : "");
    }

    @Override
    public Input parseInput(Map<String, Object> input) {
        String skill = (String) input.get("skill");
        String args = (String) input.get("args");
        return new Input(skill, args);
    }

    // ==================== Input/Output/Progress ====================

    public record Input(
            String skill,
            String args
    ) {
        public Input(String skill) {
            this(skill, null);
        }
    }

    public record Output(
            String result,
            String error,
            String skillName,
            boolean isError
    ) {
        public String toResultString() {
            if (isError) {
                return error;
            }
            return result;
        }
    }

    public record Progress(String status) implements ToolProgressData {}
}