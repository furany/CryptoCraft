package de.cryptocraft;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import javax.imageio.ImageIO;

class ChartPainterTest {
    @Test
    void rendersEveryThemeStyleAndWallSizeWithoutServerAccess() throws Exception {
        Instant now = Instant.parse("2026-10-05T12:00:00Z");
        var quote =
                new CryptoPriceService.Quote(
                        new BigDecimal("64500.25"), new BigDecimal("2.34"), now.minusSeconds(30));
        var points = new ArrayList<CryptoPriceService.PricePoint>();
        for (int i = 0; i < 100; i++)
            points.add(
                    new CryptoPriceService.PricePoint(
                            now.minusSeconds((100 - i) * 300L),
                            BigDecimal.valueOf(64000 + Math.sin(i / 5.0) * 800 + i * 5)));
        Map<String, String> labels =
                Map.of(
                        "language",
                        "en",
                        "chartLoading",
                        "LOADING",
                        "chartChangeLabel",
                        "24H",
                        "chartNow",
                        "NOW",
                        "chartCollecting",
                        "COLLECTING",
                        "chartCoverage",
                        "%visible% / %window% | N=%samples%");
        Files.createDirectories(Path.of("build/chart-previews"));
        for (String theme : List.of("dark", "light", "ocean"))
            for (String style : List.of("chart", "price", "compact"))
                for (int size = 1; size <= 3; size++) {
                    var board =
                            new CryptoBoard(
                                    "id",
                                    UUID.randomUUID(),
                                    "Owner",
                                    UUID.randomUUID(),
                                    "world",
                                    0,
                                    64,
                                    0,
                                    "BTC",
                                    "bitcoin",
                                    "EUR",
                                    BlockFace.SOUTH,
                                    new BoardSettings("", 1.35, 24, theme, style, size, false));
                    BufferedImage image =
                            new ChartPainter(board, quote, points, labels, false, now).drawFrame();
                    assertEquals(128 * size, image.getWidth());
                    assertEquals(128 * size, image.getHeight());
                    Set<Integer> colors = new HashSet<>();
                    for (int y = 0; y < image.getHeight(); y++)
                        for (int x = 0; x < image.getWidth(); x++) colors.add(image.getRGB(x, y));
                    assertTrue(
                            colors.size() >= 5,
                            theme + "/" + style + " must include readable content");
                    ImageIO.write(
                                image,
                                "png",
                                Path.of("build/chart-previews/" + theme + "-" + style + "-" + size + ".png")
                                        .toFile());
                }
    }

    @Test
    void sixMarketRowsRenderAndLastRowRefreshesAtEverySize() throws Exception {
        Instant now = Instant.parse("2026-11-02T12:00:00Z");
        List<QuotePair> pairs = List.of(new QuotePair("BTC", "bitcoin", "EUR"),
                new QuotePair("ETH", "ethereum", "EUR"), new QuotePair("SOL", "solana", "EUR"),
                new QuotePair("XRP", "ripple", "EUR"), new QuotePair("ADA", "cardano", "EUR"),
                new QuotePair("DOGE", "dogecoin", "EUR"));
        Map<String, CryptoPriceService.Quote> quotes = new LinkedHashMap<>();
        String[] amounts = {"64500.25", "2450.75", "145.5", "0.54", "0.34", "0.112"};
        for (int i = 0; i < pairs.size(); i++) quotes.put(pairs.get(i).key(), new CryptoPriceService.Quote(
                new BigDecimal(amounts[i]), BigDecimal.valueOf(i - 2.5), now.minusSeconds(30)));
        Files.createDirectories(Path.of("build/chart-previews"));
        for (String theme : List.of("dark", "light", "ocean"))
            for (int size = 1; size <= 3; size++)
                for (boolean terminal : List.of(false, true)) {
                    var board = TerminalModelTest.board(BlockFace.SOUTH, size);
                    board = board.edited(board.symbol(), board.coinId(), board.currency(), board.displayFacing(),
                            board.settings().with("theme", theme)).withMarketPairs(pairs).withTerminal(terminal);
                    var labels = Map.of("language", "de", "terminalReady", "true");
                    var before = new ChartPainter(board, quotes, Set.of(pairs.get(3).key()), labels, now).drawFrame();
                    var changed = new LinkedHashMap<>(quotes);
                    changed.put(pairs.getLast().key(), new CryptoPriceService.Quote(new BigDecimal("0.987"),
                            new BigDecimal("9.87"), now));
                    var after = new ChartPainter(board, changed, Set.of(pairs.get(3).key()), labels, now).drawFrame();
                    int changedPixels = 0;
                    for (int y = 0; y < before.getHeight(); y++)
                        for (int x = 0; x < before.getWidth(); x++)
                            if (before.getRGB(x, y) != after.getRGB(x, y)) {
                                changedPixels++;
                                assertTrue(y > before.getHeight() / 2, "Sixth row belongs in the bottom half");
                            }
                    assertTrue(changedPixels > 10, theme + "/" + size + "/" + terminal + " must show changing sixth-row data");
                    ImageIO.write(before, "png", Path.of("build/chart-previews/market-" + theme + "-" + size
                            + (terminal ? "-terminal" : "") + ".png").toFile());
                }
    }

    @Test
    void loadingStaleAndTinyPricesRender() {
        var board =
                new CryptoBoard(
                        "id",
                        UUID.randomUUID(),
                        "Owner",
                        UUID.randomUUID(),
                        "world",
                        0,
                        64,
                        0,
                        "TINY",
                        "tiny",
                        "EUR",
                        BlockFace.NORTH,
                        new BoardSettings("Long board name", 1.35, 1, "light", "chart", 1, true));
        Map<String, String> labels =
                Map.of(
                        "language",
                        "de",
                        "chartLoading",
                        "LADEN",
                        "chartNow",
                        "JETZT",
                        "chartCollecting",
                        "SAMMELN");
        Instant now = Instant.now();
        assertNotNull(new ChartPainter(board, null, List.of(), labels, true, now).drawFrame());
        var quote =
                new CryptoPriceService.Quote(
                        new BigDecimal("0.000000000123"), null, now.minusSeconds(10000));
        assertNotNull(
                new ChartPainter(
                                board,
                                quote,
                                List.of(new CryptoPriceService.PricePoint(now, quote.price())),
                                labels,
                                true,
                                now)
                        .drawFrame());
    }
}
