package com.apop.payment.config;

import com.apop.payment.messaging.LifecycleInbox;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "apop.messaging.enabled", havingValue = "true")
public class KafkaMessagingConfiguration {

    @Bean
    NewTopic paymentLifecycleTopic(MessagingProperties properties) {
        return topic(properties.topic(), properties);
    }

    @Bean
    NewTopic paymentLifecycleDeadLetterTopic(MessagingProperties properties) {
        return topic(properties.deadLetterTopic(), properties);
    }

    @Bean
    CommonErrorHandler lifecycleErrorHandler(
            KafkaTemplate<String, byte[]> template,
            MessagingProperties properties,
            LifecycleInbox inbox) {
        DeadLetterPublishingRecoverer publishing = new DeadLetterPublishingRecoverer(
                template,
                (record, exception) -> new TopicPartition(properties.deadLetterTopic(), record.partition()));
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(4);
        backOff.setInitialInterval(200);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(2_000);
        DefaultErrorHandler handler = new DefaultErrorHandler((record, exception) -> {
            publishing.accept(record, exception);
            inbox.recordDeadLetter(header(record, "ce_id"), header(record, "ce_type"));
        }, backOff);
        handler.setCommitRecovered(true);
        return handler;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, byte[]> kafkaListenerContainerFactory(
            ConsumerFactory<String, byte[]> consumerFactory,
            CommonErrorHandler lifecycleErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(lifecycleErrorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setConcurrency(1);
        return factory;
    }

    private static NewTopic topic(String name, MessagingProperties properties) {
        Map<String, String> configs = new HashMap<>();
        configs.put(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE);
        configs.put(TopicConfig.RETENTION_MS_CONFIG, Long.toString(Duration.ofDays(30).toMillis()));
        if (properties.replicas() >= 3) {
            configs.put(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "2");
        }
        return new NewTopic(name, properties.partitions(), properties.replicas()).configs(configs);
    }

    private static String header(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record, String name) {
        org.apache.kafka.common.header.Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null) {
            return null;
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
