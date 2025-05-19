package io.spring.orchestrator;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.util.Assert;

public class OrchestratorWorkers {

    private final ChatClient chatClient;
    private final String orchestratorPrompt;
    private final String workerPrompt;

    public static final String DEFAULT_ORCHESTRATOR_PROMPT = """
			Analyze this task and break it down into 2-3 distinct approaches:

			Task: {task}

			Return your response in this JSON format:
			\\{
			"analysis": "Explain your understanding of the task and which variations would be valuable.
			             Focus on how each approach serves different aspects of the task.",
			"tasks": [
				\\{
				"type": "formal",
				"description": "Write a precise, technical version that emphasizes specifications"
				\\},
				\\{
				"type": "conversational",
				"description": "Write an engaging, friendly version that connects with readers"
				\\}
			]
			\\}
			""";

    public static final String DEFAULT_WORKER_PROMPT = """
			Generate content based on:
			Task: {original_task}
			Style: {task_type}
			Guidelines: {task_description}
			""";

    /**
     * Represents a subtask identified by the orchestrator that needs to be executed
     * by a worker.
     *
     * @param type        The type or category of the task (e.g., "formal",
     *                    "conversational")
     * @param description Detailed description of what the worker should accomplish
     */
    public record Task(String type, String description) {
    }

    /**
     * Response from the orchestrator containing task analysis and breakdown into
     * subtasks.
     *
     * @param analysis Detailed explanation of the task and how different approaches
     *                 serve its aspects
     * @param tasks    List of subtasks identified by the orchestrator to be
     *                 executed by workers
     */
    public record OrchestratorResponse(String analysis, List<Task> tasks) {
    }

    /**
     * Final response containing the orchestrator's analysis and combined worker
     * outputs.
     *
     * @param analysis        The orchestrator's understanding and breakdown of the
     *                        original task
     * @param workerResponses List of responses from workers, each handling a
     *                        specific subtask
     */
    public record FinalResponse(String analysis, List<String> workerResponses) {
    }

    /**
     * Creates a new OrchestratorWorkers with default prompts.
     *
     * @param chatClient The ChatClient to use for LLM interactions
     */
    public OrchestratorWorkers(ChatClient chatClient) {
        this(chatClient, DEFAULT_ORCHESTRATOR_PROMPT, DEFAULT_WORKER_PROMPT);
    }

    /**
     * Creates a new OrchestratorWorkers with custom prompts.
     *
     * @param chatClient         The ChatClient to use for LLM interactions
     * @param orchestratorPrompt Custom prompt for the orchestrator LLM
     * @param workerPrompt       Custom prompt for the worker LLMs
     */
    public OrchestratorWorkers(ChatClient chatClient, String orchestratorPrompt, String workerPrompt) {
        Assert.notNull(chatClient, "ChatClient must not be null");
        Assert.hasText(orchestratorPrompt, "Orchestrator prompt must not be empty");
        Assert.hasText(workerPrompt, "Worker prompt must not be empty");

        this.chatClient = chatClient;
        this.orchestratorPrompt = orchestratorPrompt;
        this.workerPrompt = workerPrompt;
    }

    /**
     * Reviews the description and determines the various response types a user could create for the description.
      * @param taskDescription The user's description of what marketing material needs to be produced.
     * @return OrchestratorResponse containing the options of marketing materials to produce.
     */
    public OrchestratorResponse processDescription(String taskDescription) {
        return this.chatClient.prompt()
                .user(u -> u.text(this.orchestratorPrompt)
                        .param("task", taskDescription))
                .call()
                .entity(OrchestratorResponse.class);
    }

    /**
     * Processes a task and creates the marketing material requested by the user for the type specified.
     *
     * @param taskDescription Description of the task to be processed
     * @return String containing the generated marketing material based on the task type, description and user request.
     * @throws IllegalArgumentException if taskDescription is null or empty
     */
    @SuppressWarnings("null")
    public String  process(String userRequest, String type, String taskDescription ) {
        return this.chatClient.prompt()
                .user(u -> u.text(this.workerPrompt)
                        .param("original_task", userRequest)
                        .param("task_type", type)
                        .param("task_description", taskDescription))
                .call()
                .content();
    }
}
