package com.basamak.workflow;

import java.math.BigDecimal;

/**
 * AI step abstraction. Only {@link MockAiProvider} is implemented (zero cost, deterministic, works offline).
 * A real provider (e.g. local Ollama) would implement the same interface and be selected with AI_PROVIDER.
 */
public interface AiProvider {
    record Result(String text, String model, int promptTokens, int completionTokens, BigDecimal costUsd) {}

    Result complete(String prompt, String model);
}
