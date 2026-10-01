package com.ledgerline.ledger.config;

import com.ledgerline.ledger.messaging.MalformedEventException;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    @Bean
    NewTopic ledgerResultsTopic(LedgerlineProperties props) {
        return TopicBuilder.name(props.topics().ledgerResults()).partitions(6).replicas(1).build();
    }

    /** Declared here too so it exists with 6 partitions even if the ledger starts first. */
    @Bean
    NewTopic paymentRequestedTopic(LedgerlineProperties props) {
        return TopicBuilder.name(props.topics().paymentRequested()).partitions(6).replicas(1).build();
    }

    /** Same partition count as the source topic: the recoverer keeps the original partition. */
    @Bean
    NewTopic paymentRequestedDeadLetterTopic(LedgerlineProperties props) {
        return TopicBuilder.name(props.topics().paymentRequested() + ".DLT").partitions(6).replicas(1).build();
    }

    /**
     * A message that keeps failing (e.g. the database is down) is retried 3 times, then parked
     * on the dead-letter topic so it doesn't block the partition. Malformed messages skip the
     * retries because they will never succeed.
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        var handler = new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), new FixedBackOff(500L, 3));
        handler.addNotRetryableExceptions(MalformedEventException.class);
        return handler;
    }
}
