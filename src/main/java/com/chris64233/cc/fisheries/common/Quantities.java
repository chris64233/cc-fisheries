package com.chris64233.cc.fisheries.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.util.StringUtils;

/**
 * 数量统一使用 BigDecimal，固定 3 位小数精度。
 */
public final class Quantities {

    public static final int SCALE = 3;

    private Quantities() {
    }

    public static BigDecimal normalize(BigDecimal value, String field) {
        if (value == null) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        if (value.stripTrailingZeros().scale() > SCALE) {
            throw ApiException.badRequest(field + " 最多支持 " + SCALE + " 位小数");
        }
        return value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public static BigDecimal requirePositive(BigDecimal value, String field) {
        BigDecimal normalized = normalize(value, field);
        if (normalized.signum() <= 0) {
            throw ApiException.badRequest(field + " 必须大于 0");
        }
        return normalized;
    }

    public static void requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw ApiException.badRequest(field + " 不能为空");
        }
    }
}
