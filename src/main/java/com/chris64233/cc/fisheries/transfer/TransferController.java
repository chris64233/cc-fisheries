package com.chris64233.cc.fisheries.transfer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

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
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    public record CreateTransferRequest(
            @NotBlank String requestId,
            @NotBlank String season,
            @NotBlank String species,
            @NotBlank String fromHolder,
            @NotBlank String toHolder,
            @NotNull @Positive BigDecimal quantity,
            @Positive Long expiresInSeconds) {
    }

    /** 受让方接受 / 拒绝时携带的外部请求号与操作人，保证动作幂等。 */
    public record DecisionRequest(
            @NotBlank String requestId,
            @NotBlank String reviewer) {
    }

    /** 转让方取消时携带的外部请求号与发起人标识，发起方与 fromHolder 必须一致。 */
    public record CancelRequest(
            @NotBlank String requestId,
            @NotBlank String operator) {
    }

    public record TransferResponse(Long id, String requestId, String season, String species,
                                   String fromHolder, String toHolder, BigDecimal quantity,
                                   String status, String createdAt, String expiresAt,
                                   String resolvedAt, String acceptRequestId, String rejectRequestId,
                                   String cancelRequestId) {
        static TransferResponse from(Transfer transfer) {
            return new TransferResponse(transfer.getId(), transfer.getRequestId(),
                    transfer.getSeason(), transfer.getSpecies(), transfer.getFromHolder(),
                    transfer.getToHolder(), transfer.getQuantity(), transfer.getStatus().name(),
                    transfer.getCreatedAt().toString(), transfer.getExpiresAt().toString(),
                    transfer.getResolvedAt() == null ? null : transfer.getResolvedAt().toString(),
                    transfer.getAcceptRequestId(), transfer.getRejectRequestId(),
                    transfer.getCancelRequestId());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResponse initiate(@Valid @RequestBody CreateTransferRequest request) {
        Duration ttl = request.expiresInSeconds() == null ? null : Duration.ofSeconds(request.expiresInSeconds());
        return TransferResponse.from(transferService.initiate(request.requestId(), request.season(),
                request.species(), request.fromHolder(), request.toHolder(), request.quantity(), ttl));
    }

    @PostMapping("/{id}/accept")
    public TransferResponse accept(@PathVariable Long id, @Valid @RequestBody DecisionRequest request) {
        return TransferResponse.from(transferService.accept(id, request.requestId(), request.reviewer()));
    }

    @PostMapping("/{id}/reject")
    public TransferResponse reject(@PathVariable Long id, @Valid @RequestBody DecisionRequest request) {
        return TransferResponse.from(transferService.reject(id, request.requestId(), request.reviewer()));
    }

    @PostMapping("/{id}/cancel")
    public TransferResponse cancel(@PathVariable Long id, @Valid @RequestBody CancelRequest request) {
        return TransferResponse.from(transferService.cancel(id, request.requestId(), request.operator()));
    }

    @PostMapping("/expire-due")
    public Map<String, Integer> expireDue() {
        return Map.of("expired", transferService.expireDue());
    }

    /**
     * 来源许可证额度分解：可转余额、已上岸数量、被其它申请占用数量。
     */
    @GetMapping("/availability")
    public TransferService.Availability availability(@RequestParam String season,
                                                     @RequestParam String species,
                                                     @RequestParam String holder) {
        return transferService.getAvailability(season, species, holder);
    }

    /**
     * 转让前后余额、状态、时间及双方台账轨迹，并给出双方余额与单据是否相互对应。
     */
    @GetMapping("/{id}/detail")
    public TransferService.TransferView detail(@PathVariable Long id) {
        return transferService.getTransferView(id);
    }

    @GetMapping("/{id}")
    public TransferResponse get(@PathVariable Long id) {
        return TransferResponse.from(transferService.getTransfer(id));
    }

    @GetMapping
    public List<TransferResponse> list(@RequestParam(required = false) String season,
                                       @RequestParam(required = false) String species) {
        return transferService.listTransfers(season, species).stream().map(TransferResponse::from).toList();
    }
}
