package com.apop.settlement.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
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

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "apop.messaging.enabled", havingValue = "true")
public class KafkaMessagingConfiguration {

    @Bean
    NewTopic settlementItemTopic(SettlementProperties properties) {
        return topic(properties.messaging().itemTopic(), properties);
    }

    @Bean
    NewTopic settlementExecutorDeadLetterTopic(SettlementProperties properties) {
        return topic(properties.messaging().deadLetterTopic(), properties);
    }

    @Bean
    CommonErrorHandler settlementErrorHandler(KafkaTemplate<String, byte[]> template, SettlementProperties properties) {
        DeadLetterPublishingRecoverer publishing = new DeadLetterPublishingRecoverer(
                template,
                (record, exception) -> new TopicPartition(properties.messaging().deadLetterTopic(), record.partition()));
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(4);
        backOff.setInitialInterval(200);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(2_000);
        DefaultErrorHandler handler = new DefaultErrorHandler(publishing, backOff);
        handler.setCommitRecovered(true);
        return handler;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, byte[]> kafkaListenerContainerFactory(
            ConsumerFactory<String, byte[]> consumerFactory,
            CommonErrorHandler settlementErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(settlementErrorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setConcurrency(1);
        return factory;
    }

    private static NewTopic topic(String name, SettlementProperties properties) {
        Map<String, String> configs = new HashMap<>();
        configs.put(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE);
        configs.put(TopicConfig.RETENTION_MS_CONFIG, Long.toString(Duration.ofDays(30).toMillis()));
        if (properties.messaging().replicas() >= 3) {
            configs.put(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "2");
        }
        return new NewTopic(name, properties.messaging().partitions(), properties.messaging().replicas()).configs(configs);
    }
}
