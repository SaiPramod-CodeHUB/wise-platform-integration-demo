package com.sai.wise.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money is BigDecimal. Never double. Never float.
 *
 * <p>This is not pedantry. 0.1 + 0.2 in binary floating point is
 * 0.30000000000000004. Do that a few million times across a payments ledger
 * and your reconciliation breaks, your books disagree with the partner's, and
 * somebody has to work out which one is right.
 *
 * <p>Scale and rounding mode are explicit. HALF_UP matches how a human expects
 * currency to round; the scale comes from the currency itself (most are 2dp,
 * JPY is 0, some are 3).
 */
public record Money(BigDecimal amount, String currency) {

    public Money {
        if (amount == null) throw new IllegalArgumentException("amount is required");
        if (currency == null || currency.isBlank()) throw new IllegalArgumentException("currency is required");
        amount = amount.setScale(scaleFor(currency), RoundingMode.HALF_UP);
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    private static int scaleFor(String currency) {
        return switch (currency.toUpperCase()) {
            case "JPY", "KRW", "VND" -> 0;
            case "BHD", "KWD", "OMR", "TND" -> 3;
            default -> 2;
        };
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }
}
