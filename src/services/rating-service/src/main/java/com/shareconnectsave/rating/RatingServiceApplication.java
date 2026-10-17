package com.shareconnectsave.rating;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Package-by-feature, not layer (java-spring-boot skill): this class only
// boots the app. Package name is com.shareconnectsave.rating, not the
// Spring-Initializr-default com.shareconnectsave.rating_service (artifactId
// with underscores substituted for hyphens) — every other Java service in
// this platform (discovery, connection) is rooted at com.shareconnectsave.<name>
// with no "_service" suffix, so later tickets' feature packages
// (com.shareconnectsave.rating.submission, .trustscore, .kafka, ...) slot in
// consistently with their siblings instead of inheriting a one-off name.
@SpringBootApplication
public class RatingServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(RatingServiceApplication.class, args);
	}

}
