package com.urlshortener.auth.validation;

import com.urlshortener.auth.dto.RegisterRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordsMatchValidator
    implements ConstraintValidator<PasswordsMatch, RegisterRequest>
{

    // A missing password is valid here: @NotBlank on the password reports it.
    @Override
    public boolean isValid(RegisterRequest request, ConstraintValidatorContext context) {
        if (request.password() == null || request.password().equals(request.confirmPassword())) {
            return true;
        }

        // Report on confirmPassword so the UI can mark that input, not the whole form.
        context.disableDefaultConstraintViolation();
        context
            .buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
            .addPropertyNode("confirmPassword")
            .addConstraintViolation();

        return false;
    }
}
