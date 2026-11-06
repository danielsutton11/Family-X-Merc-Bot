package com.familyx.mercbot.request;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Quote maths (scope doc 7.4–7.6). Discounts are Gold only and come off the whole price. */
public final class Pricing {

    private Pricing() {
    }

    /** The price after a percentage discount, rounded to the nearest dollar. */
    public static long discounted(long unitPrice, BigDecimal pct) {
        if (pct == null || pct.signum() <= 0) {
            return unitPrice;
        }
        return BigDecimal.valueOf(unitPrice)
                .multiply(BigDecimal.valueOf(100).subtract(pct))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** The discount that applies: none unless the tier is Gold-level. */
    public static BigDecimal applicableDiscount(boolean goldTier, BigDecimal pct) {
        return goldTier && pct != null && pct.signum() > 0 ? pct : BigDecimal.ZERO;
    }

    /** Total for a hit-count contract. */
    public static long total(long perHit, int targets, int hitsPerTarget) {
        return Math.multiplyExact(perHit, (long) targets * hitsPerTarget);
    }
}
