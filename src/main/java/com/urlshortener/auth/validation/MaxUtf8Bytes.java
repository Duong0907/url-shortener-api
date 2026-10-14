package com.urlshortener.auth.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// @Size counts characters, but BCrypt rejects passwords above 72 bytes.
@Documented
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
@Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER })
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxUtf8Bytes {
    int value();

    String message() default "must be at most {value} bytes when encoded as UTF-8";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
