package com.shareconnectsave.connection.client.config;

import com.shareconnectsave.connection.config.RatingServiceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

// Builder pattern (GoF) - WebClient.Builder assembles this client step by
// step (base URL, a default header, then build()) instead of one giant
// constructor. spring-boot-starter-webflux auto-configures the Builder bean
// this method takes as a parameter; this @Bean method is the one place that
// turns it into the fully-configured client RatingServiceClientImpl injects -
// same shape as discovery-service's UserServiceClientConfig (T023).
//
// Options pattern: RatingServiceProperties (bound from application.yml's
// "rating-service.base-url") is injected as a normal bean parameter here,
// same as any other collaborator, instead of a @Value-annotated String.
@Configuration
public class RatingServiceClientConfig {

    @Bean
    public WebClient ratingServiceWebClient(WebClient.Builder builder, RatingServiceProperties ratingServiceProperties) {
        return builder
                .baseUrl(ratingServiceProperties.baseUrl())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
