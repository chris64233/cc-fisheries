package com.chris64233.cc.fisheries.hold;

/**
 * 冻结明细状态：持有中、已核销（冻结量正式核销）、已释放（回到可用）。
 */
public enum HoldStatus {
    HELD,
    SETTLED,
    RELEASED
}
