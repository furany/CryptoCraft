package de.cryptocraft;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

class CryptoPriceServiceTest {
    @TempDir Path directory;
    private AtomicYamlStore storage;
    private CryptoPriceService service;
    private HttpClient http;
    private CompletableFuture<HttpResponse<String>> response;
    private final Queue<Runnable> mainTasks = new ArrayDeque<>();
    private MockedStatic<Bukkit> bukkit;
    private CryptoCraftPlugin plugin;

    @BeforeEach
    void setup() {
        plugin = mock(CryptoCraftPlugin.class);
        var boards = mock(CryptoBoardService.class);
        http = mock(HttpClient.class);
        storage = new AtomicYamlStore(Logger.getAnonymousLogger());
        when(plugin.getConfig())
                .thenReturn(
                        YamlConfiguration.loadConfiguration(
                                new java.io.File("src/main/resources/config.yml")));
        when(plugin.storage()).thenReturn(storage);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getDescription())
                .thenReturn(
                        new PluginDescriptionFile(
                                "CryptoCraft", "1.1.0", "de.cryptocraft.CryptoCraftPlugin"));
        when(boards.getBoards()).thenReturn(List.of());
        var scheduler = mock(BukkitScheduler.class);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class)))
                .thenAnswer(
                        call -> {
                            mainTasks.add(call.getArgument(1));
                            return mock(BukkitTask.class);
                        });
        response = new CompletableFuture<>();
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        service = new CryptoPriceService(plugin, boards, http);
    }

    @AfterEach
    void cleanup() {
        service.close();
        storage.close();
        bukkit.close();
    }

    private void finish(int status, String body, Map<String, List<String>> headers) {
        HttpResponse<String> result = mock(HttpResponse.class);
        when(result.statusCode()).thenReturn(status);
        when(result.body()).thenReturn(body);
        when(result.headers()).thenReturn(HttpHeaders.of(headers, (key, value) -> true));
        response.complete(result);
        while (!mainTasks.isEmpty()) mainTasks.remove().run();
    }

    @Test
    void priceCanBeRequestedWithoutAnyBoardAndCallsAreGloballyLimited() {
        service.requestPair("bitcoin", "EUR");
        service.requestPair("ethereum", "USD");
        service.refresh();
        verify(http, times(1))
                .sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        finish(
                200,
                "{\"bitcoin\":{\"eur\":1e-7,\"last_updated_at\":"
                        + Instant.now().getEpochSecond()
                        + "}}",
                Map.of());
        assertNotNull(service.getQuote("bitcoin", "EUR"));
        assertFalse(service.isStale(service.getQuote("bitcoin", "EUR")));
        assertEquals(1, service.getHistory("bitcoin", "EUR").size());
        assertFalse(service.isInFlight());
    }

    @Test
    void providerRetryAfterOverridesOurMaximumAndBlocksNewDemand() {
        service.requestPair("bitcoin", "EUR");
        finish(429, "limited", Map.of("Retry-After", List.of("7200")));
        assertTrue(Duration.between(Instant.now(), service.nextRequestAt()).toSeconds() >= 7198);
        service.requestPair("ethereum", "USD");
        verify(http, times(1))
                .sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertEquals("HTTP 429", service.failure());
        assertNull(service.getQuote("bitcoin", "EUR"));
    }

    @Test
    void emptySuccessfulHttpBodyIsAFailureRatherThanApiRecovery() {
        service.requestPair("bitcoin", "EUR");
        finish(200, "{}", Map.of());
        assertEquals(Instant.EPOCH, service.lastSuccessAt());
        assertNotNull(service.failure());
        assertFalse(service.isInFlight());
    }

    @Test
    void reloadRejectsResultsFromEarlierGeneration() {
        service.requestPair("bitcoin", "EUR");
        service.resetRetryState();
        while (!mainTasks.isEmpty()) mainTasks.remove().run();
        assertFalse(service.isInFlight());
        assertNull(service.getQuote("bitcoin", "EUR"));
        assertNull(service.failure());
    }

    @Test
    void staleSourcesAreVisibleButNeverSampledAsFreshHistory() {
        service.requestPair("bitcoin", "EUR");
        finish(
                200,
                "{\"bitcoin\":{\"eur\":100,\"last_updated_at\":"
                        + Instant.now().minusSeconds(3600).getEpochSecond()
                        + "}}",
                Map.of());
        assertTrue(service.isStale(service.getQuote("bitcoin", "EUR")));
        assertTrue(service.getHistory("bitcoin", "EUR").isEmpty());
    }
}
