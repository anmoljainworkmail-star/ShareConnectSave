package com.shareconnectsave.rating.config;

import com.shareconnectsave.rating.domain.TrustScoreCalculator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Single Responsibility (SOLID-S): this class's only job is wiring
// TrustScoreCalculator into the Spring context as a bean, the same "own tiny
// config class" split SchedulingConfig already uses for @EnableScheduling.
//
// TrustScoreCalculator itself stays annotation-free on purpose (T043: "pure,
// no I/O, no @Service") - putting `new TrustScoreCalculator()` here, instead
// of letting @Service/@Component touch the class directly, is what lets
// TrustScoreRecalculationServiceImpl receive it via ordinary constructor
// injection without the calculator ever needing to know Spring exists.
@Configuration
public class TrustScoreCalculatorConfig {

    @Bean
    public TrustScoreCalculator trustScoreCalculator() {
        return new TrustScoreCalculator();
    }
}
