package com.ecommerce.notifications.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ecommerce.notifications.EmailDelivery;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpEmailDeliveryTests {

    private final JavaMailSender sender = mock(JavaMailSender.class);

    @Test
    void retriesHaveTheSameNotificationAndMessageIds() throws Exception {
        when(sender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
        List<MimeMessage> messages = new ArrayList<>();
        doAnswer(invocation -> {
            messages.add(invocation.getArgument(0));
            return null;
        }).when(sender).send(any(MimeMessage.class));
        var delivery = new SmtpEmailDelivery(sender, "orders@ecommerce.local");
        UUID id = UUID.randomUUID();

        delivery.send(id, "asha@example.test", "Order placed", "Order: " + id);
        delivery.send(id, "asha@example.test", "Order placed", "Order: " + id);

        assertThat(messages).hasSize(2);
        for (MimeMessage message : messages) {
            assertThat(message.getHeader("Message-ID", null)).isEqualTo("<" + id + "@ecommerce.local>");
            assertThat(message.getHeader("X-Ecommerce-Notification-Id", null)).isEqualTo(id.toString());
            assertThat(message.getAllRecipients()).hasSize(1);
            assertThat(message.getSubject()).isEqualTo("Order placed");
        }
    }

    @Test
    void smtpErrorsNeverEscapeWithRecipientOrCredentialDetails() throws Exception {
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("recipient=asha@example.test password=secret"))
                .when(sender).send(any(MimeMessage.class));
        var delivery = new SmtpEmailDelivery(sender, "orders@ecommerce.local");

        assertThatThrownBy(() -> delivery.send(UUID.randomUUID(), "asha@example.test", "Order placed", "Body"))
                .isInstanceOf(EmailDelivery.Unavailable.class)
            .hasNoCause().hasMessage("Email delivery failed; notification will follow its retry policy");
    }

    @Test
    void anInvalidRecipientIsAControlledDeliveryFailure() throws Exception {
        var delivery = new SmtpEmailDelivery(sender, "orders@ecommerce.local");
        assertThatThrownBy(() -> delivery.send(UUID.randomUUID(), "not an address", "Order placed", "Body"))
                .isInstanceOf(EmailDelivery.Unavailable.class).hasNoCause();
    }
}