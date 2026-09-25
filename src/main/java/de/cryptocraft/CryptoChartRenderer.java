package de.cryptocraft;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

public final class CryptoChartRenderer extends MapRenderer {
    private static final int SIZE = 128;
    private static final int PLOT_LEFT = 44;
    private static final int PLOT_RIGHT = 123;
    private static final int PLOT_TOP = 42;
    private static final int PLOT_BOTTOM = 96;
    private static final Color BACKGROUND = new Color(14, 20, 31);
    private static final Color GRID = new Color(40, 51, 67);
    private static final Color UP = new Color(36, 190, 125);
    private static final Color DOWN = new Color(229, 77, 85);
    private static final Color NEUTRAL = new Color(87, 159, 220);
    private static final Color TEXT = new Color(224, 231, 240);
    private static final Color MUTED_TEXT = new Color(153, 169, 188);
    private static final Font HEADER_FONT = new Font(Font.MONOSPACED, Font.BOLD, 9);
    private static final Font PRICE_FONT = new Font(Font.MONOSPACED, Font.BOLD, 10);
    private static final Font LABEL_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 8);

    private final CryptoCraftPlugin plugin;
    private final CryptoBoard board;
    private final CryptoPriceService prices;
    private volatile BufferedImage frame;

    public CryptoChartRenderer(CryptoCraftPlugin plugin, CryptoBoard board, CryptoPriceService prices) {
        this.plugin = plugin;
        this.board = board;
        this.prices = prices;
        update();
    }

    public String boardId() {
        return board.id();
    }

    public void update() {
        frame = drawFrame();
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        BufferedImage currentFrame = frame;
        if (currentFrame != null) {
            canvas.drawImage(0, 0, currentFrame);
        }
    }

    private BufferedImage drawFrame() {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            graphics.setColor(BACKGROUND);
            graphics.fillRect(0, 0, SIZE, SIZE);
            graphics.setColor(GRID);
            graphics.drawRect(1, 1, SIZE - 3, SIZE - 3);

            List<CryptoPriceService.PricePoint> points = getVisibleHistory();
            CryptoPriceService.Quote quote = prices.getQuote(board.coinId(), board.currency());
            int historyHours = getChartHistoryHours();
            PriceRange scale = calculateScale(points);

            drawHeader(graphics, quote);
            drawGrid(graphics, scale, historyHours);
            if (points.size() >= 2 && scale != null) {
                drawPriceLine(graphics, points, scale, historyHours);
            } else if (points.size() == 1 && scale != null) {
                drawLatestPoint(graphics, points.get(0), scale, historyHours);
            }
            drawFooter(graphics, points.size());
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private void drawHeader(Graphics2D graphics, CryptoPriceService.Quote quote) {
        graphics.setFont(HEADER_FONT);
        graphics.setColor(MUTED_TEXT);
        graphics.drawString(board.symbol() + "/" + board.currency(), 4, 10);

        graphics.setFont(PRICE_FONT);
        if (quote == null) {
            graphics.setColor(TEXT);
            graphics.drawString("PRICE LOADING", 4, 22);
        } else {
            graphics.setColor(TEXT);
            graphics.drawString(formatPrice(quote.price()) + " " + board.currency(), 4, 22);
            if (quote.change24h() != null) {
                String sign = quote.change24h().signum() > 0 ? "+" : "";
                String change = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.US))
                        .format(quote.change24h());
                graphics.setColor(quote.change24h().signum() >= 0 ? UP : DOWN);
                graphics.setFont(LABEL_FONT);
                graphics.drawString("24H " + sign + change + "%", 4, 33);
            }
        }

        graphics.setColor(GRID);
        graphics.drawLine(4, 36, PLOT_RIGHT, 36);
    }

    private void drawGrid(Graphics2D graphics, PriceRange scale, int historyHours) {
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
        drawTimeLabels(graphics, metrics, historyHours, middleX);
    }

    private void drawTimeLabels(Graphics2D graphics, FontMetrics metrics, int historyHours, int middleX) {
        graphics.setFont(LABEL_FONT);
        graphics.setColor(MUTED_TEXT);
        String startLabel = "-" + historyHours + "h";
        String middleLabel = formatLookbackMinutes(historyHours * 30);
        String endLabel = "NOW";
        graphics.drawString(startLabel, PLOT_LEFT, 109);
        graphics.drawString(middleLabel, middleX - metrics.stringWidth(middleLabel) / 2, 109);
        graphics.drawString(endLabel, PLOT_RIGHT - metrics.stringWidth(endLabel), 109);
    }

    private void drawPriceLine(Graphics2D graphics, List<CryptoPriceService.PricePoint> points,
                               PriceRange scale, int historyHours) {
        BigDecimal firstPrice = points.get(0).price();
        BigDecimal lastPrice = points.get(points.size() - 1).price();
        int direction = lastPrice.compareTo(firstPrice);
        Color lineColor = direction > 0 ? UP : direction < 0 ? DOWN : NEUTRAL;
        long endMillis = Instant.now().toEpochMilli();
        long startMillis = endMillis - Duration.ofHours(historyHours).toMillis();
        graphics.setColor(lineColor);
        graphics.setStroke(new BasicStroke(2f));

        int lastX = xForTime(points.get(0).observedAt(), startMillis, endMillis);
        int lastY = yForPrice(firstPrice, scale);
        for (int index = 1; index < points.size(); index++) {
            CryptoPriceService.PricePoint point = points.get(index);
            int x = xForTime(point.observedAt(), startMillis, endMillis);
            int y = yForPrice(point.price(), scale);
            graphics.drawLine(lastX, lastY, x, y);
            lastX = x;
            lastY = y;
        }
        graphics.fillOval(lastX - 2, lastY - 2, 5, 5);
    }

    private void drawLatestPoint(Graphics2D graphics, CryptoPriceService.PricePoint point,
                                 PriceRange scale, int historyHours) {
        graphics.setColor(NEUTRAL);
        long endMillis = Instant.now().toEpochMilli();
        long startMillis = endMillis - Duration.ofHours(historyHours).toMillis();
        int x = xForTime(point.observedAt(), startMillis, endMillis);
        int y = yForPrice(point.price(), scale);
        graphics.fillOval(x - 2, y - 2, 5, 5);
    }

    private void drawFooter(Graphics2D graphics, int pointCount) {
        graphics.setFont(LABEL_FONT);
        graphics.setColor(MUTED_TEXT);
        String footer = pointCount < 2
                ? plugin.messages().get("chartCollecting")
                : "n=" + pointCount;
        graphics.drawString(footer, 4, 122);
    }

    private PriceRange calculateScale(List<CryptoPriceService.PricePoint> points) {
        if (points.isEmpty()) {
            return null;
        }
        double minimum = points.stream().mapToDouble(point -> point.price().doubleValue()).min().orElse(0.0);
        double maximum = points.stream().mapToDouble(point -> point.price().doubleValue()).max().orElse(0.0);
        if (minimum == maximum) {
            double padding = Math.max(Math.abs(maximum) * 0.01, 0.01);
            minimum -= padding;
            maximum += padding;
        }
        return new PriceRange(minimum, maximum);
    }

    private int xForTime(Instant observedAt, long startMillis, long endMillis) {
        long windowMillis = Math.max(1L, endMillis - startMillis);
        double offset = (observedAt.toEpochMilli() - startMillis) / (double) windowMillis;
        double boundedOffset = Math.max(0.0, Math.min(1.0, offset));
        return PLOT_LEFT + (int) Math.round(boundedOffset * (PLOT_RIGHT - PLOT_LEFT));
    }

    private int yForPrice(BigDecimal price, PriceRange scale) {
        double range = Math.max(0.0000000001, scale.maximum() - scale.minimum());
        double offset = (price.doubleValue() - scale.minimum()) / range;
        double boundedOffset = Math.max(0.0, Math.min(1.0, offset));
        return PLOT_BOTTOM - (int) Math.round(boundedOffset * (PLOT_BOTTOM - PLOT_TOP));
    }

    private List<CryptoPriceService.PricePoint> getVisibleHistory() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(getChartHistoryHours()));
        return prices.getHistory(board.coinId(), board.currency()).stream()
                .filter(point -> !point.observedAt().isBefore(cutoff))
                .toList();
    }

    private int getChartHistoryHours() {
        return Math.max(1, Math.min(168,
                plugin.getConfig().getInt("boards.chart-history-hours", 24)));
    }

    private String formatLookbackMinutes(int minutes) {
        if (minutes < 60) {
            return "-" + minutes + "m";
        }
        int hours = minutes / 60;
        return minutes % 60 == 0 ? "-" + hours + "h" : "-" + hours + ".5h";
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
        int decimalPlaces = price.abs().compareTo(BigDecimal.ONE) >= 0
                ? 2
                : Math.max(2, Math.min(8, price.stripTrailingZeros().scale()));
        NumberFormat number = NumberFormat.getNumberInstance(Locale.US);
        number.setMinimumFractionDigits(Math.min(2, decimalPlaces));
        number.setMaximumFractionDigits(decimalPlaces);
        return number.format(price);
    }

    private record PriceRange(double minimum, double maximum) {
    }
}
