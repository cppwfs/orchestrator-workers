package io.spring.orchestrator;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;

@SpringBootApplication
public class OrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }

    @Bean
    ApplicationRunner applicationRunner(@Qualifier("inputChannel") MessageChannel inputChannel) {
        return args -> {
            inputChannel.send(MessageBuilder.withPayload("Write a product description for a new eco-friendly water bottle").build());
        };
    }
}
