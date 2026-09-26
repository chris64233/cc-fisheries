package com.chris64233.cc.fisheries.landing;

public enum LandingCorrectionStatus {
    /** 已创建，等待确认；此时尚未发生任何配额变化。 */
    PENDING,
    /** 已确认，配额差额已处理。 */
    CONFIRMED
}
