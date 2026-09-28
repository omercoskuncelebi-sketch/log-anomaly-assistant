package org.example.llm;

/**
 * Deterministic fallback used when no external LLM is configured.
 * This class is not an LLM and never sends data outside the application.
 */
public class FallbackLlmClient implements LlmClient {

    @Override
    public String ask(String prompt) {
        String marker = "Yanıt taslağı:\n";
        int start = prompt.indexOf(marker);
        int end = prompt.indexOf("\nKanıt:\n");
        if (start >= 0 && end > start) {
            return prompt.substring(start + marker.length(), end);
        }
        return "Yerel log kanıtlarına göre: " + prompt;
    }
}
