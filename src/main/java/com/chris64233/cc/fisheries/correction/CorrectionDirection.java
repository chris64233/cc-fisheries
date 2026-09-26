package com.chris64233.cc.fisheries.correction;

/**
 * 更正方向：增重需要再次取得配额（冻结后核销）；减重归还实际差额。
 */
public enum CorrectionDirection {
    INCREASE,
    DECREASE
}
