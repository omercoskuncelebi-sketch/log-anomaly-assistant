package org.example.llm;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Configuration
public class LlmClientConfiguration {

    @Bean
    public LlmClient llmClient(Environment environment, RestClient.Builder restClientBuilder) {
        String apiKey = environment.getProperty("OPENAI_API_KEY");
        if (StringUtils.hasText(apiKey)) {
            return new OpenAiLlmClient(
                    restClientBuilder.build(),
                    apiKey.trim(),
                    modelOrDefault(environment.getProperty("OPENAI_MODEL"))
            );
        }
        return new FallbackLlmClient();
    }

    private String modelOrDefault(String model) {
        return StringUtils.hasText(model) ? model.trim() : OpenAiLlmClient.DEFAULT_MODEL;
    }
}
