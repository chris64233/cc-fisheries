package com.chris64233.cc.fisheries.transfer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

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
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank String season,
            @NotBlank String species,
            @NotBlank String fromHolder,
            @NotBlank String toHolder,
            @NotNull @Positive BigDecimal quantity,
            @Positive Long expiresInSeconds) {
    }

    /** 接受 / 拒绝 / 取消携带的外部请求号，保证动作幂等。 */
    public record TransferActionRequest(
            @NotBlank @Size(max = 64) String requestId) {
    }

    /** 取消还需声明申请人，服务端校验其为转让方本人。 */
    public record CancelTransferRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank String requesterHolder) {
    }

    public record TransferResponse(Long id, String season, String species, String fromHolder,
                                   String toHolder, BigDecimal quantity, String status,
                                   String createdAt, String expiresAt, String resolvedAt) {
        static TransferResponse from(Transfer transfer) {
            return new TransferResponse(transfer.getId(), transfer.getSeason(), transfer.getSpecies(),
                    transfer.getFromHolder(), transfer.getToHolder(), transfer.getQuantity(),
                    transfer.getStatus().name(), transfer.getCreatedAt().toString(),
                    transfer.getExpiresAt().toString(),
                    transfer.getResolvedAt() == null ? null : transfer.getResolvedAt().toString());
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
    public TransferResponse accept(@PathVariable Long id, @Valid @RequestBody TransferActionRequest request) {
        return TransferResponse.from(transferService.accept(id, request.requestId()));
    }

    @PostMapping("/{id}/reject")
    public TransferResponse reject(@PathVariable Long id, @Valid @RequestBody TransferActionRequest request) {
        return TransferResponse.from(transferService.reject(id, request.requestId()));
    }

    @PostMapping("/{id}/cancel")
    public TransferResponse cancel(@PathVariable Long id, @Valid @RequestBody CancelTransferRequest request) {
        return TransferResponse.from(
                transferService.cancel(id, request.requestId(), request.requesterHolder()));
    }

    @PostMapping("/expire-due")
    public java.util.Map<String, Integer> expireDue() {
        return java.util.Map.of("expired", transferService.expireDue());
    }

    @GetMapping("/{id}")
    public TransferResponse get(@PathVariable Long id) {
        return TransferResponse.from(transferService.getTransfer(id));
    }

    /** 转让详情：双方许可证转让前后余额、台账事件、冻结明细与单据状态时间。 */
    @GetMapping("/{id}/detail")
    public TransferService.TransferDetail detail(@PathVariable Long id) {
        return transferService.getDetail(id);
    }

    @GetMapping
    public List<TransferResponse> list(@RequestParam(required = false) String season,
                                       @RequestParam(required = false) String species) {
        return transferService.listTransfers(season, species).stream().map(TransferResponse::from).toList();
    }
}
