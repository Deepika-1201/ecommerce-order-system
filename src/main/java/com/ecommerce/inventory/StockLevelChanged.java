package com.ecommerce.inventory;

import com.ecommerce.platform.MessageType;

@MessageType(name = "stock.level_changed", topic = "ecom.inventory.stock-events")
public record StockLevelChanged(String sku, String locationCode, long available) {
}