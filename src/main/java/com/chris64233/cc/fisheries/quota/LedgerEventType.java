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
    /** 卸港申报核销 */
    LANDING_DEDUCT
}
