package com.urlshortener.link.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SlugGeneratorTest {

    private final SlugGenerator generator = new SlugGenerator();

    @Test
    void generatesSevenBase62Characters() {
        assertThat(generator.next()).matches("[0-9A-Za-z]{7}");
    }

    @Test
    void generatesDifferentSlugs() {
        Set<String> slugs = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            slugs.add(generator.next());
        }
        assertThat(slugs).hasSizeGreaterThan(995);
    }
}
