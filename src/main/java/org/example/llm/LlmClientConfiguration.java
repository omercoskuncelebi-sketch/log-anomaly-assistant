package org.example.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Configuration
public class LlmClientConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmClientConfiguration.class);
    private static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1";

    @Bean
    public LlmClient llmClient(Environment environment, RestClient.Builder restClientBuilder) {
        String apiKey = firstConfiguredValue(environment, "LLM_API_KEY", "OPENAI_API_KEY");
        if (StringUtils.hasText(apiKey)) {
            LOGGER.info("Using the OpenAI-compatible LLM client.");
            return new OpenAiLlmClient(
                    restClientBuilder.build(),
                    apiKey.trim(),
                    modelOrDefault(firstConfiguredValue(environment, "LLM_MODEL", "OPENAI_MODEL")),
                    baseUrlOrDefault(environment.getProperty("LLM_BASE_URL"))
            );
        }

        LOGGER.info("Using the deterministic fallback client because no LLM API key is configured.");
        return new FallbackLlmClient();
    }

    private String firstConfiguredValue(Environment environment, String preferredName, String fallbackName) {
        String preferredValue = environment.getProperty(preferredName);
        return StringUtils.hasText(preferredValue) ? preferredValue : environment.getProperty(fallbackName);
    }

    private String modelOrDefault(String model) {
        return StringUtils.hasText(model) ? model.trim() : OpenAiLlmClient.DEFAULT_MODEL;
    }

    private String baseUrlOrDefault(String baseUrl) {
        return StringUtils.hasText(baseUrl) ? baseUrl.trim() : DEFAULT_OPENAI_BASE_URL;
    }
}
