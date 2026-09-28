# Project Rules

## Technology
- Use Java 17 only.
- Use Maven.
- Use Spring Boot 3.x compatible with Java 17.
- Use Jackson for JSON parsing.
- Use JUnit 5 and Mockito for tests.
- Do not use Java 21+ APIs or language features.

## Architecture
- Keep the architecture simple and easy to explain in a technical interview.
- Avoid unnecessary abstractions, frameworks, patterns, or overengineering.
- Prefer small focused classes with clear responsibilities.
- The application should expose a small HTTP API.

Expected endpoints:
- GET /stats
- GET /anomalies
- POST /ask

## Log Processing
- Read app-logs.jsonl as JSON Lines.
- Log fields may differ between records.
- Do not assume every field exists.
- Parse timestamps correctly.
- Calculate:
    - request count per service
    - error rate per service
    - p95 latency per service
    - hourly distribution

## Anomaly Detection
- Anomaly detection should primarily be deterministic and performed locally in Java.
- Do not send the full log file to the LLM.
- Detected anomalies must contain evidence.
- Evidence should include relevant information such as:
    - service
    - time range
    - event count
    - traceId examples
    - relevant status codes
    - relevant messages

- Do not hard-code anomaly results specifically for the provided dataset.
- Implement reusable detection logic.

## LLM Usage
- The LLM is used to explain and answer questions using already selected relevant evidence.
- The LLM must not receive the entire app-logs.jsonl file.
- Send only relevant summaries, statistics, anomalies, or filtered evidence.
- LLM integration must be behind an LlmClient interface so it can be mocked in tests.
- API keys must never be hard-coded in source code.
- Configuration should come from environment variables or application configuration.

## Privacy
- Personally identifiable information must be masked before data is sent to the LLM.
- At minimum:
    - userEmail must be masked
    - clientIp must be masked
- Raw values may be used internally for local anomaly detection.
- Masking must happen before constructing the LLM request.

## Hallucination / Unknown Information
- Never invent information not supported by logs.
- Do not claim causation when logs only show correlation.
- If the requested information does not exist in the log data, answer:
  "Bu bilgi log verisinde mevcut değil."

## Testing
- Add unit tests for important business logic.
- At minimum test:
    - log parsing
    - statistics calculation
    - anomaly detection
    - PII masking
    - question answering
- At least one automated test must mock LlmClient.
- Run Maven tests after meaningful changes.

## Documentation
Maintain:
- README.md
- PROMPTS.md

README must explain:
- architecture and approach
- what data is sent to the LLM and why
- how the approach would change for 2 million log lines
- detected anomalies and their evidence
- privacy/masking approach
- how to run the application
- how to run tests
- AI usage notes, including at least two concrete cases where AI gave an incorrect, incomplete, or unnecessarily complex suggestion

PROMPTS.md should contain prompts used during development and/or by the application.

## Development Style
- Implement incrementally.
- Do not build the entire project in one step unless explicitly requested.
- After each stage, keep the project compiling and tests passing.
- Explain important design decisions in comments only when they are not obvious from the code.