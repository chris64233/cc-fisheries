package com.chris64233.cc.fisheries.correction;

/**
 * 更正状态机：{@code PENDING → CONFIRMED / REJECTED}，已终结的更正不能再次变更。
 */
public enum CorrectionStatus {
    PENDING,
    CONFIRMED,
    REJECTED
}
