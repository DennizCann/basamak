package com.basamak.workflow;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Deterministic fake model: the same prompt always gives the same answer, so tests and demos are repeatable.
 * Token counts are estimated (≈ 4 characters per token) and the cost uses a made-up price list
 * (0.15 USD / 1M input, 0.60 USD / 1M output) that is clearly labelled as simulated in the UI.
 * Prompts containing "[fail]" fail on purpose, which is how the "resume from the failed step" flow is demonstrated.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "basamak.ai-provider", havingValue = "mock", matchIfMissing = true)
public class MockAiProvider implements AiProvider {
    private static final BigDecimal IN = new BigDecimal("0.00000015");
    private static final BigDecimal OUT = new BigDecimal("0.00000060");

    @Override
    public Result complete(String prompt, String model) {
        if (prompt.contains("[fail]")) throw new IllegalStateException("Sahte model bu istemi işleyemedi ([fail] işareti).");
        String clean = prompt.replaceAll("\\s+", " ").trim();
        String head = clean.length() > 180 ? clean.substring(0, 180) + "…" : clean;
        int words = clean.isEmpty() ? 0 : clean.split(" ").length;
        String text = "Sahte yapay zekâ cevabı (" + words + " kelimelik isteme): " + head;
        int pt = Math.max(1, (int) Math.ceil(prompt.length() / 4.0));
        int ct = Math.max(1, (int) Math.ceil(text.length() / 4.0));
        BigDecimal cost = IN.multiply(BigDecimal.valueOf(pt)).add(OUT.multiply(BigDecimal.valueOf(ct))).setScale(6, RoundingMode.HALF_UP);
        return new Result(text, model == null || model.isBlank() ? "mock-small" : model, pt, ct, cost);
    }
}
