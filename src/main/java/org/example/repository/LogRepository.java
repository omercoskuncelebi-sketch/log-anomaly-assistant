package org.example.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.example.model.LogEntry;
import org.springframework.stereotype.Repository;

@Repository
public class LogRepository {

    private static final String LOG_RESOURCE = "/app-logs.jsonl";

    private final ObjectMapper objectMapper;

    public LogRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<LogEntry> findAll() {
        InputStream inputStream = LogRepository.class.getResourceAsStream(LOG_RESOURCE);
        if (inputStream == null) {
            throw new IllegalStateException("Classpath resource not found: " + LOG_RESOURCE);
        }

        try (inputStream;
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            List<LogEntry> entries = new ArrayList<>();
            String line;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }

                try {
                    entries.add(objectMapper.readValue(line, LogEntry.class));
                } catch (JsonProcessingException exception) {
                    throw new IllegalStateException(
                            "Failed to parse JSON log entry in " + LOG_RESOURCE + " at line " + lineNumber
                                    + ": " + exception.getOriginalMessage(),
                            exception
                    );
                }
            }

            return entries;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read classpath resource: " + LOG_RESOURCE, exception);
        }
    }
}
