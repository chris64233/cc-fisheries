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
    /** 卸港申报进入待复核，冻结可用配额 */
    LANDING_FREEZE,
    /** 卸港复核通过，冻结量正式核销 */
    LANDING_CONSUME,
    /** 卸港复核驳回，冻结量释放回可用 */
    LANDING_RELEASE,
    /** 更正增重：申报后再次取得配额，冻结可用量 */
    CORRECTION_FREEZE,
    /** 更正增重复核通过，冻结量正式核销 */
    CORRECTION_CONSUME,
    /** 更正复核驳回（增重），冻结量释放回可用 */
    CORRECTION_RELEASE,
    /** 更正减重复核通过，已核销中的实际差额归还可用 */
    CORRECTION_REFUND
}
