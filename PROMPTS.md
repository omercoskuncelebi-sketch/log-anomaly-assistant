# Development Prompts

- Set up a Java 17 Maven Spring Boot project with Jackson, JUnit 5 and Mockito.
- Implement a nullable `LogEntry` model and a classpath JSONL parser that ignores unknown fields.
- Build a simple deterministic MVP with statistics, anomaly detection, PII masking, a small LLM boundary, question answering, HTTP endpoints, tests and concise documentation.
- Keep the implementation interview-friendly, do not upload the full log file to an LLM, and report only evidence supported by the logs.
- Add an optional OpenAI-backed client selected by `OPENAI_API_KEY`, while retaining a deterministic no-network fallback.
- Review the assessment brief against the implementation; keep answers evidence-based and support its Turkish example questions.
- Let the external LLM add one clearly labeled, evidence-grounded hypothesis and one practical next investigation step, without turning hypotheses into facts.
