package com.urlshortener.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class VerificationMailer {

    private final JavaMailSender sender;

    @Value("${app.mail.from}")
    private final String from;

    @Value("${app.ui-base-url}")
    private final String uiBaseUrl;

    // Never throws a MailException: a mail outage must not fail registration.
    public void sendVerification(String email, String rawToken) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Verify your email");
        message.setText(
            "Open this link to verify your email:\n\n" +
                link(rawToken) +
                "\n\nIf the link has expired, request a new one on the login page.\n" +
                "If you did not create an account, ignore this email."
        );

        try {
            sender.send(message);
        } catch (MailException e) {
            log.warn("Could not send a verification email", e);
        }
    }

    private String link(String rawToken) {
        return uiBaseUrl.replaceAll("/+$", "") + "/verify-email?token=" + rawToken;
    }
}
