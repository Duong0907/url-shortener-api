package com.urlshortener.auth.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.urlshortener.auth.dto.RegisterRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

// Uses the real Bean Validation engine, so the property path of the violation is checked too.
class PasswordsMatchValidatorTest {

    static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsEqualPasswords() {
        assertThat(confirmViolations("correct-horse", "correct-horse")).isEmpty();
    }

    @Test
    void rejectsDifferentPasswordsOnTheConfirmPasswordField() {
        Set<ConstraintViolation<RegisterRequest>> violations = confirmViolations(
            "correct-horse",
            "correct-horsE"
        );

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo("must match password");
    }

    @Test
    void comparisonIsCaseSensitive() {
        assertThat(confirmViolations("Password1", "password1")).hasSize(1);
    }

    @Test
    void comparisonDoesNotTrimWhitespace() {
        assertThat(confirmViolations("correct-horse", "correct-horse ")).hasSize(1);
    }

    @Test
    void rejectsMissingConfirmation() {
        assertThat(confirmViolations("correct-horse", null)).hasSize(1);
        assertThat(confirmViolations("correct-horse", "")).hasSize(1);
    }

    @Test
    void doesNotAddASecondErrorWhenThePasswordItselfIsMissing() {
        // @NotBlank already reports the missing password; this check stays silent.
        assertThat(confirmViolations(null, "correct-horse")).isEmpty();
    }

    private static Set<ConstraintViolation<RegisterRequest>> confirmViolations(
        String password,
        String confirmPassword
    ) {
        RegisterRequest request = new RegisterRequest(
            "alice@example.com",
            password,
            confirmPassword
        );

        return VALIDATOR.validate(request)
            .stream()
            .filter(v -> v.getPropertyPath().toString().equals("confirmPassword"))
            .collect(Collectors.toSet());
    }
}
