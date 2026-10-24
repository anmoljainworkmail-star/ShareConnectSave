package com.shareconnectsave.admin_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// @ConfigurationPropertiesScan (Options pattern, T057): auto-registers every
// @ConfigurationProperties-annotated record under this package
// (UserServiceProperties, RatingServiceProperties, ReportServiceProperties)
// as a bean — same convention discovery-service's own application class
// already established.
//
// @EnableScheduling for AdminOutboxRelay's @Scheduled polling loop lives on
// its own tiny config/SchedulingConfig.java, not here — same "keep the entry
// point ignorant of wiring detail" split report-service's and rating-service's
// application classes already use.
@SpringBootApplication
@ConfigurationPropertiesScan
public class AdminServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(AdminServiceApplication.class, args);
	}

}
