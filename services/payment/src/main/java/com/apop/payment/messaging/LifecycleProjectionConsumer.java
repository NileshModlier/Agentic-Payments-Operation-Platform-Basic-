package com.apop.payment.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "apop.messaging.enabled", havingValue = "true")
public class LifecycleProjectionConsumer {

    private final LifecycleInbox inbox;

    LifecycleProjectionConsumer(LifecycleInbox inbox) {
        this.inbox = inbox;
    }

    @KafkaListener(
            topics = "${apop.messaging.topic}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onMessage(ConsumerRecord<String, byte[]> record) {
        inbox.project(record.value());
    }
}
