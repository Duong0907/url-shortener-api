package com.urlshortener.redirect;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.urlshortener.TestcontainersConfiguration;
import com.urlshortener.link.entity.Link;
import com.urlshortener.link.repository.LinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RedirectControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    LinkRepository links;

    @BeforeEach
    void cleanDatabase() {
        links.deleteAll();
    }

    @Test
    void redirectsWith302AndNoStore() throws Exception {
        links.saveAndFlush(new Link("abc1234", "https://example.com/page", null, false, null, null));

        mvc.perform(get("/abc1234"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/page"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void returns404ForUnknownSlug() throws Exception {
        mvc.perform(get("/nope123"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:link-not-found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void healthIsNotTreatedAsSlug() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk());
    }
}
