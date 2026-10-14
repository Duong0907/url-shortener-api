package com.urlshortener.auth.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class MaxUtf8BytesValidatorTest {

    private static MaxUtf8BytesValidator validatorWithLimit(int limit) {
        MaxUtf8Bytes annotation = mock(MaxUtf8Bytes.class);
        when(annotation.value()).thenReturn(limit);
        MaxUtf8BytesValidator validator = new MaxUtf8BytesValidator();
        validator.initialize(annotation);

        return validator;
    }

    @Test
    void nullIsValid() {
        assertThat(validatorWithLimit(72).isValid(null, null)).isTrue();
    }

    @Test
    void emptyIsValid() {
        assertThat(validatorWithLimit(72).isValid("", null)).isTrue();
    }

    @Test
    void asciiIsCountedOneBytePerCharacter() {
        MaxUtf8BytesValidator validator = validatorWithLimit(72);

        assertThat(validator.isValid("a".repeat(72), null)).isTrue();
        assertThat(validator.isValid("a".repeat(73), null)).isFalse();
    }

    @Test
    void twoByteCharactersCountTwice() {
        MaxUtf8BytesValidator validator = validatorWithLimit(72);

        assertThat(validator.isValid("é".repeat(36), null)).isTrue();
        assertThat(validator.isValid("é".repeat(37), null)).isFalse();
    }

    @Test
    void fourByteCharactersCountFourTimes() {
        MaxUtf8BytesValidator validator = validatorWithLimit(72);

        // One emoji is 2 Java chars but 4 UTF-8 bytes: 18 of them are exactly 72 bytes.
        assertThat(validator.isValid("😀".repeat(18), null)).isTrue();
        assertThat(validator.isValid("😀".repeat(19), null)).isFalse();
    }
}
