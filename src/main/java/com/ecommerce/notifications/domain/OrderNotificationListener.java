package com.ecommerce.notifications.domain;

import com.ecommerce.platform.FlowContext;
import com.ecommerce.platform.IntegrationEvent;
import com.ecommerce.platform.IntegrationEvents;
import com.ecommerce.platform.WorkerComponent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

@WorkerComponent
@ConditionalOnProperty(name = "ecom.events.enabled", havingValue = "true")
class OrderNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(OrderNotificationListener.class);

    private final IntegrationEvents events;
    private final NotificationService notifications;

    OrderNotificationListener(IntegrationEvents events, NotificationService notifications) {
        this.events = events;
        this.notifications = notifications;
    }

    @KafkaListener(id = NotificationService.CONSUMER, groupId = NotificationService.CONSUMER,
            topics = IntegrationEvents.ORDER_TOPIC, containerFactory = "notificationsKafkaFactory")
    public void receive(ConsumerRecord<byte[], byte[]> record, Acknowledgment acknowledgment) {
        IntegrationEvent event = events.read(record.topic(), record.key(), record.value());
        try (var _ = FlowContext.message(event.aggregateId(), event.messageId())) {
            notifications.accept(event);
            log.info("notification_event_processed type={}", event.type());
            acknowledgment.acknowledge();
        }
    }
}