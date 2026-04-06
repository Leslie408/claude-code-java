/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code TaskTool
 */
package com.anthropic.claudecode.tools;

import com.anthropic.claudecode.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * TaskTool - Create and manage structured task lists.
 *
 * <p>Corresponds to TaskTool in services/tools/TaskTool/.
 *
 * <p>Usage notes:
 * - Use this tool to create a structured task list for your current coding session
 * - Helps track progress and organize complex tasks
 * - Users can see progress and understand what you're doing
 * - Create tasks with clear, specific subjects in imperative form
 * - Use TaskUpdate to set up dependencies between tasks
 * - Mark each task as completed as soon as you finish it
 */
public class TaskTool extends AbstractTool<TaskTool.Input, TaskTool.Output, TaskTool.Progress> {

    public static final String NAME = "Task";

    // Task status values
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_DELETED = "deleted";

    public TaskTool() {
        super(NAME, List.of("task", "todo"), createSchema());
    }

    private static Map<String, Object> createSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        // Task action
        Map<String, Object> actionProp = new LinkedHashMap<>();
        actionProp.put("type", "string");
        actionProp.put("description", "The action to perform");
        properties.put("action", actionProp);

        // TaskCreate fields
        Map<String, Object> subjectProp = new LinkedHashMap<>();
        subjectProp.put("type", "string");
        subjectProp.put("description", "Brief title for the task (imperative form)");
        properties.put("subject", subjectProp);

        Map<String, Object> descriptionProp = new LinkedHashMap<>();
        descriptionProp.put("type", "string");
        descriptionProp.put("description", "Detailed description of what needs to be done");
        properties.put("description", descriptionProp);

        Map<String, Object> activeFormProp = new LinkedHashMap<>();
        activeFormProp.put("type", "string");
        activeFormProp.put("description", "Present continuous form for spinner (e.g. 'Running tests')");
        properties.put("activeForm", activeFormProp);

        // TaskUpdate fields
        Map<String, Object> taskIdProp = new LinkedHashMap<>();
        taskIdProp.put("type", "string");
        taskIdProp.put("description", "The ID of the task to update");
        properties.put("taskId", taskIdProp);

        Map<String, Object> statusProp = new LinkedHashMap<>();
        statusProp.put("type", "string");
        statusProp.put("enum", List.of(STATUS_PENDING, STATUS_IN_PROGRESS, STATUS_COMPLETED, STATUS_DELETED));
        statusProp.put("description", "New status for the task");
        properties.put("status", statusProp);

        Map<String, Object> ownerProp = new LinkedHashMap<>();
        ownerProp.put("type", "string");
        ownerProp.put("description", "Agent name claiming the task");
        properties.put("owner", ownerProp);

        // Dependencies
        Map<String, Object> addBlockedByProp = new LinkedHashMap<>();
        addBlockedByProp.put("type", "array");
        addBlockedByProp.put("items", Map.of("type", "string"));
        addBlockedByProp.put("description", "Task IDs that must complete before this task");
        properties.put("addBlockedBy", addBlockedByProp);

        Map<String, Object> addBlocksProp = new LinkedHashMap<>();
        addBlocksProp.put("type", "array");
        addBlocksProp.put("items", Map.of("type", "string"));
        addBlocksProp.put("description", "Task IDs that cannot start until this task completes");
        properties.put("addBlocks", addBlocksProp);

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
            String action = input.action();

            if ("create".equals(action)) {
                return createTask(input);
            } else if ("update".equals(action)) {
                return updateTask(input);
            } else if ("list".equals(action)) {
                return listTasks();
            } else if ("get".equals(action)) {
                return getTask(input);
            } else {
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

    private ToolResult<Output> createTask(Input input) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);

