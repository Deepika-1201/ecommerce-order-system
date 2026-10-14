package com.ecommerce.notifications.domain;

import com.ecommerce.platform.IntegrationEvents;
import com.ecommerce.platform.WorkerComponent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

@WorkerComponent
@ConditionalOnProperty(name = "ecom.events.enabled", havingValue = "true")
class OrderNotificationListener {

    private final IntegrationEvents events;
    private final NotificationService notifications;

    OrderNotificationListener(IntegrationEvents events, NotificationService notifications) {
        this.events = events;
        this.notifications = notifications;
    }

    @KafkaListener(id = NotificationService.CONSUMER, groupId = NotificationService.CONSUMER,
            topics = IntegrationEvents.ORDER_TOPIC, containerFactory = "notificationsKafkaFactory")
    public void receive(ConsumerRecord<byte[], byte[]> record, Acknowledgment acknowledgment) {
        notifications.accept(events.read(record.topic(), record.key(), record.value()));
        acknowledgment.acknowledge();
    }
}