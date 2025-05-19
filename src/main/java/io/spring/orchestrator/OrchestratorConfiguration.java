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

    @Bean
    MessageChannel inputChannel() {
        return new DirectChannel();
    }

    @Bean
    public Executor taskExecutor() {
        return Executors.newCachedThreadPool();
    }

    @Bean
    public IntegrationFlow splitAggregateFlow(@Qualifier("orchestratorResponseTransformer") AbstractTransformer orchestratorTransformer, @Qualifier("responseTransformer") AbstractTransformer responseTransformer) {
        return IntegrationFlow.from("inputChannel")
                // Add a correlation ID to the message
                .enrichHeaders(h -> h.header("correlationId", UUID.randomUUID().toString()))
                .transform(orchestratorTransformer)
                .split(new CsvSplitter())
                .channel(c -> c.executor(Executors.newCachedThreadPool())) // parallel processing
                .transform(responseTransformer)
                .aggregate(a -> a
                        .correlationStrategy(m -> m.getHeaders().get("correlationId"))
                        .releaseStrategy(new DescriptionReleaseStrategy())
                        .outputProcessor(group -> group.getMessages().stream()
                                .toList()))
                .get();
    }

    public static class CsvSplitter extends AbstractMessageSplitter {
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

    @Bean
    AbstractTransformer responseTransformer(OrchestratorWorkers orchestratorWorkers) { // have to call orchestrator workers
        return new AbstractTransformer() {
            @Override
            protected Object doTransform(Message<?> m) {
                    String response = orchestratorWorkers.process((String)m.getHeaders().get(USER_REQUEST),
                            (String)m.getHeaders().get(TASK_TYPE), (String)m.getHeaders().get(TASK_DESCRIPTION));
                    return MessageBuilder.withPayload(response)
                            .setHeaderIfAbsent(CORRELATION_KEY, Objects.requireNonNull(m.getHeaders().get(CORRELATION_KEY)))
                            .setHeaderIfAbsent(SIZE_NAME, Objects.requireNonNull(m.getHeaders().get(SIZE_NAME)))
                            .setHeaderIfAbsent(USER_REQUEST, m.getPayload().toString())
                            .build();
            }
        };
    }

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

    @Bean
    OrchestratorWorkers orchestratorWorkers(ChatClient.Builder builder) {
        return new OrchestratorWorkers(builder.build());
    }

    public static class DescriptionReleaseStrategy implements ReleaseStrategy {
        @Override
        public boolean canRelease(MessageGroup messageGroup) {
            int messageGroupSize = messageGroup.size();
            int messageMaxCount = (Integer) messageGroup.getMessages().iterator().next().getHeaders().get(SIZE_NAME);
            return messageGroupSize >= messageMaxCount;
        }
    }


}
