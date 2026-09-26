package com.chris64233.cc.fisheries.landing;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/landings")
public class LandingController {

    private final LandingService landingService;

    public LandingController(LandingService landingService) {
        this.landingService = landingService;
    }

    public record LandingRequest(
            @NotBlank String eventId,
            @NotBlank String vessel,
            @NotBlank String holder,
            @NotBlank String species,
            @NotBlank String season,
            @NotNull @Positive BigDecimal weight) {
    }

    public record ReviewRequest(
            @NotBlank String reviewEventId,
            @NotBlank String reviewer) {
    }

    public record LandingResponse(Long id, String eventId, String vessel, String holder, String species,
                                  String season, BigDecimal weight, BigDecimal confirmedWeight,
                                  String status, long version, String reviewer,
                                  String recordedAt, String confirmedAt, String rejectedAt) {
        static LandingResponse from(LandingRecord record) {
            return new LandingResponse(record.getId(), record.getEventId(), record.getVessel(),
                    record.getHolder(), record.getSpecies(), record.getSeason(), record.getWeight(),
                    record.getConfirmedWeight(), record.getStatus().name(), record.getVersion(),
                    record.getReviewer(), record.getRecordedAt().toString(),
                    record.getConfirmedAt() == null ? null : record.getConfirmedAt().toString(),
                    record.getRejectedAt() == null ? null : record.getRejectedAt().toString());
        }
    }

    @PostMapping
    public ResponseEntity<LandingResponse> declare(@Valid @RequestBody LandingRequest request) {
        LandingRecord record = landingService.declare(request.eventId(), request.vessel(),
                request.holder(), request.species(), request.season(), request.weight());
        return ResponseEntity.status(HttpStatus.CREATED).body(LandingResponse.from(record));
    }

    @PostMapping("/{eventId}/confirm")
    public LandingResponse confirm(@PathVariable String eventId, @Valid @RequestBody ReviewRequest request) {
        return LandingResponse.from(
                landingService.confirm(eventId, request.reviewEventId(), request.reviewer()));
    }

    @PostMapping("/{eventId}/reject")
    public LandingResponse reject(@PathVariable String eventId, @Valid @RequestBody ReviewRequest request) {
        return LandingResponse.from(
                landingService.reject(eventId, request.reviewEventId(), request.reviewer()));
    }

    @GetMapping("/{eventId}")
    public LandingResponse get(@PathVariable String eventId) {
        return LandingResponse.from(landingService.getByEventId(eventId));
    }

    @GetMapping("/{eventId}/chain")
    public LandingService.VersionChain chain(@PathVariable String eventId) {
        return landingService.getVersionChain(eventId);
    }

    @GetMapping
    public List<LandingResponse> list(@RequestParam(required = false) String season,
                                      @RequestParam(required = false) String species,
                                      @RequestParam(required = false) String holder) {
        return landingService.list(season, species, holder).stream().map(LandingResponse::from).toList();
    }
}
