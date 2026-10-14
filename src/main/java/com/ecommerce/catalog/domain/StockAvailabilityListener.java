package com.ecommerce.catalog.domain;

import com.ecommerce.platform.IntegrationEvents;
import com.ecommerce.platform.WorkerComponent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

@WorkerComponent
@ConditionalOnProperty(name = "ecom.events.enabled", havingValue = "true")
class StockAvailabilityListener {

    private final IntegrationEvents events;
    private final AvailabilityHints hints;

    StockAvailabilityListener(IntegrationEvents events, AvailabilityHints hints) {
        this.events = events;
        this.hints = hints;
    }

    @KafkaListener(id = AvailabilityHints.CONSUMER, groupId = AvailabilityHints.CONSUMER,
            topics = IntegrationEvents.STOCK_TOPIC, containerFactory = "availabilityKafkaFactory")
    public void receive(ConsumerRecord<byte[], byte[]> record, Acknowledgment acknowledgment) {
        hints.apply(events.read(record.topic(), record.key(), record.value()));
        acknowledgment.acknowledge();
    }
}