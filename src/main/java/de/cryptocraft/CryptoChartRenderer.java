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
        Map<String, CryptoPriceService.Quote> rowQuotes = new LinkedHashMap<>();
        Set<String> stalePairs = new HashSet<>();
        for (QuotePair pair : board.quotePairs()) {
            var value = prices.getQuote(pair.coinId(), pair.currency());
            if (value != null) rowQuotes.put(pair.key(), value);
            if (prices.isStale(value)) stalePairs.add(pair.key());
        }
        Instant now = Instant.now();
        boolean terminalReady = plugin.trading() != null && plugin.trading().available();
        String key =
                board.quotePairs().toString() + ":" + board.terminal() + ":" + terminalReady + ":" + rowQuotes + ":" + stalePairs
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
        labels.put("terminalReady", String.valueOf(terminalReady));
        ChartPainter painter = board.market()
                ? new ChartPainter(board, rowQuotes, stalePairs, labels, now)
                : new ChartPainter(
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
