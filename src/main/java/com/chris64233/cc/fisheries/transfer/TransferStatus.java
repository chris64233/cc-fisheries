package com.chris64233.cc.fisheries.transfer;

public enum TransferStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    /** 转让方在受让方接受前主动取消，冻结量已完整释放。 */
    CANCELLED,
    EXPIRED
}
