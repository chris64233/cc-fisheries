package com.chris64233.cc.fisheries.transfer;

import com.chris64233.cc.fisheries.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    public record InitiateTransferRequest(@NotBlank String season,
                                          @NotBlank String species,
                                          @NotBlank String fromHolder,
                                          @NotBlank String toHolder,
                                          @NotNull BigDecimal quantity,
                                          Long ttlSeconds) {
    }

    public record HolderRequest(@NotBlank String holder) {
    }

    public record TransferResponse(Long id, String season, String species, String fromHolder, String toHolder,
                                   BigDecimal quantity, Transfer.Status status,
                                   Instant createdAt, Instant expiresAt, Instant resolvedAt) {
        static TransferResponse of(Transfer transfer) {
            return new TransferResponse(transfer.getId(), transfer.getSeason(), transfer.getSpecies(),
                    transfer.getFromHolder(), transfer.getToHolder(), transfer.getQuantity(),
                    transfer.getStatus(), transfer.getCreatedAt(), transfer.getExpiresAt(), transfer.getResolvedAt());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResponse initiate(@RequestBody @Valid InitiateTransferRequest request) {
        return TransferResponse.of(transferService.initiate(request.season(), request.species(),
                request.fromHolder(), request.toHolder(), request.quantity(), request.ttlSeconds()));
    }

    @GetMapping("/{id}")
    public TransferResponse get(@PathVariable Long id) {
        return TransferResponse.of(transferService.getTransfer(id));
    }

    @PostMapping("/{id}/accept")
    public TransferResponse accept(@PathVariable Long id, @RequestBody @Valid HolderRequest request) {
        Transfer transfer = transferService.accept(id, request.holder());
        if (transfer.getStatus() != Transfer.Status.ACCEPTED) {
            throw ApiException.conflict("转让已到期，冻结量已释放");
        }
        return TransferResponse.of(transfer);
    }

    @PostMapping("/{id}/reject")
    public TransferResponse reject(@PathVariable Long id, @RequestBody @Valid HolderRequest request) {
        return TransferResponse.of(transferService.reject(id, request.holder()));
    }

    @PostMapping("/{id}/expire")
    public TransferResponse expire(@PathVariable Long id) {
        return TransferResponse.of(transferService.expire(id));
    }
}
