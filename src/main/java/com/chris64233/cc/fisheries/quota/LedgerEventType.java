package com.chris64233.cc.fisheries.quota;

public enum LedgerEventType {
    /** 账户核准入账 */
    GRANT,
    /** 发起转让，冻结可用配额 */
    TRANSFER_FREEZE,
    /** 转让被拒绝或到期，释放冻结 */
    TRANSFER_RELEASE,
    /** 转让被接受，转出方冻结量正式扣减 */
    TRANSFER_OUT,
    /** 转让被接受，受让方可用量增加 */
    TRANSFER_IN,
    /** 卸港申报进入待复核，冻结权利人可用配额 */
    LANDING_FREEZE,
    /** 复核确认重量等于申报重量，冻结量正式核销 */
    LANDING_SETTLE,
    /** 复核拒绝申报，或确认重量小于申报重量时释放剩余冻结量 */
    LANDING_RELEASE,
    /** 复核确认重量大于申报重量，先释放全部冻结量，再从可用量追扣差额 */
    REVIEW_CONSUME,
    /** 更正确认增加重量，从可用量追扣差额 */
    CORRECTION_DEDUCT,
    /** 更正确认减少重量，向已核销归还实际差额（回到可用量） */
    CORRECTION_RETURN
}
