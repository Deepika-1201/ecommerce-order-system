package com.ecommerce.notifications;

import java.util.UUID;

public interface EmailDelivery {

    void send(UUID notificationId, String recipient, String subject, String body);

    class Unavailable extends RuntimeException {

        public Unavailable() {
            super("Email delivery failed; notification will follow its retry policy");
        }
    }
}