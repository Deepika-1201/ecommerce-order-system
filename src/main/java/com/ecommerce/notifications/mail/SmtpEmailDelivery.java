package com.ecommerce.notifications.mail;

import com.ecommerce.notifications.EmailDelivery;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
class SmtpEmailDelivery implements EmailDelivery {

    private final JavaMailSender sender;
    private final InternetAddress from;

    SmtpEmailDelivery(JavaMailSender sender,
            @Value("${ecom.notifications.from:orders@ecommerce.local}") String from) throws MessagingException {
        this.sender = sender;
        this.from = new InternetAddress(from, true);
        this.from.validate();
    }

    @Override
    public void send(UUID notificationId, String recipient, String subject, String body) {
        try {
            InternetAddress address = new InternetAddress(recipient, true);
            address.validate();
            MimeMessage message = sender.createMimeMessage();
            message.setFrom(from);
            message.setRecipient(Message.RecipientType.TO, address);
            message.setSubject(subject, "UTF-8");
            message.setText(body, "UTF-8");
            message.setHeader("Message-ID", "<" + notificationId + "@ecommerce.local>");
            message.setHeader("X-Ecommerce-Notification-Id", notificationId.toString());
            sender.send(message);
        } catch (MessagingException | MailException failure) {
            throw new EmailDelivery.Unavailable();
        }
    }
}