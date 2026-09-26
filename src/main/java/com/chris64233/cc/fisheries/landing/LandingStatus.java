package com.chris64233.cc.fisheries.landing;

/**
 * 卸港申报状态机：
 * PENDING_REVIEW（待复核，已冻结配额） → CONFIRMED（已核销）/ REJECTED（已拒绝，冻结已释放）。
 */
public enum LandingStatus {
    PENDING_REVIEW,
    CONFIRMED,
    REJECTED
}
