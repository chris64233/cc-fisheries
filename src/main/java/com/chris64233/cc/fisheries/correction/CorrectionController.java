package com.chris64233.cc.fisheries.correction;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/corrections")
public class CorrectionController {

    private final CorrectionService correctionService;

    public CorrectionController(CorrectionService correctionService) {
        this.correctionService = correctionService;
    }

    public record CreateCorrectionRequest(
            @NotBlank String correctionId,
            @NotBlank String originalEventId,
            @NotNull @Positive BigDecimal correctedWeight) {
    }

    public record ReviewCorrectionRequest(
            @NotBlank String reviewEventId,
            @NotBlank String reviewer) {
    }

    public record CorrectionResponse(String correctionId, String originalEventId, String season,
                                     String species, String holder, String direction,
                                     BigDecimal correctedWeight, BigDecimal delta,
                                     BigDecimal originalConfirmedWeight, String status,
                                     long landingVersionAtCreate, long accountVersionAtCreate,
                                     long version, String createdAt, String resolvedAt) {
        static CorrectionResponse from(Correction c) {
            return new CorrectionResponse(c.getCorrectionId(), c.getOriginalEventId(), c.getSeason(),
                    c.getSpecies(), c.getHolder(), c.getDirection().name(), c.getCorrectedWeight(),
                    c.getDelta(), c.getOriginalConfirmedWeight(), c.getStatus().name(),
                    c.getLandingVersionAtCreate(), c.getAccountVersionAtCreate(), c.getVersion(),
                    c.getCreatedAt().toString(),
                    c.getResolvedAt() == null ? null : c.getResolvedAt().toString());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CorrectionResponse create(@Valid @RequestBody CreateCorrectionRequest request) {
        return CorrectionResponse.from(correctionService.create(
                request.correctionId(), request.originalEventId(), request.correctedWeight()));
    }

    @PostMapping("/{correctionId}/confirm")
    public CorrectionResponse confirm(@PathVariable String correctionId,
                                      @Valid @RequestBody ReviewCorrectionRequest request) {
        return CorrectionResponse.from(
                correctionService.confirm(correctionId, request.reviewEventId(), request.reviewer()));
    }

    @PostMapping("/{correctionId}/reject")
    public CorrectionResponse reject(@PathVariable String correctionId,
                                     @Valid @RequestBody ReviewCorrectionRequest request) {
        return CorrectionResponse.from(
                correctionService.reject(correctionId, request.reviewEventId(), request.reviewer()));
    }

    @GetMapping("/{correctionId}")
    public CorrectionResponse get(@PathVariable String correctionId) {
        return CorrectionResponse.from(correctionService.getByCorrectionId(correctionId));
    }

    @GetMapping
    public List<CorrectionResponse> listByOriginal(@RequestParam String originalEventId) {
        return correctionService.listByOriginal(originalEventId).stream()
                .map(CorrectionResponse::from).toList();
    }
}
