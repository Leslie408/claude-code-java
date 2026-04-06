/*
 * Copyright 2024-2026 Anthropic. All Rights Reserved.
 *
 * Java port of Claude Code AskUserQuestionTool
 */
package com.anthropic.claudecode.tools;

import com.anthropic.claudecode.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * AskUserQuestionTool - Ask the user clarifying questions during execution.
 *
 * <p>Corresponds to AskUserQuestionTool in tools/AskUserQuestionTool/.
 *
 * <p>Usage notes:
 * - Use when you need to ask the user questions during execution
 * - Gather user preferences or requirements
 * - Clarify ambiguous instructions
 * - Get decisions on implementation choices
 * - Offer choices about what direction to take
 * - Users can always select "Other" to provide custom text input
 * - Use multiSelect to allow multiple answers
 */
public class AskUserQuestionTool extends AbstractTool<AskUserQuestionTool.Input, AskUserQuestionTool.Output, AskUserQuestionTool.Progress> {

    public static final String NAME = "AskUserQuestion";

    // Global handler for user interactions
    private static volatile UserInteractionHandler globalHandler = null;

    // Pending questions awaiting responses
    private static final ConcurrentHashMap<String, CompletableFuture<Map<String, String>>> pendingQuestions = new ConcurrentHashMap<>();

    public AskUserQuestionTool() {
        super(NAME, List.of("ask", "question"), createSchema());
    }

    /**
     * Set the global user interaction handler.
     */
    public static void setGlobalHandler(UserInteractionHandler handler) {
        globalHandler = handler;
    }

    /**
     * Submit answers to pending questions.
     */
    public static void submitAnswers(String questionId, Map<String, String> answers) {
        CompletableFuture<Map<String, String>> future = pendingQuestions.remove(questionId);
        if (future != null) {
            future.complete(answers);
        }
    }

    private static Map<String, Object> createSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> questionsProp = new LinkedHashMap<>();
        questionsProp.put("type", "array");
        questionsProp.put("description", "Questions to ask the user (1-4 questions)");
        Map<String, Object> questionItem = new LinkedHashMap<>();
        questionItem.put("type", "object");

        Map<String, Object> questionProps = new LinkedHashMap<>();

        Map<String, Object> headerProp = new LinkedHashMap<>();
        headerProp.put("type", "string");
        headerProp.put("description", "Very short label (max 12 chars, e.g. 'Auth method')");
        questionProps.put("header", headerProp);

        Map<String, Object> questionTextProp = new LinkedHashMap<>();
        questionTextProp.put("type", "string");
        questionTextProp.put("description", "The complete question to ask");
        questionProps.put("question", questionTextProp);

        Map<String, Object> multiSelectProp = new LinkedHashMap<>();
        multiSelectProp.put("type", "boolean");
        multiSelectProp.put("description", "Allow multiple selections");
        questionProps.put("multiSelect", multiSelectProp);

        Map<String, Object> optionsProp = new LinkedHashMap<>();
        optionsProp.put("type", "array");
        optionsProp.put("description", "Available options (2-4 choices)");
        Map<String, Object> optionItem = new LinkedHashMap<>();
        optionItem.put("type", "object");
        Map<String, Object> optionProps = new LinkedHashMap<>();
        optionProps.put("label", Map.of("type", "string", "description", "Display text"));
        optionProps.put("description", Map.of("type", "string", "description", "Explanation"));
        optionProps.put("preview", Map.of("type", "string", "description", "Optional preview content"));
        optionItem.put("properties", optionProps);
        optionsProp.put("items", optionItem);
        questionProps.put("options", optionsProp);

        questionItem.put("properties", questionProps);
        questionsProp.put("items", questionItem);
        properties.put("questions", questionsProp);

