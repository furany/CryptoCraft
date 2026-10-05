package de.cryptocraft;

import java.util.Locale;

public record BoardSettings(
        String name,
        double height,
        int hours,
        String theme,
        String style,
        int size,
        boolean showRange) {
    public BoardSettings {
        if (name == null || name.length() > 32 || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("name: max 32 printable characters");
        if (!Double.isFinite(height) || height < 0.5 || height > 8)
            throw new IllegalArgumentException("height: 0.5..8");
        if (hours < 1 || hours > 168) throw new IllegalArgumentException("hours: 1..168");
        if (!java.util.Set.of("dark", "light", "ocean").contains(theme))
            throw new IllegalArgumentException("theme: dark|light|ocean");
        if (!java.util.Set.of("chart", "price", "compact").contains(style))
            throw new IllegalArgumentException("style: chart|price|compact");
        if (size < 1 || size > 3) throw new IllegalArgumentException("size: 1..3");
    }

    public BoardSettings with(String key, String value) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "name" -> new BoardSettings(value, height, hours, theme, style, size, showRange);
            case "height" ->
                    new BoardSettings(
                            name, Double.parseDouble(value), hours, theme, style, size, showRange);
            case "hours" ->
                    new BoardSettings(
                            name, height, Integer.parseInt(value), theme, style, size, showRange);
            case "theme" ->
                    new BoardSettings(
                            name,
                            height,
                            hours,
                            value.toLowerCase(Locale.ROOT),
                            style,
                            size,
                            showRange);
            case "style" ->
                    new BoardSettings(
                            name,
                            height,
                            hours,
                            theme,
                            value.toLowerCase(Locale.ROOT),
                            size,
                            showRange);
            case "size" ->
                    new BoardSettings(
                            name, height, hours, theme, style, Integer.parseInt(value), showRange);
            case "range" -> {
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
                    throw new IllegalArgumentException("range: true|false");
                yield new BoardSettings(
                        name, height, hours, theme, style, size, Boolean.parseBoolean(value));
            }
            default ->
                    throw new IllegalArgumentException(
                            "setting:"
                                + " coin|currency|facing|name|height|hours|theme|style|size|range");
        };
    }
}
