package com.urlshortener.link.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class SlugGenerator {

    static final int LENGTH = 7;
    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final SecureRandom random = new SecureRandom();

    public String next() {
        StringBuilder slug = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            slug.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return slug.toString();
    }
}
