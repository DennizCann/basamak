package com.basamak.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Free local model server (https://ollama.com), selected with AI_PROVIDER=ollama. Cost is 0 (runs on your machine);
 * tokens come from Ollama's counters. NOT verified in this project: Ollama is not installed on the development machine,
 * so only the mock provider is tested. The base URL is configuration, never user input (no SSRF surface).
 */
@Component
@ConditionalOnProperty(name = "basamak.ai-provider", havingValue = "ollama")
public class OllamaAiProvider implements AiProvider {
    private final ObjectMapper json;
    private final String baseUrl;
    private final String defaultModel;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public OllamaAiProvider(ObjectMapper json, @Value("${basamak.ai.ollama-url:http://localhost:11434}") String baseUrl,
                            @Value("${basamak.ai.ollama-model:llama3.2}") String defaultModel) {
        this.json = json;
        this.baseUrl = baseUrl;
        this.defaultModel = defaultModel;
    }

    @Override
    public Result complete(String prompt, String model) {
        String m = model == null || model.isBlank() || model.startsWith("mock") ? defaultModel : model;
        try {
            String body = json.writeValueAsString(json.createObjectNode().put("model", m).put("prompt", prompt).put("stream", false));
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/generate"))
                    .timeout(Duration.ofMinutes(2)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new IllegalStateException("Yerel model hata döndürdü (HTTP " + res.statusCode() + ").");
            JsonNode r = json.readTree(res.body());
            return new Result(r.path("response").asText(), m, r.path("prompt_eval_count").asInt(0), r.path("eval_count").asInt(0), BigDecimal.ZERO);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Yerel modele ulaşılamadı: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Model çağrısı kesildi.");
        }
    }
}
