/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 * Java port of Claude Code tools/ScheduleCronTool/CronCreateTool.ts
 */
package com.anthropic.claudecode.tools;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.nio.file.*;
import com.anthropic.claudecode.*;
import com.anthropic.claudecode.utils.CronUtils;

/**
 * CronCreate Tool - Schedule recurring or one-shot prompts.
 */
public final class CronCreateTool extends AbstractTool<CronCreateTool.Input, CronCreateTool.Output, ToolProgressData> {
    private static final int MAX_JOBS = 50;
    private static final int DEFAULT_MAX_AGE_DAYS = 7;

    public CronCreateTool() {
        super("CronCreate", "Schedule a recurring or one-shot prompt");
    }

    @Override
    public Input parseInput(Map<String, Object> input) {
        String cron = (String) input.get("cron");
        String prompt = (String) input.get("prompt");
        Boolean recurring = (Boolean) input.get("recurring");
        Boolean durable = (Boolean) input.get("durable");
        return new Input(cron, prompt, recurring, durable);
    }

    /**
     * Input schema.
     */
    public record Input(
        String cron,
        String prompt,
        Boolean recurring,
        Boolean durable
    ) {}

    /**
     * Output schema.
     */
    public record Output(
        String id,
        String humanSchedule,
        boolean recurring,
        boolean durable
    ) {}

    @Override
    public String description() {
        return """
            Schedule a prompt to be enqueued at a future time.

            Uses standard 5-field cron in local timezone:
            - minute hour day-of-month month day-of-week
            - "*/5 * * * *" = every 5 minutes
            - "0 9 * * *" = 9am local every day
            - "30 14 28 2 *" = Feb 28 at 2:30pm local once

            Recurring jobs auto-expire after 7 days.

            Use durable: true to persist across sessions (writes to .claude/scheduled_tasks.json).""";
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
        // Validate cron expression
        if (!CronUtils.isValidCron(input.cron())) {
            return CompletableFuture.completedFuture(ValidationResult.failure(
                "Invalid cron expression '" + input.cron() + "'. Expected 5 fields: M H DoM Mon DoW.",
                1
            ));
        }

        // Check if cron matches any date
        if (CronUtils.nextRun(input.cron(), System.currentTimeMillis()) == null) {
            return CompletableFuture.completedFuture(ValidationResult.failure(
                "Cron expression '" + input.cron() + "' does not match any calendar date in the next year.",
                2
            ));
        }

        // Check job limit
        if (CronUtils.getJobCount() >= MAX_JOBS) {
            return CompletableFuture.completedFuture(ValidationResult.failure(
                "Too many scheduled jobs (max " + MAX_JOBS + "). Cancel one first.",
                3
            ));
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
            boolean recurring = input.recurring() != null ? input.recurring() : true;
            boolean durable = input.durable() != null ? input.durable() : false;

            // Check if durable is enabled
            boolean effectiveDurable = durable && isDurableCronEnabled();

            // Add cron task
            String id = CronUtils.addCronTask(
                input.cron(),
                input.prompt(),
                recurring,
                effectiveDurable
            );

            // Enable scheduler
            CronUtils.setScheduledTasksEnabled(true);

            String humanSchedule = CronUtils.cronToHuman(input.cron());

            return ToolResult.of(new Output(
                id,
                humanSchedule,
                recurring,
                effectiveDurable
            ));
        });
    }

    @Override
    public String formatResult(Output output) {
        String where = output.durable()
            ? "Persisted to .claude/scheduled_tasks.json"
            : "Session-only (not written to disk, dies when Claude exits)";

        if (output.recurring()) {
            return String.format(
                "Scheduled recurring job %s (%s). %s. Auto-expires after %d days. Use CronDelete to cancel sooner.",
                output.id(),
                output.humanSchedule(),
                where,
                DEFAULT_MAX_AGE_DAYS
            );
        } else {
            return String.format(
                "Scheduled one-shot task %s (%s). %s. It will fire once then auto-delete.",
                output.id(),
                output.humanSchedule(),
                where
            );
        }
    }

    private boolean isDurableCronEnabled() {
        String env = System.getenv("CLAUDE_CODE_DURABLE_CRON");
        return "true".equalsIgnoreCase(env) || "1".equals(env);
    }
}