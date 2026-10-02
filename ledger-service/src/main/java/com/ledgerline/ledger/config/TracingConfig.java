package com.ledgerline.ledger.config;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TracingConfig {

    /**
     * Don't observe @Scheduled runs. The outbox relay polls every 100 ms, so each empty poll
     * would otherwise show up as its own trace and bury the real ones. The relay creates its
     * own span per published event, parented on the request that wrote the row.
     */
    @Bean
    ObservationPredicate skipScheduledTaskObservations() {
        return (name, context) -> !"tasks.scheduled.execution".equals(name);
    }
}
