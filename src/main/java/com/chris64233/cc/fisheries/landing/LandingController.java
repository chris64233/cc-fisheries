package com.chris64233.cc.fisheries.landing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

    public record DeclareLandingRequest(@NotBlank String eventId,
                                        @NotBlank String vessel,
                                        @NotBlank String holder,
                                        @NotBlank String species,
                                        @NotBlank String season,
                                        @NotNull BigDecimal weight) {
    }

    public record LandingResponse(Long id, String eventId, String vessel, String holder, String species,
                                  String season, BigDecimal weight, Instant createdAt, boolean replayed) {
        static LandingResponse of(LandingRecord record, boolean replayed) {
            return new LandingResponse(record.getId(), record.getEventId(), record.getVessel(),
                    record.getHolder(), record.getSpecies(), record.getSeason(), record.getWeight(),
                    record.getCreatedAt(), replayed);
        }
    }

    @PostMapping
    public ResponseEntity<LandingResponse> declare(@RequestBody @Valid DeclareLandingRequest request) {
        LandingService.LandingResult result = landingService.declare(request.eventId(), request.vessel(),
                request.holder(), request.species(), request.season(), request.weight());
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(LandingResponse.of(result.record(), result.replayed()));
    }

    @GetMapping("/{eventId}")
    public LandingResponse get(@PathVariable String eventId) {
        return LandingResponse.of(landingService.getByEventId(eventId), false);
    }

    @GetMapping
    public List<LandingResponse> listByHolder(@RequestParam String holder) {
        return landingService.listByHolder(holder).stream()
                .map(record -> LandingResponse.of(record, false))
                .toList();
    }
}
