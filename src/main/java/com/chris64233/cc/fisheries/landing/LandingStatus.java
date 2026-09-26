package com.chris64233.cc.fisheries.landing;

/**
 * 卸港申报复核状态。
 *
 * <p>状态机：
 * <ul>
 *   <li>{@code PENDING_REVIEW → CONFIRMED}：港口复核人确认重量，冻结量正式核销；</li>
 *   <li>{@code PENDING_REVIEW → REJECTED}：复核驳回，冻结量释放回可用；</li>
 *   <li>{@code CONFIRMED → CORRECTED}：存在已生效的更正，原记录台账保留，当前有效重量以版本链最新记录为准。</li>
 * </ul>
 */
public enum LandingStatus {
    PENDING_REVIEW,
    CONFIRMED,
    REJECTED,
    CORRECTED
}
