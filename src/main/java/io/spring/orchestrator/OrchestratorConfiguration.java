package io.spring.orchestrator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.aggregator.ReleaseStrategy;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.splitter.AbstractMessageSplitter;
import org.springframework.integration.store.MessageGroup;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.integration.transformer.AbstractTransformer;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;

@EnableIntegration
@Configuration
public class OrchestratorConfiguration {

    public static final String SIZE_NAME="size_name";
    public static final String CORRELATION_KEY = "correlationId";
    public static final String TASK_TYPE = "taskType";
    public static final String TASK_DESCRIPTION = "taskDescription";
    public static final String USER_REQUEST = "userRequest";
    public static final String ANALYSIS = "analysis";

    /**
     * Creates the primary input channel for the orchestration flow.
     * 
     * @return A DirectChannel that serves as the entry point for messages in the integration flow
     */
    @Bean
    MessageChannel inputChannel() {
        return new DirectChannel();
    }

    /**
     * Provides a thread pool executor for handling concurrent tasks in the orchestration flow.
     * 
     * @return A cached thread pool executor that creates new threads as needed
     */
    @Bean
    public Executor taskExecutor() {
        return Executors.newCachedThreadPool();
    }

    /**
     * Defines the main integration flow that splits incoming requests into subtasks,
     * processes them in parallel, and aggregates the results.
     * 
     * @param orchestratorTransformer Transformer that converts user requests into orchestrator responses
     * @param responseTransformer Transformer that processes individual tasks and generates responses
     * @return An IntegrationFlow that handles the complete orchestration process
     */
    @Bean
    public IntegrationFlow splitAggregateFlow(@Qualifier("orchestratorResponseTransformer") AbstractTransformer orchestratorTransformer, @Qualifier("responseTransformer") AbstractTransformer responseTransformer) {
        return IntegrationFlow.from("inputChannel")
                // Add a correlation ID to the message
                .enrichHeaders(h -> h.header("correlationId", UUID.randomUUID().toString()))
                .transform(orchestratorTransformer)
                .split(new taskSplitter())
                .channel(c -> c.executor(Executors.newCachedThreadPool())) // parallel processing
                .transform(responseTransformer)
                .aggregate(a -> a
                        .correlationStrategy(m -> m.getHeaders().get("correlationId"))
                        .releaseStrategy(new DescriptionReleaseStrategy())
                        .outputProcessor(group -> group.getMessages().stream()
                                .toList()))
                .get();
    }

    /**
     * A message splitter that breaks down an OrchestratorResponse into individual task messages.
     * Each task from the OrchestratorResponse is converted into a separate message with
     * appropriate headers for correlation and task information.
     */
    public static class taskSplitter extends AbstractMessageSplitter {
        /**
         * Splits a message containing an OrchestratorResponse into multiple messages,
         * one for each task in the response.
         *
         * @param message The message containing an OrchestratorResponse payload
         * @return A list of messages, each corresponding to a task from the OrchestratorResponse
         */
        @Override
        protected Object splitMessage(Message<?> message) {
            OrchestratorWorkers.OrchestratorResponse payload = (OrchestratorWorkers.OrchestratorResponse) message.getPayload();
            List<Message<?>> messages = new ArrayList<>();

            for(OrchestratorWorkers.Task task : payload.tasks()) {
                messages.add(MessageBuilder.withPayload(payload)
                        .setHeaderIfAbsent(CORRELATION_KEY, Objects.requireNonNull(message.getHeaders().get(CORRELATION_KEY)))
                        .setHeaderIfAbsent(SIZE_NAME, payload.tasks().size())
                        .setHeaderIfAbsent(TASK_TYPE, task.type())
                        .setHeaderIfAbsent(TASK_DESCRIPTION, task.description())
                        .setHeaderIfAbsent(ANALYSIS, payload.analysis())
                        .setHeaderIfAbsent(USER_REQUEST, Objects.requireNonNull(message.getHeaders().get(USER_REQUEST)))
                        .build());
            }
            return messages;
        }
    }

