package com.chris64233.cc.fisheries.transfer;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定期扫描到期待处理转让并释放冻结量。
 */
@Component
public class TransferExpiryScheduler {

    private final TransferService transferService;

    public TransferExpiryScheduler(TransferService transferService) {
        this.transferService = transferService;
    }

    @Scheduled(initialDelayString = "${fisheries.transfer-expiry.interval:60s}",
            fixedDelayString = "${fisheries.transfer-expiry.interval:60s}")
    public void expireDueTransfers() {
        transferService.expireDue();
    }
}
