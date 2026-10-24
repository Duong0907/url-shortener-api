package com.urlshortener.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class VerificationMailerTest {

    @Mock
    JavaMailSender sender;

    @Test
    void sendsTheLinkToTheRecipientFromTheConfiguredAddress() {
        mailer("https://ui.test").sendVerification("alice@example.com", "tok-123");

        SimpleMailMessage message = sentMessage();
        assertThat(message.getTo()).containsExactly("alice@example.com");
        assertThat(message.getFrom()).isEqualTo("no-reply@urlshortener.test");
        assertThat(message.getSubject()).isEqualTo("Verify your email");
        assertThat(message.getText()).contains("https://ui.test/verify-email?token=tok-123");
    }

    @Test
    void doesNotDoubleTheSlashWhenTheBaseUrlEndsWithOne() {
        mailer("https://ui.test/").sendVerification("alice@example.com", "tok-123");

        assertThat(sentMessage().getText())
            .contains("https://ui.test/verify-email?token=tok-123")
            .doesNotContain("https://ui.test//verify-email");
    }

    @Test
    void aMailOutageDoesNotPropagate() {
        doThrow(new MailSendException("smtp down")).when(sender).send(any(SimpleMailMessage.class));

        assertThatCode(() ->
            mailer("https://ui.test").sendVerification("alice@example.com", "tok-123")
        ).doesNotThrowAnyException();
    }

    private VerificationMailer mailer(String uiBaseUrl) {
        return new VerificationMailer(sender, "no-reply@urlshortener.test", uiBaseUrl);
    }

    private SimpleMailMessage sentMessage() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());

        return captor.getValue();
    }
}
