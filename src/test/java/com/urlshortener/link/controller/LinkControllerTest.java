package com.urlshortener.link.controller;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.urlshortener.TestcontainersConfiguration;
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
class LinkControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void createsAnonymousLink() throws Exception {
        mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUrl\":\"https://example.com/page\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value(matchesPattern("[0-9A-Za-z]{7}")))
                .andExpect(jsonPath("$.shortUrl").value(matchesPattern("http://localhost:8080/[0-9A-Za-z]{7}")))
                .andExpect(jsonPath("$.targetUrl").value("https://example.com/page"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void rejectsBlankTargetUrlAsProblemJson() throws Exception {
        mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON).content("{\"targetUrl\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void rejectsUnsafeTargetUrlAsProblemJson() throws Exception {
        mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUrl\":\"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Only http and https URLs are allowed"));
    }
}
