package com.shareconnectsave.admin_service.client;

import com.shareconnectsave.admin_service.config.RatingServiceProperties;
import com.shareconnectsave.admin_service.config.ReportServiceProperties;
import com.shareconnectsave.admin_service.config.UserServiceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

// Builder pattern (GoF), plain English: a builder assembles something step by
// step (set the base URL, set a default header, THEN build it) instead of one
// constructor call that takes every option at once. Think of ordering a
// sandwich by naming each ingredient one at a time rather than reciting a
// 12-word code to the cashier — WebClient.Builder works the same way: each
// .baseUrl(...)/.defaultHeader(...) call returns the same builder so they
// chain, and nothing is actually built until .build() runs. Same exact shape
// as discovery-service's UserServiceClientConfig.
//
// Three beans in ONE config class, not three near-identical files (discovery-
// service gave UserServiceClientConfig its own file because it was the only
// outbound client that service had at setup time): admin-service's whole
// reason to exist is aggregating exactly these three upstream services, so
// keeping their construction side by side here is more readable than three
// files that would only ever be read together anyway.
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient userServiceWebClient(WebClient.Builder builder, UserServiceProperties userServiceProperties) {
        return builder
                .baseUrl(userServiceProperties.baseUrl())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Bean
    public WebClient ratingServiceWebClient(WebClient.Builder builder, RatingServiceProperties ratingServiceProperties) {
        return builder
                .baseUrl(ratingServiceProperties.baseUrl())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Bean
    public WebClient reportServiceWebClient(WebClient.Builder builder, ReportServiceProperties reportServiceProperties) {
        return builder
                .baseUrl(reportServiceProperties.baseUrl())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
