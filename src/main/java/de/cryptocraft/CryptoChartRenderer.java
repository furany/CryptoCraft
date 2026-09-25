package de.cryptocraft;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.map.MinecraftFont;

import java.awt.BasicStroke;
import java.awt.Color;
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
    private static final Color BACKGROUND = new Color(14, 20, 31);
    private static final Color GRID = new Color(40, 51, 67);
    private static final Color UP = new Color(36, 190, 125);
    private static final Color DOWN = new Color(229, 77, 85);
    private static final Color NEUTRAL = new Color(87, 159, 220);

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

        CryptoPriceService.Quote quote = prices.getQuote(board.coinId(), board.currency());
        List<CryptoPriceService.PricePoint> history = getVisibleHistory();
        canvas.drawText(5, 5, MinecraftFont.Font, board.symbol() + "/" + board.currency());
        if (quote == null) {
            canvas.drawText(5, 17, MinecraftFont.Font, "PRICE LOADING");
        } else {
            canvas.drawText(5, 17, MinecraftFont.Font,
                    formatPrice(quote.price()) + " " + board.currency());
            if (quote.change24h() != null) {
                String sign = quote.change24h().signum() > 0 ? "+" : "";
                String change = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.US))
                        .format(quote.change24h());
                canvas.drawText(5, 28, MinecraftFont.Font, "24H " + sign + change + "%");
            }
        }

        if (history.size() < 2) {
            canvas.drawText(5, 108, MinecraftFont.Font, plugin.messages().get("chartCollecting"));
        }
    }

    private BufferedImage drawFrame() {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.setColor(BACKGROUND);
            graphics.fillRect(0, 0, SIZE, SIZE);
            graphics.setColor(GRID);
            graphics.drawRect(1, 1, SIZE - 3, SIZE - 3);

            int top = 41;
            int bottom = 108;
            for (int row = 0; row <= 4; row++) {
                int y = top + (bottom - top) * row / 4;
                graphics.drawLine(6, y, 121, y);
            }
            for (int column = 0; column <= 4; column++) {
                int x = 6 + 115 * column / 4;
                graphics.drawLine(x, top, x, bottom);
            }

            List<CryptoPriceService.PricePoint> points = getVisibleHistory();
            if (points.size() >= 2) {
                CryptoPriceService.Quote quote = prices.getQuote(board.coinId(), board.currency());
                Color lineColor = quote != null && quote.change24h() != null
                        ? (quote.change24h().signum() >= 0 ? UP : DOWN)
                        : (points.get(points.size() - 1).price().compareTo(points.get(0).price()) >= 0 ? UP : DOWN);
                drawPriceLine(graphics, points, lineColor, top, bottom);
            } else {
                graphics.setColor(NEUTRAL);
                graphics.setStroke(new BasicStroke(2f));
                graphics.drawLine(8, 77, 120, 77);
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private void drawPriceLine(Graphics2D graphics, List<CryptoPriceService.PricePoint> points,
                               Color lineColor, int top, int bottom) {
        double minimum = points.stream().mapToDouble(point -> point.price().doubleValue()).min().orElse(0.0);
        double maximum = points.stream().mapToDouble(point -> point.price().doubleValue()).max().orElse(0.0);
        double range = maximum - minimum;
        if (range == 0.0) {
            double padding = Math.max(Math.abs(maximum) * 0.01, 0.01);
            minimum -= padding;
            maximum += padding;
            range = maximum - minimum;
        }

        long firstTime = points.get(0).observedAt().toEpochMilli();
        long timeRange = Math.max(1L, points.get(points.size() - 1).observedAt().toEpochMilli() - firstTime);
        int lastX = 7;
        int lastY = bottom - 1;
        graphics.setColor(lineColor);
        graphics.setStroke(new BasicStroke(2f));
        for (int index = 0; index < points.size(); index++) {
            CryptoPriceService.PricePoint point = points.get(index);
            double timeOffset = (point.observedAt().toEpochMilli() - firstTime) / (double) timeRange;
            int x = 7 + (int) Math.round(timeOffset * 113.0);
            double priceOffset = (point.price().doubleValue() - minimum) / range;
            int y = bottom - 1 - (int) Math.round(priceOffset * (bottom - top - 2));
            if (index > 0) {
                graphics.drawLine(lastX, lastY, x, y);
            }
            lastX = x;
            lastY = y;
        }
    }

    private List<CryptoPriceService.PricePoint> getVisibleHistory() {
        int historyHours = Math.max(1, Math.min(168,
                plugin.getConfig().getInt("boards.chart-history-hours", 24)));
        Instant cutoff = Instant.now().minus(Duration.ofHours(historyHours));
        return prices.getHistory(board.coinId(), board.currency()).stream()
                .filter(point -> !point.observedAt().isBefore(cutoff))
                .toList();
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
}
