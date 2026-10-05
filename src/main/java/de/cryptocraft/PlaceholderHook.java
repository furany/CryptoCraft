package de.cryptocraft;

/** Keeps optional PlaceholderAPI types outside the main plugin's class-loading path. */
final class PlaceholderHook {
    static Runnable register(CryptoCraftPlugin plugin) {
        var expansion = new CryptoPlaceholders(plugin);
        return expansion.register() ? expansion::unregister : null;
    }
}
