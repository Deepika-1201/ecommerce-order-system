package com.ecommerce.platform.messaging;

import com.ecommerce.platform.MessageType;

final class MessageTypes {

    private MessageTypes() {
    }

    static MessageType of(Class<?> payloadType) {
        MessageType type = payloadType.getAnnotation(MessageType.class);
        if (type == null) {
            throw new IllegalArgumentException(payloadType.getName() + " is not annotated with @MessageType");
        }
        return type;
    }

    static String key(MessageType type) {
        return type.name() + "@v" + type.version();
    }

    /** {@code ecommerce/<module>}, from the payload's package {@code com.ecommerce.<module>…}. */
    static String source(Class<?> payloadType) {
        String[] segments = payloadType.getPackageName().split("\\.");
        return segments.length > 2 ? "ecommerce/" + segments[2] : "ecommerce";
    }
}
