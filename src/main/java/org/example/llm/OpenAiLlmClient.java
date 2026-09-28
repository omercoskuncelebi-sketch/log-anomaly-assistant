package org.example.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Client for an OpenAI-compatible Responses API.
 * The caller is responsible for masking the prompt.
 */
public class OpenAiLlmClient implements LlmClient {

    public static final String DEFAULT_MODEL = "gpt-4o-mini";

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final String responsesUrl;

    public OpenAiLlmClient(
            RestClient restClient,
            String apiKey,
            String model,
            String baseUrl) {

        this.restClient = restClient;
        this.apiKey = apiKey;
        this.model = model;
        this.responsesUrl = normalizeBaseUrl(baseUrl) + "/responses";
    }

    @Override
    public String ask(String prompt) {
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalArgumentException("LLM prompt must not be blank.");
        }

        try {
            JsonNode response = restClient.post()
                    .uri(responsesUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "model", model,
                            "input", prompt
                    ))
                    .retrieve()
                    .body(JsonNode.class);

            String output = extractText(response);

            if (!StringUtils.hasText(output)) {
                throw new IllegalStateException(
                        "LLM API response did not contain text output."
                );
            }

            return output;

        } catch (RestClientResponseException exception) {
            throw new IllegalStateException(
                    "LLM API request failed with status "
                            + exception.getStatusCode().value()
                            + ". Response: "
                            + exception.getResponseBodyAsString(),
                    exception
            );

        } catch (RestClientException exception) {
            throw new IllegalStateException(
                    "LLM API request failed due to a network or provider error.",
                    exception
            );
        }
    }

    private String extractText(JsonNode response) {
        if (response == null) {
            return "";
        }

        String providerError = response.path("error").path("message").asText();

        if (StringUtils.hasText(providerError)) {
            throw new IllegalStateException(
                    "LLM API returned an error: " + providerError
            );
        }

        StringBuilder text = new StringBuilder();

        for (JsonNode outputItem : response.path("output")) {
            if (!"message".equals(outputItem.path("type").asText())) {
                continue;
            }

            for (JsonNode contentItem : outputItem.path("content")) {
                if ("output_text".equals(contentItem.path("type").asText())) {

                    if (!text.isEmpty()) {
                        text.append('\n');
                    }

                    text.append(contentItem.path("text").asText());
                }
            }
        }

        return text.toString();
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalArgumentException("LLM base URL must not be blank.");
        }

        String trimmed = baseUrl.trim();

        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        return trimmed;
    }
}