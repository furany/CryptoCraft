package de.cryptocraft;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TerminalModelTest {
    static CryptoBoard board(BlockFace facing, int size) {
        return new CryptoBoard("terminal", UUID.randomUUID(), "Owner", UUID.randomUUID(), "world",
                15, 64, 15, "BTC", "bitcoin", "EUR", facing,
                new BoardSettings("", 2.0, 24, "dark", "chart", size, false)).withTerminal(true);
    }

    @Test
    void footprintContainsEveryScreenCornerAndComputerPartInAllDirections() {
        for (BlockFace facing : List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST))
            for (int size = 1; size <= 3; size++) {
                CryptoBoard board = board(facing, size);
                var parts = TerminalModel.parts(board);
                assertEquals(30, parts.size());
                assertEquals(30, parts.stream().map(TerminalModel.Part::id).distinct().count());
                var cells = CryptoBoardService.footprintCells(board, parts);
                assertTrue(cells.contains(new CryptoBoardService.Cell(15, 64, 15)));
                for (var part : parts) {
                    assertTrue(part.width() > 0 && part.height() > 0 && part.depth() > 0, part.id());
                    var at = part.location(board, null);
                    for (double x : new double[] { at.getX(), at.getX() + part.width() - 1e-7 })
                        for (double y : new double[] { at.getY(), at.getY() + part.height() - 1e-7 })
                            for (double z : new double[] { at.getZ(), at.getZ() + part.depth() - 1e-7 })
                                assertTrue(cells.contains(new CryptoBoardService.Cell((int) Math.floor(x),
                                        (int) Math.floor(y), (int) Math.floor(z))), facing + "/" + size + "/" + part.id());
                }
                for (int tile = 0; tile < size * size; tile++) {
                    var at = CryptoBoardService.tileLocation(board, null, tile);
                    assertTrue(cells.contains(new CryptoBoardService.Cell(at.getBlockX(), at.getBlockY(), at.getBlockZ())));
                }
                if (size > 1) assertTrue(cells.stream().anyMatch(cell -> cell.x() >= 16 || cell.z() >= 16),
                        "Wide terminals must protect the neighboring chunk too");
            }
    }

    @Test
    void keyboardFacesPlayerAndFrameEnclosesMapWall() {
        for (BlockFace facing : List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST))
            for (int size = 1; size <= 3; size++) {
                var board = board(facing, size);
                var keyboard = TerminalModel.parts(board).stream().filter(part -> part.id().equals("keyboard")).findFirst().orElseThrow();
                double centerX = keyboard.x() + keyboard.width() / 2;
                double centerZ = keyboard.z() + keyboard.depth() / 2;
                var screen = CryptoBoardService.mapSurfaceLocation(board, null, 0);
                double screenPlane = (screen.getX() - board.x() - .5) * facing.getModX()
                        + (screen.getZ() - board.z() - .5) * facing.getModZ();
                assertTrue(centerX * facing.getModX() + centerZ * facing.getModZ() > screenPlane);
                var top = TerminalModel.parts(board).stream().filter(part -> part.id().equals("frame-top")).findFirst().orElseThrow();
                assertEquals(Math.floor(board.settings().height()) + size, top.y(), 1e-7);
            }
    }

    @Test
    void housingMatchesActualSnappedVanillaMapEdgesAndPlaneForEveryFacingAndSize() {
        for (BlockFace facing : List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST))
            for (int size = 1; size <= 3; size++) {
                CryptoBoard initial = board(facing, size);
                CryptoBoard board = initial.edited(initial.symbol(), initial.coinId(), initial.currency(), facing,
                        initial.settings().with("height", "2.35"));
                int rightX = facing.getModZ(), rightZ = -facing.getModX();
                double left = Double.POSITIVE_INFINITY, right = Double.NEGATIVE_INFINITY;
                double bottom = Double.POSITIVE_INFINITY, top = Double.NEGATIVE_INFINITY;
                Double mapPlane = null;
                for (int tile = 0; tile < size * size; tile++) {
                    var entity = CryptoBoardService.tileLocation(board, null, tile);
                    // Vanilla BlockAttachedEntity.setPos -> ItemFrame.createBoundingBox is idempotent.
                    assertEquals(entity.getX(), Math.floor(entity.getX()) + .5 - facing.getModX() * 15.0 / 32, 1e-9);
                    assertEquals(entity.getY(), Math.floor(entity.getY()) + .5, 1e-9);
                    assertEquals(entity.getZ(), Math.floor(entity.getZ()) + .5 - facing.getModZ() * 15.0 / 32, 1e-9);
                    var surface = CryptoBoardService.mapSurfaceLocation(board, null, tile);
                    double horizontal = surface.getX() * rightX + surface.getZ() * rightZ;
                    left = Math.min(left, horizontal - .5);
                    right = Math.max(right, horizontal + .5);
                    bottom = Math.min(bottom, surface.getY() - .5);
                    top = Math.max(top, surface.getY() + .5);
                    double plane = surface.getX() * facing.getModX() + surface.getZ() * facing.getModZ();
                    if (mapPlane != null) assertEquals(mapPlane, plane, 1e-9);
                    mapPlane = plane;
                }
                var parts = TerminalModel.parts(board);
                var frameLeft = parts.stream().filter(part -> part.id().equals("frame-left")).findFirst().orElseThrow();
                var frameRight = parts.stream().filter(part -> part.id().equals("frame-right")).findFirst().orElseThrow();
                var frameTop = parts.stream().filter(part -> part.id().equals("frame-top")).findFirst().orElseThrow();
                var frameBottom = parts.stream().filter(part -> part.id().equals("frame-bottom")).findFirst().orElseThrow();
                assertEquals(left, projection(frameLeft, board, rightX, rightZ)[1], 1e-9);
                assertEquals(right, projection(frameRight, board, rightX, rightZ)[0], 1e-9);
                assertEquals(top, board.y() + frameTop.y(), 1e-9);
                assertEquals(bottom, board.y() + frameBottom.y() + frameBottom.height(), 1e-9);
                double[] frameDepth = projection(frameTop, board, facing.getModX(), facing.getModZ());
                assertEquals(mapPlane - .10, frameDepth[0], 1e-9);
                assertEquals(mapPlane + .06, frameDepth[1], 1e-9);
                var back = parts.stream().filter(part -> part.id().equals("case-back")).findFirst().orElseThrow();
                double[] backDepth = projection(back, board, facing.getModX(), facing.getModZ());
                assertEquals(mapPlane - .10, backDepth[0], 1e-9);
                assertEquals(mapPlane - .04, backDepth[1], 1e-9);
                assertTrue(backDepth[1] < mapPlane, "The case back must not obscure the visible map");
                assertEquals(left, projection(back, board, rightX, rightZ)[0], 1e-9);
                assertEquals(right, projection(back, board, rightX, rightZ)[1], 1e-9);
                assertEquals(bottom, board.y() + back.y(), 1e-9);
                assertEquals(top, board.y() + back.y() + back.height(), 1e-9);
                assertTrue(board.y() + 1.102 < bottom - .09, "Keyboard must stay below the lower bezel");
            }
    }

    @Test
    void southTwoMapWallMatchesPositionObservedOnNativeServer() {
        CryptoBoard board = new CryptoBoard("native", UUID.randomUUID(), "Owner", UUID.randomUUID(), "world",
                5, 80, 0, "BTC", "bitcoin", "EUR", BlockFace.SOUTH,
                new BoardSettings("", 2.35, 24, "dark", "chart", 2, false)).withTerminal(true);
        var lowerLeft = CryptoBoardService.tileLocation(board, null, 2);
        assertEquals(5.5, lowerLeft.getX());
        assertEquals(82.5, lowerLeft.getY());
        assertEquals(.03125, lowerLeft.getZ());
        var surface = CryptoBoardService.mapSurfaceLocation(board, null, 2);
        assertEquals(.0078125, surface.getZ());
    }

    private double[] projection(TerminalModel.Part part, CryptoBoard board, int axisX, int axisZ) {
        var at = part.location(board, null);
        double a = at.getX() * axisX + at.getZ() * axisZ;
        double b = (at.getX() + part.width()) * axisX + (at.getZ() + part.depth()) * axisZ;
        return new double[] { Math.min(a, b), Math.max(a, b) };
    }

    @Test
    void marketRejectsDuplicateIdsMixedCurrenciesAndTooManyRows() {
        var base = board(BlockFace.SOUTH, 2);
        assertThrows(IllegalArgumentException.class, () -> base.withMarketPairs(List.of()));
        assertThrows(IllegalArgumentException.class, () -> base.withMarketPairs(List.of(
                new QuotePair("BTC", "bitcoin", "EUR"), new QuotePair("XBT", "bitcoin", "EUR"))));
        assertThrows(IllegalArgumentException.class, () -> base.withMarketPairs(List.of(new QuotePair("BTC", "bitcoin", "USD"))));
        assertThrows(IllegalArgumentException.class, () -> base.withMarketPairs(java.util.stream.IntStream.range(0, 7)
                .mapToObj(index -> new QuotePair("C" + index, "coin" + index, "EUR")).toList()));
    }
}
