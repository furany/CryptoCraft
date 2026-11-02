package de.cryptocraft;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.*;

/** Pure, axis-aligned geometry for a vanilla computer. No blocks are placed or replaced. */
final class TerminalModel {
    static final double MINIMUM_HEIGHT = 2.0;

    record Part(String id, Material material, double x, double y, double z,
                double width, double height, double depth) {
        Location location(CryptoBoard board, World world) {
            return new Location(world, board.x() + .5 + x, board.y() + y, board.z() + .5 + z);
        }
    }

    static List<Part> parts(CryptoBoard board) {
        double size = board.settings().size();
        double bottom = Math.floor(board.settings().height());
        double half = size / 2;
        List<Part> parts = new ArrayList<>();
        parts.add(part(board, "case-back", Material.BLACK_CONCRETE, -half, bottom, -.10, size, size, .06));
        parts.add(part(board, "frame-left", Material.BLACK_CONCRETE, -half - .09, bottom - .09, -.10, .09, size + .18, .16));
        parts.add(part(board, "frame-right", Material.BLACK_CONCRETE, half, bottom - .09, -.10, .09, size + .18, .16));
        parts.add(part(board, "frame-top", Material.BLACK_CONCRETE, -half, bottom + size, -.10, size, .09, .16));
        parts.add(part(board, "frame-bottom", Material.BLACK_CONCRETE, -half, bottom - .09, -.10, size, .09, .16));
        parts.add(part(board, "stand", Material.POLISHED_DEEPSLATE, -.10, 1.06, -.14, .20, Math.max(.16, bottom - 1.06), .20));
        parts.add(part(board, "base", Material.BLACK_CONCRETE, -.34, 1.01, -.26, .68, .07, .44));
        parts.add(part(board, "keyboard", Material.GRAY_CONCRETE, -.43, 1.025, .20, .86, .065, .32));
        for (int row = 0; row < 3; row++)
            for (int key = 0; key < 7; key++)
                parts.add(part(board, "key-" + row + "-" + key, Material.SMOOTH_STONE,
                        -.385 + key * .11, 1.09, .225 + row * .085, .085, .012, .055));
        parts.add(part(board, "indicator", Material.LIME_CONCRETE, half - .13, bottom - .065, .061, .04, .035, .015));
        return List.copyOf(parts);
    }

    private static Part part(CryptoBoard board, String id, Material material,
            double u, double y, double w, double width, double height, double depth) {
        int rx = board.displayFacing().getModZ();
        int rz = -board.displayFacing().getModX();
        int fx = board.displayFacing().getModX();
        int fz = board.displayFacing().getModZ();
        double x1 = u * rx + w * fx;
        double z1 = u * rz + w * fz;
        double x2 = (u + width) * rx + (w + depth) * fx;
        double z2 = (u + width) * rz + (w + depth) * fz;
        // Item frames snap to block centers. Even-sized walls shift by half a block, and
        // their rendered surface is near the block face rather than the supplied spawn plane.
        var first = CryptoBoardService.mapSurfaceLocation(board, null, 0);
        var last = CryptoBoardService.mapSurfaceLocation(board, null, board.settings().size() - 1);
        double originX = (first.getX() + last.getX()) / 2 - board.x() - .5;
        double originZ = (first.getZ() + last.getZ()) / 2 - board.z() - .5;
        return new Part(id, material, Math.min(x1, x2) + originX, y, Math.min(z1, z2) + originZ,
                Math.abs(x2 - x1), height, Math.abs(z2 - z1));
    }
}
