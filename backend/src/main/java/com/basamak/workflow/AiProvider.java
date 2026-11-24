package com.basamak.workflow;

import java.math.BigDecimal;

/**
 * AI step abstraction, chosen by configuration (AI_PROVIDER), not code: {@code mock} (default: zero cost, deterministic,
 * offline) or {@code ollama} (a free local model server; implemented but NOT verified here, Ollama is not installed).
 */
public interface AiProvider {
    record Result(String text, String model, int promptTokens, int completionTokens, BigDecimal costUsd) {}

    Result complete(String prompt, String model);
}
