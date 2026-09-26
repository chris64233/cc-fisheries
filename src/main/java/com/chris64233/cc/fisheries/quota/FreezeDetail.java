package com.chris64233.cc.fisheries.quota;

import java.math.BigDecimal;

/**
 * 单笔冻结明细：转让冻结（TRANSFER）或待复核卸港冻结（LANDING）。
 *
 * @param type     TRANSFER / LANDING
 * @param ref      转让 ID 或卸港事件号
 * @param quantity 冻结数量
 * @param detail   转让为受让方，卸港为船名
 */
public record FreezeDetail(String type, String ref, BigDecimal quantity, String detail) {
}
