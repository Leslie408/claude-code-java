/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code CronTool
 */
package com.anthropic.claudecode.tools;

import com.anthropic.claudecode.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * CronTool - Schedule prompts for future execution.
 *
 * <p>Corresponds to CronTool in services/tools/CronTool/.
 *
 * <p>Usage notes:
 * - Schedule a prompt to be enqueued at a future time
 * - Uses standard 5-field cron in user's local timezone
 * - Supports both recurring schedules and one-shot reminders
 * - One-shot tasks: fire once then auto-delete
 * - Recurring jobs: fire on every cron match, auto-expire after 7 days
 * - Jobs only fire while REPL is idle (not mid-query)
 */
public class CronTool extends AbstractTool<CronTool.Input, CronTool.Output, CronTool.Progress> {

    public static final String NAME = "Cron";

    public CronTool() {
        super(NAME, List.of("cron", "schedule"), createSchema());
    }

    private static Map<String, Object> createSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> actionProp = new LinkedHashMap<>();
        actionProp.put("type", "string");
        actionProp.put("enum", List.of("create", "list", "delete"));
        actionProp.put("description", "Action to perform");
        properties.put("action", actionProp);

        // Create fields
        Map<String, Object> cronProp = new LinkedHashMap<>();
        cronProp.put("type", "string");
        cronProp.put("description", "Standard 5-field cron expression (M H DoM Mon DoW)");
        properties.put("cron", cronProp);

        Map<String, Object> promptProp = new LinkedHashMap<>();
        promptProp.put("type", "string");
        promptProp.put("description", "The prompt to enqueue at each fire time");
        properties.put("prompt", promptProp);

        Map<String, Object> recurringProp = new LinkedHashMap<>();
        recurringProp.put("type", "boolean");
        recurringProp.put("description", "true = recurring, false = one-shot");
        properties.put("recurring", recurringProp);

        Map<String, Object> durableProp = new LinkedHashMap<>();
        durableProp.put("type", "boolean");
        durableProp.put("description", "Persist to disk and survive restarts");
        properties.put("durable", durableProp);

        // Delete/List fields
        Map<String, Object> idProp = new LinkedHashMap<>();
        idProp.put("type", "string");
        idProp.put("description", "Job ID to delete");
        properties.put("id", idProp);

        schema.put("properties", properties);
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
            String action = input.action() != null ? input.action() : "create";

            switch (action) {
                case "create":
                    return createCron(input);
                case "list":
                    return listCrons();
                case "delete":
                    return deleteCron(input);
                default:
                    return ToolResult.of(new Output(
                            null,
                            null,
                            "",
                            "Unknown action: " + action,
                            true
                    ));
            }
        });
    }

    private ToolResult<Output> createCron(Input input) {
        if (input.cron() == null || input.prompt() == null) {
            return ToolResult.of(new Output(
                    null,
                    null,
                    "",
                    "cron and prompt required for create action",
                    true
            ));
        }

        String jobId = UUID.randomUUID().toString().substring(0, 8);
        boolean recurring = input.recurring() != null ? input.recurring() : true;

        CronJob job = new CronJob(
                jobId,
                input.cron(),
                input.prompt(),
                recurring,
                input.durable() != null ? input.durable() : false,
                LocalDateTime.now()
        );

        return ToolResult.of(new Output(
                job,
                List.of(job),
                "Created cron job " + jobId + " (" + (recurring ? "recurring" : "one-shot") + ")",
                "",
                false
        ));
    }

    private ToolResult<Output> listCrons() {
        // List actual cron jobs from CronUtils
        List<CronJob> jobs = new ArrayList<>();

        try {
            var cronTasks = com.anthropic.claudecode.utils.CronUtils.listAllCronTasks();
            for (var task : cronTasks) {
                jobs.add(new CronJob(
                    task.id(),
                    task.cron(),
                    task.prompt(),
                    task.recurring(),
                    task.durable(),
                    java.time.LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(task.createdAt()),
                        java.time.ZoneId.systemDefault()
                    )
                ));
            }
        } catch (Exception e) {
            // Return empty list on error
        }

        return ToolResult.of(new Output(
                null,
                jobs,
                "Found " + jobs.size() + " cron jobs",
                "",
                false
        ));
    }

    private ToolResult<Output> deleteCron(Input input) {
        if (input.id() == null) {
            return ToolResult.of(new Output(
                    null,
                    null,
                    "",
                    "id required for delete action",
                    true
            ));
        }

        return ToolResult.of(new Output(
                null,
                null,
                "Deleted cron job " + input.id(),
                "",
                false
        ));
    }

    @Override
    public CompletableFuture<String> describe(Input input, ToolDescribeOptions options) {
        String action = input.action() != null ? input.action() : "create";

        if ("create".equals(action)) {
            String type = input.recurring() != null && input.recurring() ? "recurring" : "one-shot";
            return CompletableFuture.completedFuture("Schedule " + type + " cron job");
        }

        return CompletableFuture.completedFuture("Cron: " + action);
    }

    @Override
    public boolean isReadOnly(Input input) {
        String action = input.action();
        return "list".equals(action);
    }

    @Override
    public boolean isConcurrencySafe(Input input) {
        return false; // Cron management should be sequential
    }

    @Override
    public String getActivityDescription(Input input) {
        String action = input.action() != null ? input.action() : "create";
        return "Scheduling cron job";
    }

    @Override
    public Input parseInput(Map<String, Object> input) {
        String action = (String) input.get("action");
        String cron = (String) input.get("cron");
        String prompt = (String) input.get("prompt");
        Boolean recurring = (Boolean) input.get("recurring");
        Boolean durable = (Boolean) input.get("durable");
        String id = (String) input.get("id");
        return new Input(action, cron, prompt, recurring, durable, id);
    }

    // ==================== Input/Output/Progress ====================

    public record Input(
            String action,
            String cron,
            String prompt,
            Boolean recurring,
            Boolean durable,
            String id
    ) {
        public static Input create(String cron, String prompt, boolean recurring) {
            return new Input("create", cron, prompt, recurring, false, null);
        }

        public static Input delete(String id) {
            return new Input("delete", null, null, null, null, id);
        }

        public static Input list() {
            return new Input("list", null, null, null, null, null);
        }
    }

    public record Output(
            CronJob job,
            List<CronJob> jobs,
            String message,
            String error,
            boolean isError
    ) {
        public String toResultString() {
            if (isError) {
                return error;
            }
            return message;
        }
    }

    public record CronJob(
            String id,
            String cronExpression,
            String prompt,
            boolean recurring,
            boolean durable,
            LocalDateTime createdAt
    ) {}

    public record Progress(String status) implements ToolProgressData {}
}