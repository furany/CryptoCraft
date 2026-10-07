package de.cryptocraft;

import org.bukkit.entity.Player;
import org.bukkit.map.*;

import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.*;

public final class CryptoChartRenderer extends MapRenderer {
    private final CryptoCraftPlugin plugin;
    private final CryptoBoard board;
    private final CryptoPriceService prices;
    private final int tile;
    private volatile BufferedImage frame;
    private volatile BufferedImage source;
    private long requestedVersion;
    private final Map<MapCanvas, BufferedImage> drawn = new WeakHashMap<>();

    public CryptoChartRenderer(
            CryptoCraftPlugin plugin, CryptoBoard board, CryptoPriceService prices, int tile) {
        this.plugin = plugin;
        this.board = board;
        this.prices = prices;
        this.tile = tile;
        update();
    }

    public String boardId() {
        return board.id();
    }

    public int tile() {
        return tile;
    }

    public synchronized void update() {
        var quote = prices.getQuote(board.coinId(), board.currency());
        Instant now = Instant.now();
        String key =
                board.quoteKey()
                        + ":"
                        + board.symbol()
                        + ":"
                        + board.settings()
                        + ":"
                        + now.getEpochSecond() / 60
                        + ":"
                        + quote
                        + ":"
                        + prices.historyRevision()
                        + ":"
                        + plugin.messages().revision();
        Map<String, String> labels = new HashMap<>();
        for (String label :
                List.of(
                        "chartLoading",
                        "chartChangeLabel",
                        "chartNow",
                        "chartCollecting",
                        "chartCoverage")) labels.put(label, plugin.messages().get(label));
        labels.put("language", plugin.messages().getLanguage());
        ChartPainter painter =
                new ChartPainter(
                        board,
                        quote,
                        prices.getHistory(board.coinId(), board.currency()),
                        labels,
                        prices.isStale(quote),
                        now);
        long version = ++requestedVersion;
        plugin.boards()
                .sharedImage(key, painter::drawFrame)
                .thenAccept(
                        image -> {
                            synchronized (this) {
                                if (version != requestedVersion || source == image) return;
                                source = image;
                                int size = board.settings().size();
                                frame =
                                        image.getSubimage(
                                                tile % size * 128, tile / size * 128, 128, 128);
                            }
                        });
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        BufferedImage image = frame;
        if (image != null && drawn.get(canvas) != image) {
            canvas.drawImage(0, 0, image);
            drawn.put(canvas, image);
        }
    }
}
