package de.cryptocraft;

import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.Set;

final class SafeTeleport {
    private static final Set<Material> DANGER =
            Set.of(
                    Material.LAVA,
                    Material.FIRE,
                    Material.SOUL_FIRE,
                    Material.MAGMA_BLOCK,
                    Material.CACTUS,
                    Material.CAMPFIRE,
                    Material.SOUL_CAMPFIRE,
                    Material.POWDER_SNOW,
                    Material.SWEET_BERRY_BUSH,
                    Material.WITHER_ROSE,
                    Material.POINTED_DRIPSTONE);

    static boolean safe(Block floor, Block feet, Block head) {
        return floor.getType().isSolid()
                && !floor.isPassable()
                && feet.isPassable()
                && head.isPassable()
                && !floor.isLiquid()
                && !feet.isLiquid()
                && !head.isLiquid()
                && !DANGER.contains(floor.getType())
                && !DANGER.contains(feet.getType())
                && !DANGER.contains(head.getType())
                && Math.abs(floor.getBoundingBox().getMaxY() - feet.getY()) < 0.001;
    }
}
