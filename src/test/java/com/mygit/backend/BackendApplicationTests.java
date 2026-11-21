package com.mygit.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mygit.backend.dto.CreateRepoReq;
import com.mygit.backend.dto.LoginReq;
import com.mygit.backend.dto.RegisterReq;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BackendApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String TEST_ID = String.valueOf(System.currentTimeMillis()).substring(7);
    private static final String TEST_USER = "user" + TEST_ID;
    private static final String TEST_EMAIL = "user" + TEST_ID + "@mygit.dev";
    private static final String TEST_PASS = "TestSecret123!";
    private static String jwtToken;

    @Test
    @Order(1)
    void contextLoads() {
    }

    @Test
    @Order(2)
    void shouldRegisterUserSuccessfully() throws Exception {
        RegisterReq req = new RegisterReq(TEST_USER, TEST_EMAIL, TEST_PASS);

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.username").value(TEST_USER))
                .andReturn();

        Map<?, ?> resMap = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        jwtToken = (String) resMap.get("token");
    }

    @Test
    @Order(3)
    void shouldLoginAndReceiveJwt() throws Exception {
        LoginReq req = new LoginReq(TEST_EMAIL, TEST_PASS);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.username").value(TEST_USER));
    }

    @Test
    @Order(4)
    void shouldRejectUnauthenticatedRepoAccess() throws Exception {
        mockMvc.perform(get("/api/repos"))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(5)
    void shouldCreateAndListIsolatedUserRepositories() throws Exception {
        String repoName = "repo-" + TEST_ID;
        CreateRepoReq createReq = new CreateRepoReq(repoName, "Isolated VCS Repo", true);

        mockMvc.perform(post("/api/repos")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(repoName))
                .andExpect(jsonPath("$.ownerUsername").value(TEST_USER));

        mockMvc.perform(get("/api/repos")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value(repoName))
                .andExpect(jsonPath("$[0].ownerUsername").value(TEST_USER));
    }
}
