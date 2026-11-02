package de.cryptocraft;

import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.math.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;

/** Immutable render input. Coordinates use the map-wall resolution, never a scaled 128px UI. */
final class ChartPainter {
    private static final Color UP = new Color(36, 190, 125);
    private static final Color DOWN = new Color(229, 77, 85);
    private static final Color STALE = new Color(247, 177, 61);
    private static final Color ACCENT = new Color(76, 190, 213);
    private final CryptoBoard board;
    private final CryptoPriceService.Quote quote;
    private final List<CryptoPriceService.PricePoint> history;
    private final Map<String, CryptoPriceService.Quote> quotes;
    private final Set<String> stalePairs;
    private final Map<String, String> labels;
    private final boolean stale;
    private final Instant now;
    private final int size, margin, fontSize;
    private final Color background, panel, grid, text, muted;
    private final Font small, title, priceFont;

    ChartPainter(CryptoBoard board, CryptoPriceService.Quote quote,
            List<CryptoPriceService.PricePoint> history, Map<String, String> labels, boolean stale, Instant now) {
        this(board, quote, history, quote == null ? Map.of() : Map.of(board.quoteKey(), quote),
                stale ? Set.of(board.quoteKey()) : Set.of(), labels, stale, now);
    }

    ChartPainter(CryptoBoard board, Map<String, CryptoPriceService.Quote> quotes,
            Set<String> stalePairs, Map<String, String> labels, Instant now) {
        this(board, null, List.of(), quotes, stalePairs, labels, false, now);
    }

    private ChartPainter(CryptoBoard board, CryptoPriceService.Quote quote,
            List<CryptoPriceService.PricePoint> history, Map<String, CryptoPriceService.Quote> quotes,
            Set<String> stalePairs, Map<String, String> labels, boolean stale, Instant now) {
        this.board = board;
        this.quote = quote;
        this.history = List.copyOf(history);
        this.quotes = Map.copyOf(quotes);
        this.stalePairs = Set.copyOf(stalePairs);
        this.labels = Map.copyOf(labels);
        this.stale = stale;
        this.now = now;
        size = 128 * board.settings().size();
        margin = 4 + (board.settings().size() - 1) * 3;
        fontSize = 8 + (board.settings().size() - 1) * 3;
        small = new Font(Font.MONOSPACED, Font.PLAIN, fontSize);
        title = new Font(Font.MONOSPACED, Font.BOLD, fontSize + 1);
        priceFont = new Font(Font.MONOSPACED, Font.BOLD, 12 + (board.settings().size() - 1) * 6);
        boolean light = board.settings().theme().equals("light");
        boolean ocean = board.settings().theme().equals("ocean");
        background = light ? new Color(244, 247, 251) : ocean ? new Color(7, 29, 43) : new Color(14, 20, 31);
        panel = light ? Color.WHITE : ocean ? new Color(10, 37, 53) : new Color(18, 26, 38);
        grid = light ? new Color(210, 218, 230) : new Color(40, 51, 67);
        text = light ? new Color(24, 36, 52) : new Color(224, 231, 240);
        muted = light ? new Color(66, 82, 102) : new Color(153, 169, 188);
    }