    /**
     * Creates a transformer that processes individual tasks from the orchestrator.
     * This transformer extracts task information from message headers and uses the OrchestratorWorkers
     * to generate a response for each task.
     *
     * @param orchestratorWorkers The service that processes tasks and generates responses
     * @return A transformer that converts task messages into response messages
     */
    @Bean
    AbstractTransformer responseTransformer(OrchestratorWorkers orchestratorWorkers) {
        return new AbstractTransformer() {
            @Override
            protected Object doTransform(Message<?> m) {
                    String response = orchestratorWorkers.process((String)m.getHeaders().get(USER_REQUEST),
                            (String)m.getHeaders().get(TASK_TYPE), (String)m.getHeaders().get(TASK_DESCRIPTION));
                    return MessageBuilder.withPayload(response)
                            .setHeaderIfAbsent(CORRELATION_KEY, Objects.requireNonNull(m.getHeaders().get(CORRELATION_KEY)))
                            .setHeaderIfAbsent(SIZE_NAME, Objects.requireNonNull(m.getHeaders().get(SIZE_NAME)))
                            .setHeaderIfAbsent(USER_REQUEST, Objects.requireNonNull(m.getHeaders().get(USER_REQUEST)))
                            .build();
            }
        };
    }

    /**
     * Creates a transformer that processes the initial user request and transforms it into
     * an OrchestratorResponse containing analysis and subtasks.
     *
     * @param orchestratorWorkers The service that analyzes user requests and breaks them down into subtasks
     * @return A transformer that converts user request messages into orchestrator response messages
     */
    @Bean
    AbstractTransformer orchestratorResponseTransformer(OrchestratorWorkers orchestratorWorkers) {
        return new AbstractTransformer() {
            @Override
            protected Object doTransform(Message<?> message) {
                OrchestratorWorkers.OrchestratorResponse orchestratorResponse = orchestratorWorkers.processDescription(message.getPayload().toString());
                return MessageBuilder.withPayload(orchestratorResponse)
                        .setHeaderIfAbsent(CORRELATION_KEY, Objects.requireNonNull(message.getHeaders().get(CORRELATION_KEY)))
                        .setHeaderIfAbsent(USER_REQUEST, message.getPayload().toString())
                        .build();
            }
        };
    }

    /**
     * Creates the final integration flow that handles the output from the split-aggregate flow.
     * This flow prints the worker responses to the console.
     *
     * @param flow The upstream split-aggregate flow that processes and aggregates tasks
     * @param orchestratorWorkers The service that processes tasks (not directly used in this method)
     * @return An IntegrationFlow that handles the final output of the orchestration process
     */
    @Bean
    public IntegrationFlow myFlow(@Qualifier("splitAggregateFlow") IntegrationFlow flow, OrchestratorWorkers orchestratorWorkers) {
        return IntegrationFlow.from(flow)
                .handle(msg -> {
                    System.out.println("\n=== WORKER OUTPUT ===\n");
                    List<Message<?>> responses = (List<Message<?>>) msg.getPayload();
                    responses.forEach(s -> {
                        System.out.println(s.getPayload() + "\n=============\n");
                    });
                })
                .get();
    }

    /**
     * Creates the OrchestratorWorkers service that handles task analysis and processing.
     * This service uses a ChatClient to interact with an LLM for generating responses.
     *
     * @param builder The ChatClient.Builder used to create the ChatClient
     * @return An OrchestratorWorkers instance configured with the default prompts
     */
    @Bean
    OrchestratorWorkers orchestratorWorkers(ChatClient.Builder builder) {
        return new OrchestratorWorkers(builder.build());
    }

    /**
     * A release strategy that determines when a message group is ready to be released based on size.
     * This strategy compares the current size of the message group with the expected size
     * stored in the SIZE_NAME header of the messages.
     */
    public static class DescriptionReleaseStrategy implements ReleaseStrategy {
        /**
         * Determines if a message group can be released for further processing.
         *
         * @param messageGroup The group of messages being aggregated
         * @return true if the current size of the message group is greater than or equal to the expected size
         */
        @Override
        public boolean canRelease(MessageGroup messageGroup) {
            int messageGroupSize = messageGroup.size();
            int messageMaxCount = (Integer) messageGroup.getMessages().iterator().next().getHeaders().get(SIZE_NAME);
            return messageGroupSize >= messageMaxCount;
        }
    }


}
