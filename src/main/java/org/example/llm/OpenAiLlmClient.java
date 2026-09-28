package org.example.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Client for the OpenAI Responses API. The caller is responsible for masking the prompt. */
public class OpenAiLlmClient implements LlmClient {

    public static final String DEFAULT_MODEL = "gpt-4o-mini";

    private static final String RESPONSES_PATH = "https://api.openai.com/v1/responses";

    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    public OpenAiLlmClient(RestClient restClient, String apiKey, String model) {
        this.restClient = restClient;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String ask(String prompt) {
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalArgumentException("OpenAI prompt must not be blank.");
        }

        try {
            JsonNode response = restClient.post()
                    .uri(RESPONSES_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", model, "input", prompt, "store", false))
                    .retrieve()
                    .body(JsonNode.class);

            String output = extractText(response);
            if (!StringUtils.hasText(output)) {
                throw new IllegalStateException("OpenAI API response did not contain text output.");
            }
            return output;
        } catch (RestClientResponseException exception) {
            throw new IllegalStateException(
                    "OpenAI API request failed with status " + exception.getStatusCode().value() + ".",
                    exception
            );
        } catch (RestClientException exception) {
            throw new IllegalStateException("OpenAI API request failed due to a network or provider error.", exception);
        }
    }

    private String extractText(JsonNode response) {
        if (response == null) {
            return "";
        }

        String providerError = response.path("error").path("message").asText();
        if (StringUtils.hasText(providerError)) {
            throw new IllegalStateException("OpenAI API returned an error: " + providerError);
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
}
