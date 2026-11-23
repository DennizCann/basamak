package com.basamak.journey;

import com.basamak.common.ApiException;
import com.basamak.content.ContentType;
import com.basamak.content.PublishedContent;
import com.basamak.content.PublishedContent.Published;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Member-facing journeys. Journeys are data (CMS content), so a new journey appears here as soon as it is
 * published - no code change or deploy. Only published journeys and published trainings are ever returned.
 */
@RestController
@RequiredArgsConstructor
public class JourneyController {
    private final PublishedContent published;

    public record TrainingRef(UUID id, String title, int versionNo) {}

    public record JourneyDto(UUID id, String title, String description, int order, int versionNo,
                             List<TrainingRef> trainings, JsonNode unlock) {}

    @GetMapping("/api/journeys")
    public List<JourneyDto> list() {
        return published.list(ContentType.JOURNEY).stream()
                .map(this::dto)
                .sorted(Comparator.comparingInt(JourneyDto::order).thenComparing(JourneyDto::title))
                .toList();
    }

    @GetMapping("/api/journeys/{id}")
    public JourneyDto get(@PathVariable UUID id) {
        return published.get(id, ContentType.JOURNEY).map(this::dto)
                .orElseThrow(() -> ApiException.notFound("Journey"));
    }

    private JourneyDto dto(Published j) {
        List<TrainingRef> trainings = new ArrayList<>();
        for (JsonNode t : j.body().path("trainingIds")) {
            published.get(UUID.fromString(t.asText()), ContentType.TRAINING)
                    .ifPresent(p -> trainings.add(new TrainingRef(p.itemId(), p.title(), p.versionNo())));
        }
        return new JourneyDto(j.itemId(), j.title(), j.body().path("description").asText(""),
                j.body().path("order").asInt(), j.versionNo(), trainings, j.body().path("unlock"));
    }
}