    BufferedImage drawFrame() {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setColor(background);
            g.fillRect(0, 0, size, size);
            int top = margin;
            if (board.terminal()) {
                g.setColor(labels.getOrDefault("terminalReady", "true").equals("true") ? ACCENT : STALE);
                g.fillRect(0, 0, size, fontSize + margin * 2);
                g.setColor(background);
                g.setFont(title);
                fit(g, labels.getOrDefault("terminalReady", "true").equals("true")
                        ? local("TRADING TERMINAL", "HANDELSTERMINAL") : local("TERMINAL · OFFLINE", "TERMINAL · OFFLINE"),
                        margin, top + fontSize, size - margin * 2);
                top += fontSize + margin * 2;
            }
            if (board.market()) market(g, top);
            else chart(g, top);
            if (board.terminal()) {
                g.setColor(grid);
                g.drawLine(margin, size - fontSize - margin - 3, size - margin, size - fontSize - margin - 3);
                g.setColor(ACCENT);
                g.setFont(small);
                fit(g, local("Right-click: exchange", "Rechtsklick: Börse"), margin, size - margin, size - margin * 2);
            }
        } finally { g.dispose(); }
        return image;
    }

    private void market(Graphics2D g, int top) {
        g.setFont(title);
        g.setColor(text);
        String heading = board.settings().name().isBlank()
                ? local("REFERENCE", "REFERENZ") + " / " + board.currency() : board.settings().name();
        fit(g, heading, margin, top + fontSize + 1, size - margin * 2);
        int header = top + (fontSize + margin) * 2 + 1;
        int bottom = size - margin - (board.terminal() ? fontSize + margin + 5 : fontSize + margin);
        int symbolWidth = size < 256 ? 26 : (int) (size * .24);
        int changeWidth = size < 256 ? 37 : (int) (size * .24);
        int priceRight = size - margin - changeWidth - margin;
        int changeRight = size - margin;
        g.setFont(small);
        g.setColor(muted);
        fit(g, "COIN", margin, header, symbolWidth);
        right(g, local("PRICE", "PREIS"), priceRight, header);
        right(g, "24H %", changeRight, header);
        g.setColor(grid);
        g.drawLine(margin, header + 3, size - margin, header + 3);
        int rowHeight = (bottom - header - 5) / board.quotePairs().size();
        Font rowFont = small.deriveFont((float) Math.max(6, Math.min(fontSize, rowHeight - 2)));
        for (int i = 0; i < board.quotePairs().size(); i++) {
            QuotePair pair = board.quotePairs().get(i);
            var value = quotes.get(pair.key());
            int baseline = header + 5 + i * rowHeight + (rowHeight + rowFont.getSize()) / 2 - 1;
            if (i % 2 == 0) {
                g.setColor(panel);
                g.fillRect(margin, header + 5 + i * rowHeight, size - margin * 2, rowHeight);
            }
            g.setFont(rowFont);
            g.setColor(text);
            fit(g, pair.symbol(), margin + 1, baseline, symbolWidth - 2);
            String amount = value == null ? "—" : fittedPrice(g, value.price(), priceRight - margin - symbolWidth - 3);
            g.setColor(value == null ? muted : stalePairs.contains(pair.key()) ? STALE : text);
            rightFit(g, amount, priceRight, baseline, priceRight - margin - symbolWidth - 3);
            String change = value == null ? "—" : stalePairs.contains(pair.key()) ? local("STALE", "ALT")
                    : value.change24h() == null ? "—" : percent(value.change24h());
            g.setColor(value == null ? muted : stalePairs.contains(pair.key()) ? STALE
                    : value.change24h() == null ? muted : value.change24h().signum() < 0 ? DOWN : UP);
            rightFit(g, change, changeRight, baseline, changeWidth);
        }
        if (!board.terminal()) {
            g.setColor(muted);
            g.setFont(small);
            fit(g, local("EXTERNAL REFERENCE", "EXTERNE REFERENZ"), margin, size - margin, size - margin * 2);
        }
    }

    private void chart(Graphics2D g, int top) {
        int baseline = top + fontSize + 1;
        g.setFont(title);
        g.setColor(muted);
        String status = quote == null ? label("chartLoading", local("LOADING", "LADEN"))
                : stale ? local("STALE ", "ALT ") + age(Duration.between(quote.updatedAt(), now).toMinutes())
                : quote.change24h() == null ? "" : "24H " + percent(quote.change24h()) + "%";
        g.setFont(small);
        int statusWidth = Math.min(size / 2 + 6, g.getFontMetrics().stringWidth(status));
        g.setFont(title);
        fit(g, board.label(), margin, baseline, Math.max(15, size - margin * 3 - statusWidth));
        g.setFont(small);
        g.setColor(quote == null ? muted : stale ? STALE : quote.change24h() != null && quote.change24h().signum() < 0 ? DOWN : UP);
        rightFit(g, status, size - margin, baseline, statusWidth);
        int priceBaseline = baseline + priceFont.getSize() + margin;
        g.setFont(priceFont);
        g.setColor(text);
        if (quote != null && !board.settings().style().equals("price"))
            fit(g, fittedPrice(g, quote.price(), size - margin * 2), margin, priceBaseline, size - margin * 2);
        int footerSpace = board.terminal() ? (fontSize + margin) * 2 + 7 : fontSize + margin + 3;
        int bottom = size - footerSpace;
        List<CryptoPriceService.PricePoint> points = history.stream()
                .filter(point -> !point.observedAt().isBefore(now.minus(Duration.ofHours(board.settings().hours())))
                        && !point.observedAt().isAfter(now)).toList();
        if (board.settings().style().equals("price")) {
            g.setColor(panel);
            g.fillRect(margin, priceBaseline, size - margin * 2, Math.max(12, bottom - priceBaseline));
            g.setColor(grid);
            g.drawRect(margin, priceBaseline, size - margin * 2, Math.max(12, bottom - priceBaseline));
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, size / 7));
            g.setColor(text);
            String amount = quote == null ? label("chartLoading", "LOADING") : fittedPrice(g, quote.price(), size - margin * 2);
            fit(g, amount, margin, Math.max(priceBaseline + 12, (priceBaseline + bottom) / 2 + fontSize), size - margin * 2);
        } else {
            int left = size == 128 ? 39 : size == 256 ? 58 : 77;
            int plotTop = priceBaseline + margin + 5;
            int plotBottom = bottom - fontSize - margin;
            plot(g, points, left, plotTop, size - margin, Math.max(plotTop + 8, plotBottom));
        }
        g.setFont(small);
        g.setColor(muted);
        String footer;
        if (board.settings().showRange() && !points.isEmpty()) {
            BigDecimal low = points.stream().map(CryptoPriceService.PricePoint::price).min(BigDecimal::compareTo).orElseThrow();
            BigDecimal high = points.stream().map(CryptoPriceService.PricePoint::price).max(BigDecimal::compareTo).orElseThrow();
            footer = "L " + compact(low) + " H " + compact(high);
        } else if (points.size() < 2) footer = label("chartCollecting", local("COLLECTING", "SAMMELN"));
        else footer = age(Duration.between(points.getFirst().observedAt(), now).toMinutes())
                    + "/" + age(board.settings().hours() * 60L) + " · " + points.size();
        fit(g, footer, margin, size - margin - (board.terminal() ? fontSize + margin + 5 : 0), size - margin * 2);
    }

    private void plot(Graphics2D g, List<CryptoPriceService.PricePoint> points, int left, int top, int right, int bottom) {
        g.setColor(panel);
        g.fillRect(left, top, right - left, bottom - top);
        g.setFont(small);
        BigDecimal low = points.stream().map(CryptoPriceService.PricePoint::price).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal high = points.stream().map(CryptoPriceService.PricePoint::price).max(BigDecimal::compareTo).orElse(BigDecimal.ONE);
        if (low.compareTo(high) == 0) {
            BigDecimal padding = high.abs().multiply(new BigDecimal(".01"));
            if (padding.signum() == 0) padding = BigDecimal.ONE;
            low = low.subtract(padding);
            high = high.add(padding);
        }
        BigDecimal range = high.subtract(low);
        int lines = size == 128 ? 3 : 5;
        for (int i = 0; i < lines; i++) {
            int y = top + (bottom - top) * i / (lines - 1);
            g.setColor(grid);
            g.drawLine(left, y, right, y);
            g.setColor(muted);
            BigDecimal value = high.subtract(range.multiply(BigDecimal.valueOf(i)).divide(BigDecimal.valueOf(lines - 1), MathContext.DECIMAL64));
            rightFit(g, compact(value), left - 3, y + fontSize / 2 - 1, left - margin - 3);
        }
        Instant start = now.minus(Duration.ofHours(board.settings().hours()));
        if (!points.isEmpty() && points.getFirst().observedAt().isAfter(start)) start = points.getFirst().observedAt();
        if (start.isAfter(now.minusSeconds(60))) start = now.minusSeconds(60);
        long interval = Duration.between(start, now).toMillis();
        g.setColor(muted);
        String startLabel = "-" + age(Duration.between(start, now).toMinutes());
        fit(g, startLabel, left, bottom + fontSize + 2, right - left);
        right(g, label("chartNow", local("NOW", "JETZT")), right, bottom + fontSize + 2);
        if (points.isEmpty()) return;
        Path2D line = new Path2D.Double();
        double lastX = left, lastY = bottom;
        for (int i = 0; i < points.size(); i++) {
            var point = points.get(i);
            double x = left + (right - left) * Math.clamp(Duration.between(start, point.observedAt()).toMillis() / (double) interval, 0, 1);
            double offset = point.price().subtract(low).divide(range, MathContext.DECIMAL64).doubleValue();
            double y = bottom - (bottom - top) * Math.clamp(offset, 0, 1);
            if (i == 0) line.moveTo(x, y); else line.lineTo(x, y);
            lastX = x; lastY = y;
        }
        Color color = points.getLast().price().compareTo(points.getFirst().price()) >= 0 ? UP : DOWN;
        Path2D area = (Path2D) line.clone();
        area.lineTo(lastX, bottom);
        area.lineTo(left, bottom);
        area.closePath();
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 35));
        g.fill(area);
        g.setColor(color);
        g.setStroke(new BasicStroke(size == 128 ? 1.2f : 2f));
        g.draw(line);
        g.fillOval((int) lastX - 2, (int) lastY - 2, 4, 4);
    }

    private String fittedPrice(Graphics2D g, BigDecimal value, int width) {
        String full = CryptoFormat.price(value, labels.getOrDefault("language", "en").equals("de") ? Locale.GERMANY : Locale.US);
        return g.getFontMetrics().stringWidth(full) <= width ? full : compact(value);
    }

    private String compact(BigDecimal value) {
        BigDecimal absolute = value.abs();
        String[] suffixes = {"T", "B", "M", "K"};
        int[] powers = {12, 9, 6, 3};
        for (int i = 0; i < powers.length; i++) if (absolute.compareTo(BigDecimal.TEN.pow(powers[i])) >= 0)
            return new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.US))
                    .format(value.movePointLeft(powers[i])) + suffixes[i];
        if (absolute.signum() != 0 && absolute.compareTo(new BigDecimal(".01")) < 0)
            return new DecimalFormat("0.#E0", DecimalFormatSymbols.getInstance(Locale.US)).format(value).replace("E", "e");
        return new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.US)).format(value);
    }

    private String percent(BigDecimal value) {
        return (value.signum() > 0 ? "+" : "") + new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.US)).format(value);
    }

    private void fit(Graphics2D g, String value, int x, int y, int width) {
        g.drawString(shorten(g, value, width), x, y);
    }

    private String shorten(Graphics2D g, String value, int width) {
        if (width <= 0) return "";
        if (g.getFontMetrics().stringWidth(value) <= width) return value;
        while (!value.isEmpty() && g.getFontMetrics().stringWidth(value + "…") > width)
            value = value.substring(0, value.length() - 1);
        return value.isEmpty() ? "" : value + "…";
    }

    private void right(Graphics2D g, String value, int x, int y) {
        g.drawString(value, x - g.getFontMetrics().stringWidth(value), y);
    }

    private void rightFit(Graphics2D g, String value, int x, int y, int width) {
        right(g, shorten(g, value, width), x, y);
    }

    private String local(String english, String german) { return labels.getOrDefault("language", "en").equals("de") ? german : english; }
    private String label(String key, String fallback) { return labels.getOrDefault(key, fallback); }
    private String age(long minutes) {
        minutes = Math.max(0, minutes);
        if (minutes < 60) return minutes + "m";
        if (minutes < 1440) return minutes / 60 + "h" + (minutes % 60 == 0 ? "" : minutes % 60 + "m");
        return minutes / 1440 + "d" + (minutes % 1440 == 0 ? "" : minutes % 1440 / 60 + "h");
    }
}
