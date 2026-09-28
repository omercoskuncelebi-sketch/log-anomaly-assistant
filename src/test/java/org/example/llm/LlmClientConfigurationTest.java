package org.example.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

class LlmClientConfigurationTest {

    @Test
    void selectsUsableFallbackWhenOpenAiApiKeyIsMissing() {
        Environment environment = mock(Environment.class);
        RestClient.Builder restClientBuilder = mock(RestClient.Builder.class);
        when(environment.getProperty("OPENAI_API_KEY")).thenReturn(null);

        LlmClient client = new LlmClientConfiguration().llmClient(environment, restClientBuilder);

        assertInstanceOf(FallbackLlmClient.class, client);
        assertEquals(
                "Yerel kanıta dayalı yanıt.",
                client.ask("Yanıt taslağı:\nYerel kanıta dayalı yanıt.\nKanıt:\nseçilmiş kanıt")
        );
        verifyNoInteractions(restClientBuilder);
    }

    @Test
    void selectsFallbackWhenOpenAiApiKeyIsBlank() {
        Environment environment = mock(Environment.class);
        RestClient.Builder restClientBuilder = mock(RestClient.Builder.class);
        when(environment.getProperty("OPENAI_API_KEY")).thenReturn("   ");

        LlmClient client = new LlmClientConfiguration().llmClient(environment, restClientBuilder);

        assertInstanceOf(FallbackLlmClient.class, client);
        verifyNoInteractions(restClientBuilder);
    }

    @Test
    void selectsOpenAiClientFromOpenAiEnvironmentVariables() {
        Environment environment = mock(Environment.class);
        RestClient.Builder restClientBuilder = mock(RestClient.Builder.class);
        RestClient restClient = mock(RestClient.class);
        when(environment.getProperty("OPENAI_API_KEY")).thenReturn("test-key");
        when(restClientBuilder.build()).thenReturn(restClient);

        LlmClient client = new LlmClientConfiguration().llmClient(environment, restClientBuilder);

        assertInstanceOf(OpenAiLlmClient.class, client);
        verify(restClientBuilder).build();
    }

    @Test
    void selectsOpenAiCompatibleClientFromGenericLlmEnvironmentVariables() {
        Environment environment = mock(Environment.class);
        RestClient.Builder restClientBuilder = mock(RestClient.Builder.class);
        RestClient restClient = mock(RestClient.class);
        when(environment.getProperty("LLM_API_KEY")).thenReturn("test-key");
        when(restClientBuilder.build()).thenReturn(restClient);

        LlmClient client = new LlmClientConfiguration().llmClient(environment, restClientBuilder);

        assertInstanceOf(OpenAiLlmClient.class, client);
        verify(restClientBuilder).build();
    }
}
