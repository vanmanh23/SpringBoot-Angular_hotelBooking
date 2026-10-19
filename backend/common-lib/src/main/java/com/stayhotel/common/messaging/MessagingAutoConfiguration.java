package com.stayhotel.common.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.ExponentialBackOff;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.time.Clock;

/**
 * Outbox + idempotent consumers + DLT error handling for services that use Kafka and own the
 * {@code outbox_events}/{@code processed_messages} tables (see each service's Flyway migration).
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnProperty(name = "stayhotel.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class MessagingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MessagingAutoConfiguration.class);

    @Bean
    OutboxWriter outboxWriter(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        return new OutboxWriter(jdbc, objectMapper, clock);
    }

    @Bean
    EventReader eventReader(ObjectMapper objectMapper) {
        return new EventReader(objectMapper);
    }

    @Bean
    ProcessedMessages processedMessages(JdbcTemplate jdbc, Clock clock) {
        return new ProcessedMessages(jdbc, clock);
    }

    /** Consumer-only services (no outbox table) set stayhotel.outbox.enabled=false. */
    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnProperty(name = "stayhotel.outbox.enabled", havingValue = "true", matchIfMissing = true)
    OutboxPublisher outboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
                                    PlatformTransactionManager txManager, Clock clock) {
        return new OutboxPublisher(jdbc, kafka, new TransactionTemplate(txManager), clock);
    }

    /**
     * Three attempts with exponential backoff (1 s, 2 s), then the record goes to {@code <topic>.DLT}.
     * Unparseable messages skip the retries: they will never succeed.
     */
    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template) {
        // Explicit destination: Spring's default suffix ("-dlt") would not match our pre-created ".DLT" topics.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + Topics.DLT_SUFFIX, record.partition()));
        ExponentialBackOff backOff = new ExponentialBackOff(1000, 2.0);
        backOff.setMaxAttempts(2);
        DefaultErrorHandler handler = new DefaultErrorHandler((record, ex) -> {
            log.error("Moving message from {} (key {}) to DLT after failures: {}", record.topic(), record.key(),
                    ex.getClass().getSimpleName());
            recoverer.accept(record, ex);
        }, backOff);
        handler.addNotRetryableExceptions(DeserializationException.class, JsonProcessingException.class,
                IllegalArgumentException.class);
        return handler;
    }

    @Bean
    NewTopic paymentEventsTopic() {
        return TopicBuilder.name(Topics.PAYMENT_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic bookingEventsTopic() {
        return TopicBuilder.name(Topics.BOOKING_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic reviewEventsTopic() {
        return TopicBuilder.name(Topics.REVIEW_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic reviewEventsDlt() {
        return TopicBuilder.name(Topics.REVIEW_EVENTS + Topics.DLT_SUFFIX).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic paymentEventsDlt() {
        return TopicBuilder.name(Topics.PAYMENT_EVENTS + Topics.DLT_SUFFIX).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic bookingEventsDlt() {
        return TopicBuilder.name(Topics.BOOKING_EVENTS + Topics.DLT_SUFFIX).partitions(3).replicas(1).build();
    }
}
