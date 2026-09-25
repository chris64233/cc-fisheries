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
            @NotBlank String season,
            @NotBlank String species,
            @NotBlank String fromHolder,
            @NotBlank String toHolder,
            @NotNull @Positive BigDecimal quantity,
            @Positive Long expiresInSeconds) {
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
        return TransferResponse.from(transferService.initiate(request.season(), request.species(),
                request.fromHolder(), request.toHolder(), request.quantity(), ttl));
    }

    @PostMapping("/{id}/accept")
    public TransferResponse accept(@PathVariable Long id) {
        return TransferResponse.from(transferService.accept(id));
    }

    @PostMapping("/{id}/reject")
    public TransferResponse reject(@PathVariable Long id) {
        return TransferResponse.from(transferService.reject(id));
    }

    @PostMapping("/expire-due")
    public Map<String, Integer> expireDue() {
        return Map.of("expired", transferService.expireDue());
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
