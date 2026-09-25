package com.chris64233.cc.fisheries.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.http.HttpStatus;

/**
 * 所有金额式数量统一使用固定精度 BigDecimal。
 */
public final class Quantities {

    public static final int SCALE = 3;

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, RoundingMode.UNNECESSARY);

    private Quantities() {
    }

    /**
     * 将输入数量规范化为固定精度，超出精度的输入直接拒绝。
     */
    public static BigDecimal normalize(BigDecimal value, String field) {
        if (value == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, field + " 不能为空");
        }
        try {
            return value.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    field + " 最多允许 " + SCALE + " 位小数");
        }
    }

    public static BigDecimal requirePositive(BigDecimal value, String field) {
        BigDecimal normalized = normalize(value, field);
        if (normalized.signum() <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, field + " 必须大于 0");
        }
        return normalized;
    }
}
