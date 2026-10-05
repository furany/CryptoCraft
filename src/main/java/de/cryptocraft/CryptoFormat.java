package de.cryptocraft;

import java.math.BigDecimal;
import java.text.*;
import java.util.Locale;

final class CryptoFormat {
    static String price(BigDecimal price, Locale locale) {
        if (price.signum() != 0 && price.abs().compareTo(new BigDecimal("0.00000001")) < 0)
            return new DecimalFormat("0.######E0", DecimalFormatSymbols.getInstance(locale))
                    .format(price);
        int decimals =
                price.abs().compareTo(BigDecimal.ONE) >= 0
                        ? 2
                        : Math.max(2, Math.min(8, price.stripTrailingZeros().scale()));
        NumberFormat number = NumberFormat.getNumberInstance(locale);
        number.setMinimumFractionDigits(Math.min(2, decimals));
        number.setMaximumFractionDigits(decimals);
        return number.format(price);
    }
}