        schema.put("properties", properties);
        schema.put("required", List.of("questions"));
        return schema;
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input input,
            ToolUseContext context,
            CanUseToolFn canUseTool,
            AssistantMessage parentMessage,
            Consumer<ToolProgress<Progress>> onProgress) {

        // If we have a global handler, use it for real user interaction
        if (globalHandler != null) {
            return globalHandler.askQuestions(input.questions())
                .thenApply(answers -> {
                    List<QuestionResult> results = new ArrayList<>();
                    for (Question question : input.questions()) {
                        String answer = answers.getOrDefault(question.header(), "No response");
                        results.add(new QuestionResult(question.question(), answer, Map.of()));
                    }
                    return ToolResult.of(new Output(results, "Questions answered", false));
                });
        }

        // Otherwise, return default responses based on the first option
        return CompletableFuture.supplyAsync(() -> {
            List<QuestionResult> results = new ArrayList<>();

            for (Question question : input.questions()) {
                // Default: select first option
                String defaultAnswer = question.options().stream()
                        .map(QuestionOption::label)
                        .findFirst()
                        .orElse("Other");

                results.add(new QuestionResult(
                        question.question(),
                        defaultAnswer,
                        Map.of("source", "default")
                ));
            }

            return ToolResult.of(new Output(
                    results,
                    "Questions processed (default responses - no interactive handler configured)",
                    false
            ));
        });
    }

    @Override
    public CompletableFuture<String> describe(Input input, ToolDescribeOptions options) {
        int count = input.questions().size();
        return CompletableFuture.completedFuture("Ask " + count + " question(s)");
    }

    @Override
    public boolean isReadOnly(Input input) {
        return true;
    }

    @Override
    public boolean requiresUserInteraction() {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(Input input) {
        return false; // User interaction blocks
    }

    @Override
    public String interruptBehavior() {
        return "cancel"; // Cancel question on new message
    }

    @Override
    public String getActivityDescription(Input input) {
        if (input.questions().isEmpty()) {
            return "Asking question";
        }
        return "Asking: " + input.questions().get(0).header();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Input parseInput(Map<String, Object> input) {
        List<Map<String, Object>> questionsData = (List<Map<String, Object>>) input.get("questions");
        List<Question> questions = new ArrayList<>();

        if (questionsData != null) {
            for (Map<String, Object> qData : questionsData) {
                String header = (String) qData.get("header");
                String question = (String) qData.get("question");
                boolean multiSelect = Boolean.TRUE.equals(qData.get("multiSelect"));

                List<Map<String, Object>> optionsData = (List<Map<String, Object>>) qData.get("options");
                List<QuestionOption> options = new ArrayList<>();
                if (optionsData != null) {
                    for (Map<String, Object> oData : optionsData) {
                        String label = (String) oData.get("label");
                        String desc = (String) oData.get("description");
                        String preview = (String) oData.get("preview");
                        options.add(new QuestionOption(label, desc, preview));
                    }
                }
                questions.add(new Question(header, question, multiSelect, options));
            }
        }

        Map<String, Object> metadata = (Map<String, Object>) input.get("metadata");
        return new Input(questions, metadata != null ? metadata : Map.of());
    }

    // ==================== Input/Output/Progress ====================

    public record Input(
            List<Question> questions,
            Map<String, Object> metadata
    ) {
        public Input(List<Question> questions) {
            this(questions, Map.of());
        }
    }

    public record Question(
            String header,
            String question,
            boolean multiSelect,
            List<QuestionOption> options
    ) {
        public Question(String header, String question, List<QuestionOption> options) {
            this(header, question, false, options);
        }
    }

    public record QuestionOption(
            String label,
            String description,
            String preview
    ) {
        public QuestionOption(String label, String description) {
            this(label, description, null);
        }
    }

    // Legacy alias for backwards compatibility
    public record Option(
            String label,
            String description,
            String preview
    ) {
        public Option(String label, String description) {
            this(label, description, null);
        }
    }

    public record Output(
            List<QuestionResult> answers,
            String summary,
            boolean isError
    ) {
        public String toResultString() {
            StringBuilder sb = new StringBuilder();
            sb.append(summary).append("\n\n");
            for (QuestionResult result : answers) {
                sb.append(result.question()).append("\n");
                sb.append("Answer: ").append(result.answer()).append("\n\n");
            }
            return sb.toString();
        }
    }

    public record QuestionResult(
            String question,
            String answer,
            Map<String, Object> annotations
    ) {}

    public record Progress(String status) implements ToolProgressData {}

    // ==================== Handler Interface ====================

    /**
     * Interface for handling user interaction requests.
     */
    public interface UserInteractionHandler {
        /**
         * Ask the user questions and get responses.
         *
         * @param questions List of questions to ask
         * @return CompletableFuture with map of header -> answer
         */
        CompletableFuture<Map<String, String>> askQuestions(List<Question> questions);
    }
}