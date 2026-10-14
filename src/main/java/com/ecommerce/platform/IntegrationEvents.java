package com.ecommerce.platform;

public interface IntegrationEvents {

    String ORDER_TOPIC = "ecom.ordering.order-events";
    String STOCK_TOPIC = "ecom.inventory.stock-events";

    IntegrationEvent read(String topic, byte[] key, byte[] value);
}