package dev.taufeeqahmed.servicepulse.registration;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest(properties = "servicepulse.monitoring.enabled=false")
@AutoConfigureMockMvc
@Transactional
class ServiceRegistrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MonitoredServiceRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createsAndPersistsService() throws Exception {
        var result = mockMvc.perform(post("/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("OpenAI", "https://openai.com")))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("OpenAI"))
                .andExpect(jsonPath("$.url").value("https://openai.com"))
                .andReturn();

        var services = repository.findAll();
        assertThat(services).hasSize(1);
        var saved = services.getFirst();
        assertThat(saved.getId()).isPositive();
        assertThat(saved.getName()).isEqualTo("OpenAI");
        assertThat(saved.getUrl()).isEqualTo("https://openai.com");
        assertThat(saved.getFailureThreshold()).isEqualTo(3);
        assertThat(saved.getConsecutiveFailures()).isZero();
        assertThat(saved.getWebhookUrl()).isNull();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asLong()).isEqualTo(saved.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com", "HTTPS://example.com:8443/health?ready=true"})
    void acceptsHttpAndHttpsUrls(String url) throws Exception {
        mockMvc.perform(post("/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("Example", url)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value(url));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5})
    void acceptsCustomFailureThresholdAndOptionalWebhook(int threshold) throws Exception {
        mockMvc.perform(post("/services").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"API","url":"http://localhost/health",
                                 "failureThreshold":%d,"webhookUrl":"https://example.com/hook?token=secret"}
                                """.formatted(threshold)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.failureThreshold").value(threshold))
                .andExpect(jsonPath("$.webhookConfigured").value(true))
                .andExpect(jsonPath("$.webhookUrl").doesNotExist());
        var saved = repository.findAll().getFirst();
        assertThat(saved.getFailureThreshold()).isEqualTo(threshold);
        assertThat(saved.getWebhookUrl()).isEqualTo("https://example.com/hook?token=secret");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsNonPositiveFailureThreshold(int threshold) throws Exception {
        mockMvc.perform(post("/services").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"API","url":"http://localhost/health","failureThreshold":%d}
                                """.formatted(threshold)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.failureThreshold").value("failureThreshold must be greater than zero"));
        assertThat(repository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2.9", "2.0", "2e0", "\"2\"", "\"\"", "2147483648", "-2147483649",
            "999999999999999999999999999999999999999999"})
    void rejectsNonIntegralOrOutOfRangeThresholdWithoutCoercion(String threshold) throws Exception {
        mockMvc.perform(post("/services").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"API","url":"http://localhost/health","failureThreshold":%s}
                                """.formatted(threshold)))
                .andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
    }

    @Test
    void explicitNullThresholdRetainsTheDefault() throws Exception {
        mockMvc.perform(post("/services").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"API","url":"http://localhost/health","failureThreshold":null}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.failureThreshold").value(3));
        assertThat(repository.findAll()).singleElement()
                .satisfies(service -> assertThat(service.getFailureThreshold()).isEqualTo(3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "ftp://example.com/hook", "not-a-url", "https:///hook"})
    void rejectsInvalidWebhookConfiguration(String webhook) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("name", "API", "url", "http://localhost/health",
                "webhookUrl", webhook));
        mockMvc.perform(post("/services").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.webhookUrl").isNotEmpty());
        assertThat(repository.count()).isZero();
    }

    @Test
    void listsServicesInIdOrder() throws Exception {
        var first = repository.saveAndFlush(new MonitoredService("First", "https://first.example"));
        var second = repository.saveAndFlush(new MonitoredService("Second", "http://second.example"));

        mockMvc.perform(get("/services"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(first.getId().intValue()))
                .andExpect(jsonPath("$[0].name").value("First"))
                .andExpect(jsonPath("$[0].url").value("https://first.example"))
                .andExpect(jsonPath("$[1].id").value(second.getId().intValue()))
                .andExpect(jsonPath("$[1].name").value("Second"))
                .andExpect(jsonPath("$[1].url").value("http://second.example"));
    }

    @Test
    void returnsEmptyListWhenNoServicesExist() throws Exception {
        mockMvc.perform(get("/services"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t "})
    void rejectsBlankName(String name) throws Exception {
        expectInvalidField(name, "https://example.com", "name", "name must not be blank");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t "})
    void rejectsBlankUrl(String url) throws Exception {
        expectInvalidField("Example", url, "url", "url must not be blank");
    }

    @Test
    void rejectsMissingFields() throws Exception {
        mockMvc.perform(post("/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors.name").value("name must not be blank"))
                .andExpect(jsonPath("$.errors.url").value("url must not be blank"));

        assertThat(repository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com",
            "example.com",
            "https:///health",
            "https://example.com/has space",
            "https://example.com/%broken",
            "http://example.com:65536",
            "http://example.com:-2"
    })
    void rejectsInvalidUrl(String url) throws Exception {
        expectInvalidField("Example", url, "url", "url must be a valid HTTP or HTTPS URL");
    }

    @Test
    void acceptsMaximumFieldLengths() throws Exception {
        String name = "n".repeat(255);
        String url = "https://example.com/" + "a".repeat(2048 - "https://example.com/".length());

        mockMvc.perform(post("/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(name, url)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.url").value(url));

        repository.flush();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void rejectsNameLongerThanStorageLimit() throws Exception {
        mockMvc.perform(post("/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("n".repeat(256), "https://example.com")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").isNotEmpty());

        assertThat(repository.count()).isZero();
    }

    @Test
    void rejectsUrlLongerThanStorageLimit() throws Exception {
        String url = "https://example.com/" + "a".repeat(2049 - "https://example.com/".length());

        mockMvc.perform(post("/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("Example", url)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.url").isNotEmpty());

        assertThat(repository.count()).isZero();
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.detail").isNotEmpty());

        assertThat(repository.count()).isZero();
    }

    private void expectInvalidField(String name, String url, String field, String message) throws Exception {
        mockMvc.perform(post("/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(name, url)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.errors." + field).value(message));

        assertThat(repository.count()).isZero();
    }

    private String requestBody(String name, String url) {
        Map<String, String> fields = new HashMap<>();
        fields.put("name", name);
        fields.put("url", url);
        return objectMapper.writeValueAsString(fields);
    }
}