        return ToolResult.of(new Output(
                new TaskInfo(
                        taskId,
                        input.subject(),
                        input.description(),
                        STATUS_PENDING,
                        null,
                        List.of(),
                        List.of()
                ),
                null,
                "Created task " + taskId + ": " + input.subject(),
                "",
                false
        ));
    }

    private ToolResult<Output> updateTask(Input input) {
        if (input.taskId() == null) {
            return ToolResult.of(new Output(
                    null,
                    null,
                    "",
                    "taskId required for update action",
                    true
            ));
        }

        return ToolResult.of(new Output(
                new TaskInfo(
                        input.taskId(),
                        input.subject(),
                        input.description(),
                        input.status(),
                        input.owner(),
                        input.addBlockedBy() != null ? input.addBlockedBy() : List.of(),
                        input.addBlocks() != null ? input.addBlocks() : List.of()
                ),
                null,
                "Updated task " + input.taskId() + " to status: " + input.status(),
                "",
                false
        ));
    }

    private ToolResult<Output> listTasks() {
        // Query actual task store
        List<TaskInfo> tasks = new ArrayList<>();

        try {
            String home = System.getProperty("user.home");
            String cwd = System.getProperty("user.dir");
            String slug = cwd.replaceAll("[^a-zA-Z0-9]", "-");
            java.nio.file.Path tasksPath = java.nio.file.Paths.get(home, ".claude", "projects", slug, "tasks.json");

            if (java.nio.file.Files.exists(tasksPath)) {
                String content = java.nio.file.Files.readString(tasksPath);

                // Parse tasks JSON array
                int arrStart = content.indexOf("[");
                int arrEnd = content.lastIndexOf("]");
                if (arrStart >= 0 && arrEnd > arrStart) {
                    String arr = content.substring(arrStart, arrEnd + 1);

                    // Parse each task object
                    int i = 0;
                    while (i < arr.length()) {
                        int objStart = arr.indexOf("{", i);
                        if (objStart < 0) break;

                        int depth = 1;
                        int objEnd = objStart + 1;
                        while (objEnd < arr.length() && depth > 0) {
                            char c = arr.charAt(objEnd);
                            if (c == '{') depth++;
                            else if (c == '}') depth--;
                            objEnd++;
                        }

                        String obj = arr.substring(objStart, objEnd);
                        TaskInfo task = parseTaskObject(obj);
                        if (task != null) {
                            tasks.add(task);
                        }
                        i = objEnd;
                    }
                }
            }
        } catch (Exception e) {
            // Return empty list on error
        }

        return ToolResult.of(new Output(
                null,
                tasks,
                "Found " + tasks.size() + " tasks",
                "",
                false
        ));
    }

    private TaskInfo parseTaskObject(String json) {
        try {
            String id = extractJsonValue(json, "id");
            String subject = extractJsonValue(json, "subject");
            String description = extractJsonValue(json, "description");
            String status = extractJsonValue(json, "status");
            String owner = extractJsonValue(json, "owner");

            return new TaskInfo(
                id != null ? id : UUID.randomUUID().toString().substring(0, 8),
                subject != null ? subject : "Task",
                description != null ? description : "",
                status != null ? status : STATUS_PENDING,
                owner,
                List.of(),
                List.of()
            );
        } catch (Exception e) {
            return null;
        }
    }

    private String extractJsonValue(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return null;
        int valStart = json.indexOf("\"", idx + key.length() + 3) + 1;
        if (valStart < 1) return null;
        int valEnd = json.indexOf("\"", valStart);
        return json.substring(valStart, valEnd);
    }

    private ToolResult<Output> getTask(Input input) {
        if (input.taskId() == null) {
            return ToolResult.of(new Output(
                    null,
                    null,
                    "",
                    "taskId required for get action",
                    true
            ));
        }

        // Fetch actual task from store
        try {
            String home = System.getProperty("user.home");
            String cwd = System.getProperty("user.dir");
            String slug = cwd.replaceAll("[^a-zA-Z0-9]", "-");
            java.nio.file.Path tasksPath = java.nio.file.Paths.get(home, ".claude", "projects", slug, "tasks.json");

            if (java.nio.file.Files.exists(tasksPath)) {
                String content = java.nio.file.Files.readString(tasksPath);

                // Find task by ID
                int idIdx = content.indexOf("\"" + input.taskId() + "\"");
                if (idIdx >= 0) {
                    // Find the object containing this ID
                    int objStart = content.lastIndexOf("{", idIdx);
                    int objEnd = content.indexOf("}", idIdx) + 1;

                    if (objStart >= 0 && objEnd > objStart) {
                        String obj = content.substring(objStart, objEnd);
                        TaskInfo task = parseTaskObject(obj);
                        if (task != null) {
                            return ToolResult.of(new Output(
                                    task,
                                    null,
                                    "",
                                    "",
                                    false
                            ));
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Fall through to not found
        }

        return ToolResult.of(new Output(
                null,
                null,
                "",
                "Task not found: " + input.taskId(),
                true
        ));
    }

    @Override
    public CompletableFuture<String> describe(Input input, ToolDescribeOptions options) {
        String action = input.action();
        String subject = input.subject();

        if ("create".equals(action) && subject != null) {
            return CompletableFuture.completedFuture("Create task: " + subject);
        }
        if ("update".equals(action)) {
            return CompletableFuture.completedFuture("Update task " + input.taskId());
        }

        return CompletableFuture.completedFuture("Task: " + action);
    }

    @Override
    public boolean isReadOnly(Input input) {
        String action = input.action();
        return "list".equals(action) || "get".equals(action);
    }

    @Override
    public boolean isConcurrencySafe(Input input) {
        // Task management should be sequential
        return false;
    }

    @Override
    public String getActivityDescription(Input input) {
        String action = input.action();
        if ("create".equals(action)) {
            return "Creating task";
        }
        return "Managing tasks";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Input parseInput(Map<String, Object> input) {
        String action = (String) input.get("action");
        String taskId = (String) input.get("taskId");
        String subject = (String) input.get("subject");
        String description = (String) input.get("description");
        String activeForm = (String) input.get("activeForm");
        String status = (String) input.get("status");
        String owner = (String) input.get("owner");
        List<String> addBlockedBy = (List<String>) input.get("addBlockedBy");
        List<String> addBlocks = (List<String>) input.get("addBlocks");
        return new Input(action, taskId, subject, description, activeForm, status, owner, addBlockedBy, addBlocks);
    }

    // ==================== Input/Output/Progress ====================

    public record Input(
            String action,
            String taskId,
            String subject,
            String description,
            String activeForm,
            String status,
            String owner,
            List<String> addBlockedBy,
            List<String> addBlocks
    ) {
        // Create task input
        public static Input create(String subject, String description) {
            return new Input("create", null, subject, description, null, null, null, null, null);
        }

        // Update task status
        public static Input updateStatus(String taskId, String status) {
            return new Input("update", taskId, null, null, null, status, null, null, null);
        }
    }

    public record Output(
            TaskInfo task,
            List<TaskInfo> tasks,
            String message,
            String error,
            boolean isError
    ) {
        public String toResultString() {
            if (isError) {
                return error;
            }
            if (task != null) {
                return message + "\n" + task.toString();
            }
            return message;
        }
    }

    public record TaskInfo(
            String id,
            String subject,
            String description,
            String status,
            String owner,
            List<String> blockedBy,
            List<String> blocks
    ) {}

    public record Progress(String status) implements ToolProgressData {}
}