package com.urlshortener.link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.urlshortener.TestcontainersConfiguration;
import com.urlshortener.link.entity.Link;
import com.urlshortener.link.repository.LinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LinkServiceTest {

    @Autowired
    LinkService service;

    @Autowired
    LinkRepository links;

    @MockitoBean
    SlugGenerator slugs;

    @BeforeEach
    void cleanDatabase() {
        links.deleteAll();
    }

    @Test
    void createsLinkWithGeneratedSlug() {
        when(slugs.next()).thenReturn("abc1234");

        Link link = service.create("https://example.com", null, "203.0.113.7");

        assertThat(link.getId()).isNotNull();
        assertThat(link.getSlug()).isEqualTo("abc1234");
        assertThat(link.getOwnerId()).isNull();
        assertThat(links.findBySlug("abc1234")).isPresent();
    }

    @Test
    void retriesWhenGeneratedSlugIsTaken() {
        links.saveAndFlush(new Link("taken00", "https://example.com", null, false, null, null));
        when(slugs.next()).thenReturn("taken00", "free000");

        Link link = service.create("https://example.org", null, null);

        assertThat(link.getSlug()).isEqualTo("free000");
    }

    @Test
    void givesUpAfterThreeCollisions() {
        links.saveAndFlush(new Link("taken00", "https://example.com", null, false, null, null));
        when(slugs.next()).thenReturn("taken00");

        assertThatThrownBy(() -> service.create("https://example.org", null, null)).isInstanceOf(
            DataIntegrityViolationException.class
        );
    }
}
