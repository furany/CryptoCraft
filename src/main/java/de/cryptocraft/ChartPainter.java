package de.cryptocraft;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure raster rendering from immutable inputs; safe to run off the server thread. */
final class ChartPainter {
    private static final int SIZE = 128;
    private static final int PLOT_LEFT = 44;
    private static final int PLOT_RIGHT = 123;
    private static final int PLOT_TOP = 38;
    private static final int PLOT_BOTTOM = 97;
    private final Color BACKGROUND;
    private final Color PLOT_BACKGROUND;
    private final Color GRID;
    private static final Color UP = new Color(36, 190, 125);
    private static final Color DOWN = new Color(229, 77, 85);
    private static final Color STALE_TEXT = new Color(247, 177, 61);
    private static final Color NEUTRAL = new Color(87, 159, 220);
    private final Color TEXT;
    private final Color MUTED_TEXT;
    private static final Font HEADER_FONT = new Font(Font.MONOSPACED, Font.BOLD, 9);
    private static final Font PRICE_FONT = new Font(Font.MONOSPACED, Font.BOLD, 10);
    private static final Font LABEL_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 8);

    private final CryptoBoard board;
    private final CryptoPriceService.Quote quote;
    private final List<CryptoPriceService.PricePoint> history;
    private final Map<String, String> labels;
    private final boolean stale;
    private final Instant now;

    ChartPainter(
            CryptoBoard board,
            CryptoPriceService.Quote quote,
            List<CryptoPriceService.PricePoint> history,
            Map<String, String> labels,
            boolean stale,
            Instant now) {
        this.board = board;
        this.quote = quote;
        this.history = List.copyOf(history);
        this.labels = Map.copyOf(labels);
        this.stale = stale;
        this.now = now;
        boolean light = board.settings().theme().equals("light");
        boolean ocean = board.settings().theme().equals("ocean");
        BACKGROUND =
                light
                        ? new Color(244, 247, 251)
                        : ocean ? new Color(7, 29, 43) : new Color(14, 20, 31);
        PLOT_BACKGROUND =
                light ? Color.WHITE : ocean ? new Color(10, 37, 53) : new Color(18, 26, 38);
        GRID = light ? new Color(210, 218, 230) : new Color(40, 51, 67);
        TEXT = light ? new Color(24, 36, 52) : new Color(224, 231, 240);
        MUTED_TEXT = light ? new Color(66, 82, 102) : new Color(153, 169, 188);
    }

    private String label(String key) {
        return labels.getOrDefault(key, key);
    }

    private String label(String key, Map<String, String> values) {
        String text = label(key);
        for (var entry : values.entrySet())
            text = text.replace("%" + entry.getKey() + "%", entry.getValue());
        return text;
    }

    BufferedImage drawFrame() {
        int size = board.settings().size();
        BufferedImage image =
                new BufferedImage(SIZE * size, SIZE * size, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.scale(size, size);
            graphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            graphics.setRenderingHint(
                    RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            graphics.setColor(BACKGROUND);
            graphics.fillRect(0, 0, SIZE, SIZE);

            List<CryptoPriceService.PricePoint> points = getVisibleHistory();
            int historyHours = getChartHistoryHours();
            PriceRange scale = calculateScale(points);
            TimeWindow timeWindow = getTimeWindow(points, historyHours);

            drawHeader(graphics, quote);
            if (board.settings().style().equals("price")) {
                graphics.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
                graphics.setColor(TEXT);
                String label = quote == null ? label("chartLoading") : formatPrice(quote.price());
                while (graphics.getFontMetrics().stringWidth(label) > 118
                        && graphics.getFont().getSize() > 7)
                    graphics.setFont(
                            graphics.getFont()
                                    .deriveFont((float) graphics.getFont().getSize() - 1));
                graphics.drawString(label, 4, 68);
                drawFooter(graphics, points.size(), timeWindow, historyHours);
                return image;
            }
            drawGrid(graphics, scale, timeWindow);
            if (points.size() >= 2 && scale != null) {
                drawPriceLine(graphics, points, scale, timeWindow);
            } else if (points.size() == 1 && scale != null) {
                drawLatestPoint(graphics, points.get(0), scale, timeWindow);
            }
            if (board.settings().style().equals("compact") && quote != null) {
                graphics.setColor(TEXT);
                graphics.setFont(PRICE_FONT);
                graphics.drawString(formatPrice(quote.price()), PLOT_LEFT + 3, PLOT_TOP + 12);
            }
            drawFooter(graphics, points.size(), timeWindow, historyHours);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private void drawHeader(Graphics2D graphics, CryptoPriceService.Quote quote) {
        graphics.setFont(HEADER_FONT);
        graphics.setColor(MUTED_TEXT);
        String heading =
                board.settings().name().isBlank()
                        ? board.symbol() + "/" + board.currency()
                        : board.settings().name();
        while (graphics.getFontMetrics().stringWidth(heading) > 62 && heading.length() > 1)
            heading = heading.substring(0, heading.length() - 1);
        graphics.drawString(heading, 4, 10);

        graphics.setFont(LABEL_FONT);
        if (quote == null) {
            graphics.setColor(TEXT);
            String loading = label("chartLoading");
            graphics.drawString(
                    loading, PLOT_RIGHT - graphics.getFontMetrics().stringWidth(loading), 10);
        } else {
            if (isStale(quote)) {
                long ageMinutes = Math.max(0, Duration.between(quote.updatedAt(), now).toMinutes());
                String staleLabel =
                        (label("language").equals("de") ? "ALT " : "STALE ")
                                + formatAge(ageMinutes);
                graphics.setColor(STALE_TEXT);
                graphics.drawString(
                        staleLabel,
                        PLOT_RIGHT - graphics.getFontMetrics().stringWidth(staleLabel),
                        10);
            } else if (quote.change24h() != null) {
                String sign = quote.change24h().signum() > 0 ? "+" : "";
                String change =
                        new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.US))
                                .format(quote.change24h());
                String changeLabel = label("chartChangeLabel") + " " + sign + change + "%";
                graphics.setColor(quote.change24h().signum() >= 0 ? UP : DOWN);
                graphics.drawString(
                        changeLabel,
                        PLOT_RIGHT - graphics.getFontMetrics().stringWidth(changeLabel),
                        10);
            }

            graphics.setFont(PRICE_FONT);
            graphics.setColor(TEXT);
            graphics.drawString(formatPrice(quote.price()), 4, 25);
            graphics.setFont(LABEL_FONT);
            graphics.setColor(MUTED_TEXT);
            String currency = board.currency();
            graphics.drawString(
                    currency, PLOT_RIGHT - graphics.getFontMetrics().stringWidth(currency), 25);
        }

        graphics.setColor(GRID);
        graphics.drawLine(4, 30, PLOT_RIGHT, 30);
    }

    private void drawGrid(Graphics2D graphics, PriceRange scale, TimeWindow timeWindow) {
        graphics.setColor(PLOT_BACKGROUND);
        graphics.fillRect(PLOT_LEFT, PLOT_TOP, PLOT_RIGHT - PLOT_LEFT, PLOT_BOTTOM - PLOT_TOP);
        graphics.setColor(GRID);
        graphics.drawRect(PLOT_LEFT, PLOT_TOP, PLOT_RIGHT - PLOT_LEFT, PLOT_BOTTOM - PLOT_TOP);

        graphics.setFont(LABEL_FONT);
        FontMetrics metrics = graphics.getFontMetrics();
        for (int row = 0; row <= 4; row++) {
            int y = PLOT_TOP + (PLOT_BOTTOM - PLOT_TOP) * row / 4;
            graphics.setColor(GRID);
            graphics.drawLine(PLOT_LEFT, y, PLOT_RIGHT, y);
            if (scale != null) {
                double price = scale.maximum() - (scale.maximum() - scale.minimum()) * row / 4.0;
                String label = formatAxisPrice(price);
                int labelX = PLOT_LEFT - 5 - metrics.stringWidth(label);
                graphics.setColor(MUTED_TEXT);
                graphics.drawString(label, labelX, y + 3);
            }
        }

        int middleX = PLOT_LEFT + (PLOT_RIGHT - PLOT_LEFT) / 2;
        graphics.setColor(GRID);
        graphics.drawLine(PLOT_LEFT, PLOT_TOP, PLOT_LEFT, PLOT_BOTTOM);
        graphics.drawLine(middleX, PLOT_TOP, middleX, PLOT_BOTTOM);
        graphics.drawLine(PLOT_RIGHT, PLOT_TOP, PLOT_RIGHT, PLOT_BOTTOM);
        drawTimeLabels(graphics, metrics, timeWindow, middleX);
    }

    private void drawTimeLabels(
            Graphics2D graphics, FontMetrics metrics, TimeWindow timeWindow, int middleX) {
        graphics.setFont(LABEL_FONT);
        graphics.setColor(MUTED_TEXT);
        String startLabel = "-" + formatDuration(timeWindow.visibleMinutes());
        String middleLabel = "-" + formatDuration(Math.max(1, timeWindow.visibleMinutes() / 2));
        String endLabel = label("chartNow");
        graphics.drawString(startLabel, PLOT_LEFT, 109);
        graphics.drawString(middleLabel, middleX - metrics.stringWidth(middleLabel) / 2, 109);
        graphics.drawString(endLabel, PLOT_RIGHT - metrics.stringWidth(endLabel), 109);
    }

    private void drawPriceLine(
            Graphics2D graphics,
            List<CryptoPriceService.PricePoint> points,
            PriceRange scale,
            TimeWindow timeWindow) {
        BigDecimal firstPrice = points.get(0).price();
        BigDecimal lastPrice = points.get(points.size() - 1).price();
        int direction = lastPrice.compareTo(firstPrice);
        Color lineColor = direction > 0 ? UP : direction < 0 ? DOWN : NEUTRAL;
        long startMillis = timeWindow.start().toEpochMilli();
        long endMillis = timeWindow.end().toEpochMilli();
        Path2D.Double line = new Path2D.Double();
        Path2D.Double area = new Path2D.Double();
        int firstX = xForTime(points.get(0).observedAt(), startMillis, endMillis);
        int firstY = yForPrice(firstPrice, scale);
        line.moveTo(firstX, firstY);
        area.moveTo(firstX, PLOT_BOTTOM);
        area.lineTo(firstX, firstY);
        int lastX = firstX;
        int lastY = firstY;
        for (int index = 1; index < points.size(); index++) {
            CryptoPriceService.PricePoint point = points.get(index);
            int x = xForTime(point.observedAt(), startMillis, endMillis);
            int y = yForPrice(point.price(), scale);
            line.lineTo(x, y);
            area.lineTo(x, y);
            lastX = x;
            lastY = y;
        }
        area.lineTo(lastX, PLOT_BOTTOM);
        area.closePath();

        graphics.setColor(withAlpha(lineColor, 48));
        graphics.fill(area);
        graphics.setColor(withAlpha(lineColor, 100));
        graphics.setStroke(
                new BasicStroke(
                        1f,
                        BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_ROUND,
                        1f,
                        new float[] {2f, 3f},
                        0f));
        graphics.drawLine(PLOT_LEFT, lastY, PLOT_RIGHT, lastY);
        graphics.setColor(lineColor);
        graphics.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.draw(line);
        graphics.setColor(TEXT);
        graphics.fillOval(lastX - 3, lastY - 3, 7, 7);
        graphics.setColor(lineColor);
        graphics.fillOval(lastX - 1, lastY - 1, 3, 3);
    }

    private void drawLatestPoint(
            Graphics2D graphics,
            CryptoPriceService.PricePoint point,
            PriceRange scale,
            TimeWindow timeWindow) {
        graphics.setColor(NEUTRAL);
        long startMillis = timeWindow.start().toEpochMilli();
        long endMillis = timeWindow.end().toEpochMilli();
        int x = xForTime(point.observedAt(), startMillis, endMillis);
        int y = yForPrice(point.price(), scale);
        graphics.setColor(TEXT);
        graphics.fillOval(x - 3, y - 3, 7, 7);
        graphics.setColor(NEUTRAL);
        graphics.fillOval(x - 1, y - 1, 3, 3);
    }

    private void drawFooter(
            Graphics2D graphics, int pointCount, TimeWindow timeWindow, int historyHours) {
        graphics.setFont(LABEL_FONT);
        graphics.setColor(MUTED_TEXT);
        List<CryptoPriceService.PricePoint> points = getVisibleHistory();
        String footer =
                board.settings().showRange() && !points.isEmpty()
                        ? "L "
                                + formatAxisPrice(
                                        points.stream()
                                                .mapToDouble(p -> p.price().doubleValue())
                                                .min()
                                                .orElse(0))
                                + " H "
                                + formatAxisPrice(
                                        points.stream()
                                                .mapToDouble(p -> p.price().doubleValue())
                                                .max()
                                                .orElse(0))
                        : pointCount < 2
                                ? label("chartCollecting")
                                : label(
                                        "chartCoverage",
                                        Map.of(
                                                "visible",
                                                        formatDuration(timeWindow.visibleMinutes()),
                                                "window", formatDuration(historyHours * 60),
                                                "samples", String.valueOf(pointCount)));
        graphics.drawString(footer, 4, 122);
    }

    private PriceRange calculateScale(List<CryptoPriceService.PricePoint> points) {
        if (points.isEmpty()) {
            return null;
        }
        double minimum =
                points.stream().mapToDouble(point -> point.price().doubleValue()).min().orElse(0.0);
        double maximum =
                points.stream().mapToDouble(point -> point.price().doubleValue()).max().orElse(0.0);
        if (minimum == maximum) {
            double padding = Math.max(Math.abs(maximum) * 0.01, Math.ulp(maximum) * 8);
            minimum -= padding;
            maximum += padding;
        }
        return new PriceRange(minimum, maximum);
    }

    private TimeWindow getTimeWindow(List<CryptoPriceService.PricePoint> points, int historyHours) {
        Instant end = now;
        Instant start = end.minus(Duration.ofHours(historyHours));
        if (!points.isEmpty() && points.get(0).observedAt().isAfter(start)) {
            start = points.get(0).observedAt();
        }
        long durationMillis =
                Math.max(Duration.ofMinutes(1).toMillis(), Duration.between(start, end).toMillis());
        start = end.minusMillis(durationMillis);
        int visibleMinutes = Math.max(1, (int) Math.ceil(durationMillis / 60_000.0));
        return new TimeWindow(start, end, visibleMinutes);
    }

    private int xForTime(Instant observedAt, long startMillis, long endMillis) {
        long windowMillis = Math.max(1L, endMillis - startMillis);
        double offset = (observedAt.toEpochMilli() - startMillis) / (double) windowMillis;
        double boundedOffset = Math.max(0.0, Math.min(1.0, offset));
        return PLOT_LEFT + (int) Math.round(boundedOffset * (PLOT_RIGHT - PLOT_LEFT));
    }

    private int yForPrice(BigDecimal price, PriceRange scale) {
        double range = scale.maximum() - scale.minimum();
        double offset = (price.doubleValue() - scale.minimum()) / range;
        double boundedOffset = Math.max(0.0, Math.min(1.0, offset));
        return PLOT_BOTTOM - (int) Math.round(boundedOffset * (PLOT_BOTTOM - PLOT_TOP));
    }

    private List<CryptoPriceService.PricePoint> getVisibleHistory() {
        Instant cutoff = now.minus(Duration.ofHours(getChartHistoryHours()));
        return history.stream().filter(point -> !point.observedAt().isBefore(cutoff)).toList();
    }

    private int getChartHistoryHours() {
        return board.settings().hours();
    }

    private boolean isStale(CryptoPriceService.Quote quote) {
        return stale;
    }

    private String formatAge(long minutes) {
        if (minutes < 60) {
            return minutes + "m";
        }
        if (minutes < 1440) {
            long hours = minutes / 60;
            long remainingMinutes = minutes % 60;
            return remainingMinutes == 0 ? hours + "h" : hours + "h" + remainingMinutes + "m";
        }
        long days = minutes / 1440;
        long remainingHours = minutes % 1440 / 60;
        return remainingHours == 0 ? days + "d" : days + "d" + remainingHours + "h";
    }

    private String formatDuration(int minutes) {
        if (minutes < 60) {
            return minutes + "m";
        }
        if (minutes < 1440) {
            int hours = minutes / 60;
            return minutes % 60 == 0 ? hours + "h" : hours + "h" + minutes % 60 + "m";
        }
        int days = minutes / 1440;
        return minutes % 1440 == 0 ? days + "d" : days + "d" + (minutes % 1440 / 60) + "h";
    }

    private Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private String formatAxisPrice(double price) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.US);
        double absolute = Math.abs(price);
        if (absolute >= 1_000_000_000_000.0) {
            return formatCompact(price / 1_000_000_000_000.0, "T", symbols);
        }
        if (absolute >= 1_000_000_000.0) {
            return formatCompact(price / 1_000_000_000.0, "B", symbols);
        }
        if (absolute >= 1_000_000.0) {
            return formatCompact(price / 1_000_000.0, "M", symbols);
        }
        if (absolute >= 1_000.0) {
            return formatCompact(price / 1_000.0, "K", symbols);
        }
        if (absolute >= 1.0) {
            return new DecimalFormat("0.##", symbols).format(price);
        }
        if (absolute >= 0.01) {
            return new DecimalFormat("0.####", symbols).format(price);
        }
        return new DecimalFormat("0.0E0", symbols).format(price).replace("E", "e");
    }

    private String formatCompact(double value, String suffix, DecimalFormatSymbols symbols) {
        String pattern = Math.abs(value) >= 100 ? "0" : Math.abs(value) >= 10 ? "0.#" : "0.##";
        return new DecimalFormat(pattern, symbols).format(value) + suffix;
    }

    private String formatPrice(BigDecimal price) {
        return CryptoFormat.price(
                price, label("language").equals("de") ? Locale.GERMANY : Locale.US);
    }

    private record PriceRange(double minimum, double maximum) {}

    private record TimeWindow(Instant start, Instant end, int visibleMinutes) {}
}
