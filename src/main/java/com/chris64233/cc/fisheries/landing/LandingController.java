package com.chris64233.cc.fisheries.landing;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

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
            @NotBlank String reviewer,
            @NotNull @Positive BigDecimal confirmedWeight) {
    }

    public record RejectReviewRequest(
            @NotBlank String reviewEventId,
            @NotBlank String reviewer) {
    }

    public record CorrectionRequest(
            @NotBlank String correctionNo,
            @NotNull @PositiveOrZero BigDecimal correctedWeight) {
    }

    public record LandingResponse(Long id, String eventId, String vessel, String holder, String species,
                                  String season, BigDecimal weight, BigDecimal confirmedWeight,
                                  String status, long version, String recordedAt, String reviewedAt) {
        static LandingResponse from(LandingRecord record) {
            return new LandingResponse(record.getId(), record.getEventId(), record.getVessel(),
                    record.getHolder(), record.getSpecies(), record.getSeason(), record.getWeight(),
                    record.getConfirmedWeight(), record.getStatus().name(), record.getVersion(),
                    record.getRecordedAt().toString(),
                    record.getReviewedAt() == null ? null : record.getReviewedAt().toString());
        }
    }

    public record ReviewResponse(Long id, String reviewEventId, String landingEventId, String holder,
                                 String species, String season, String reviewer, BigDecimal declaredWeight,
                                 String decision, BigDecimal confirmedWeight, String reviewedAt) {
        static ReviewResponse from(LandingReview review, String landingEventId) {
            return new ReviewResponse(review.getId(), review.getReviewEventId(), landingEventId,
                    review.getHolder(), review.getSpecies(), review.getSeason(), review.getReviewer(),
                    review.getDeclaredWeight(), review.getDecision().name(), review.getConfirmedWeight(),
                    review.getReviewedAt().toString());
        }
    }

    public record CorrectionResponse(Long id, String correctionNo, String landingEventId,
                                     BigDecimal originalWeight, BigDecimal correctedWeight,
                                     long expectedLandingVersion, long expectedAccountVersion,
                                     String status, String createdAt, String confirmedAt) {
        static CorrectionResponse from(LandingCorrection correction, String landingEventId) {
            return new CorrectionResponse(correction.getId(), correction.getCorrectionNo(), landingEventId,
                    correction.getOriginalWeight(), correction.getCorrectedWeight(),
                    correction.getExpectedLandingVersion(), correction.getExpectedAccountVersion(),
                    correction.getStatus().name(), correction.getCreatedAt().toString(),
                    correction.getConfirmedAt() == null ? null : correction.getConfirmedAt().toString());
        }
    }

    public record VersionChainResponse(LandingResponse landing, ReviewResponse review,
                                       List<CorrectionResponse> corrections) {
    }

    @PostMapping
    public ResponseEntity<LandingResponse> declare(@Valid @RequestBody LandingRequest request) {
        LandingRecord record = landingService.declare(request.eventId(), request.vessel(),
                request.holder(), request.species(), request.season(), request.weight());
        return ResponseEntity.status(HttpStatus.CREATED).body(LandingResponse.from(record));
    }

    /** 港口复核确认重量（可不同于申报重量），复核事件号幂等。 */
    @PostMapping("/{eventId}/reviews/confirm")
    public ReviewResponse confirmReview(@PathVariable String eventId,
                                        @Valid @RequestBody ReviewRequest request) {
        LandingReview review = landingService.confirmReview(request.reviewEventId(), eventId,
                request.reviewer(), request.confirmedWeight());
        return ReviewResponse.from(review, eventId);
    }

    /** 港口复核拒绝，释放全部冻结量，复核事件号幂等。 */
    @PostMapping("/{eventId}/reviews/reject")
    public ReviewResponse rejectReview(@PathVariable String eventId,
                                       @Valid @RequestBody RejectReviewRequest request) {
        LandingReview review = landingService.rejectReview(request.reviewEventId(), eventId,
                request.reviewer());
        return ReviewResponse.from(review, eventId);
    }

    /** 创建称重更正（引用原申报，不动配额）；更正号幂等。 */
    @PostMapping("/{eventId}/corrections")
    public ResponseEntity<CorrectionResponse> createCorrection(
            @PathVariable String eventId, @Valid @RequestBody CorrectionRequest request) {
        LandingCorrection correction = landingService.createCorrection(request.correctionNo(), eventId,
                request.correctedWeight());
        return ResponseEntity.status(HttpStatus.CREATED).body(CorrectionResponse.from(correction, eventId));
    }

    /** 确认更正：按实际差额追扣或归还；旧版本决定被拒绝，重复确认幂等。 */
    @PostMapping("/corrections/{correctionNo}/confirm")
    public CorrectionResponse confirmCorrection(@PathVariable String correctionNo) {
        LandingCorrection correction = landingService.confirmCorrection(correctionNo);
        String landingEventId = landingService.getLandingEventId(correction.getLandingId());
        return CorrectionResponse.from(correction, landingEventId);
    }

    /** 申报版本链：原申报 + 复核结论 + 更正链。 */
    @GetMapping("/{eventId}/versions")
    public VersionChainResponse versions(@PathVariable String eventId) {
        LandingVersionChain chain = landingService.getVersionChain(eventId);
        LandingResponse landing = LandingResponse.from(chain.landing());
        ReviewResponse review = chain.review() == null ? null
                : ReviewResponse.from(chain.review(), chain.landing().getEventId());
        List<CorrectionResponse> corrections = chain.corrections().stream()
                .map(c -> CorrectionResponse.from(c, chain.landing().getEventId())).toList();
        return new VersionChainResponse(landing, review, corrections);
    }

    @GetMapping("/{eventId}")
    public LandingResponse get(@PathVariable String eventId) {
        return LandingResponse.from(landingService.getByEventId(eventId));
    }

    @GetMapping
    public List<LandingResponse> list(@RequestParam(required = false) String season,
                                      @RequestParam(required = false) String species,
                                      @RequestParam(required = false) String holder) {
        return landingService.list(season, species, holder).stream().map(LandingResponse::from).toList();
    }
}
