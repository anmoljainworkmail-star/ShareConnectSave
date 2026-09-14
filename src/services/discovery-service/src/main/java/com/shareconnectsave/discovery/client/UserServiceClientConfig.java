package com.shareconnectsave.discovery.client;

import com.shareconnectsave.discovery.config.UserServiceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

// Builder pattern (GoF) — WebClient.Builder assembles this client step by
// step (base URL, a default header, then build()) instead of one giant
// constructor. spring-boot-starter-webflux auto-configures the Builder bean
// this method takes as a parameter; this @Bean method is the one place that
// turns it into the fully-configured client every other class injects.
//
// Options pattern (T023) — UserServiceProperties (bound from application.yml's
// "user-service.base-url") is injected as a normal bean parameter here,
// same as any other collaborator, instead of a @Value-annotated String.
@Configuration
public class UserServiceClientConfig {

    @Bean
    public WebClient userServiceWebClient(WebClient.Builder builder, UserServiceProperties userServiceProperties) {
        return builder
                .baseUrl(userServiceProperties.baseUrl())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
