package com.ledgerline.payments.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    /** Partitioned by payer account, so one account's payments are processed in order. */
    @Bean
    NewTopic paymentRequestedTopic(LedgerlineProperties props) {
        return TopicBuilder.name(props.topics().paymentRequested()).partitions(6).replicas(1).build();
    }

    /** Declared here too so it exists with 6 partitions even if this service starts first. */
    @Bean
    NewTopic ledgerResultsTopic(LedgerlineProperties props) {
        return TopicBuilder.name(props.topics().ledgerResults()).partitions(6).replicas(1).build();
    }

    /** Retry a failing message 3 times, 500 ms apart, then log and move on. */
    @Bean
    CommonErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(500L, 3));
    }
}
