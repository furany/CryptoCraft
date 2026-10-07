package de.cryptocraft;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.UUID;

class BoardSettingsTest {
    private BoardSettings defaults() {
        return new BoardSettings("", 1.35, 24, "dark", "chart", 1, false);
    }

    @Test
    void rejectsInvalidSettingsAndPreservesUneditedValues() {
        for (String[] input :
                new String[][] {
                    {"height", "NaN"},
                    {"hours", "169"},
                    {"size", "4"},
                    {"theme", "invalid"},
                    {"range", "yes"},
                    {"name", "line\nbreak"}
                })
            assertThrows(IllegalArgumentException.class, () -> defaults().with(input[0], input[1]));
        var changed = defaults().with("hours", "168");
        assertEquals(168, changed.hours());
        assertEquals(defaults().height(), changed.height());
    }

    @Test
    void squareWallTilesAreContiguousAndFaceCorrectly() {
        for (BlockFace facing :
                new BlockFace[] {
                    BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST
                }) {
            var board =
                    new CryptoBoard(
                            "id",
                            UUID.randomUUID(),
                            "Owner",
                            UUID.randomUUID(),
                            "world",
                            15,
                            64,
                            15,
                            "BTC",
                            "bitcoin",
                            "EUR",
                            facing,
                            defaults().with("size", "3"));
            var first = CryptoBoardService.tileLocation(board, null, 0);
            var next = CryptoBoardService.tileLocation(board, null, 1);
            var below = CryptoBoardService.tileLocation(board, null, 3);
            assertEquals(1, first.toVector().distance(next.toVector()));
            assertEquals(1, first.toVector().distance(below.toVector()));
            assertEquals(first.getY() - 1, below.getY());
            assertEquals(facing.getModZ(), next.getX() - first.getX());
            assertEquals(-facing.getModX(), next.getZ() - first.getZ());
        }
    }
}
