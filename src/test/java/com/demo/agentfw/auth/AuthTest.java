package com.demo.agentfw.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthTest {

    private static final String PASSWORD = "correct horse battery staple";

    @RestController
    static class PingController {
        @GetMapping("/api/ping")
        String ping() {
            return "pong";
        }

        @GetMapping("/actuator/health")
        String health() {
            return "UP";
        }
    }

    private static MockMvc mvc(AuthProperties props) {
        return MockMvcBuilders.standaloneSetup(new AuthController(props), new PingController())
                .addFilters(new AuthFilter(props)).build();
    }

    private static AuthProperties enabled() {
        return new AuthProperties(PASSWORD, 3, 300, 8);
    }

    private static String login(String password) {
        return "{\"password\":\"" + password + "\"}";
    }

    @Test
    void withoutAPasswordTheApplicationIsOpen() throws Exception {
        MockMvc mvc = mvc(new AuthProperties("", 5, 300, 8));

        mvc.perform(get("/api/ping")).andExpect(status().isOk());
        mvc.perform(get("/api/me")).andExpect(jsonPath("$.authRequired").value(false))
                .andExpect(jsonPath("$.authenticated").value(true));
    }

    @Test
    void anUnauthenticatedApiCallIs401AndAPageIsRedirectedToTheLoginPage() throws Exception {
        MockMvc mvc = mvc(enabled());

        mvc.perform(get("/api/ping")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
        mvc.perform(get("/")).andExpect(status().isFound()).andExpect(header().string("Location", "/login.html"));
        mvc.perform(get("/index.html")).andExpect(status().isFound());
        mvc.perform(get("/mcp.html")).andExpect(status().isFound());
        mvc.perform(get("/api/mcp/servers")).andExpect(status().isUnauthorized());
    }

    @Test
    void theLoginPageTheLoginCallAndTheHealthCheckAreOpen() throws Exception {
        MockMvc mvc = mvc(enabled());

        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/api/me")).andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.authRequired").value(true));
        int loginPage = mvc.perform(get("/login.html")).andReturn().getResponse().getStatus();
        assertThat(loginPage).isNotIn(302, 401);              // not redirected to itself (404 here: no static handler)
    }

    @Test
    void aWrongPasswordIsRefusedAndTheRightOneOpensASession() throws Exception {
        MockMvc mvc = mvc(enabled());

        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(login("nope")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_PASSWORD"))
                .andExpect(jsonPath("$.message").value("That password is not correct."));
        MvcResult ok = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(login(PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) ok.getRequest().getSession(false);

        assertThat(session).isNotNull();
        mvc.perform(get("/api/ping").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/me").session(session)).andExpect(jsonPath("$.authenticated").value(true));
        assertThat(ok.getResponse().getContentAsString()).doesNotContain(PASSWORD);
    }

    @Test
    void signingOutEndsTheSession() throws Exception {
        MockMvc mvc = mvc(enabled());
        MvcResult ok = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(login(PASSWORD)))
                .andReturn();
        MockHttpSession session = (MockHttpSession) ok.getRequest().getSession(false);

        mvc.perform(post("/api/logout").session(session)).andExpect(status().isOk());

        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/ping").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void tooManyWrongPasswordsLockTheClientOutEvenForTheRightPassword() throws Exception {
        MockMvc mvc = mvc(enabled());

        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(login("guess" + i)))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(login(PASSWORD)))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
    }

    @Test
    void signingInGivesAFreshSessionIdSoAPlantedOneIsUseless() throws Exception {
        MockMvc mvc = mvc(enabled());
        MockHttpSession planted = new MockHttpSession();
        String plantedId = planted.getId();

        MvcResult ok = mvc.perform(post("/api/login").session(planted).contentType(MediaType.APPLICATION_JSON)
                .content(login(PASSWORD))).andReturn();

        assertThat(planted.isInvalid()).isTrue();
        assertThat(ok.getRequest().getSession(false).getId()).isNotEqualTo(plantedId);
    }

    @Test
    void thePasswordIsNeverPartOfTheConfigurationsStringForm() {
        assertThat(enabled().toString()).doesNotContain(PASSWORD).contains("enabled=true");
    }
}
