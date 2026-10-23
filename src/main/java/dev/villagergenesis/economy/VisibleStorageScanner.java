package dev.villagergenesis.economy;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Reads only real, player-visible containers near a settlement. It never creates, removes, or
 * reserves items. No persistent virtual inventory exists: if a player empties a chest, the next
 * scan reports that it is empty.
 *
 * This deliberately scans only ordinary chests, trapped chests, and barrels. Ender chests,
 * villager inventories, hoppers, and arbitrary modded inventories are not treated as public
 * settlement storage by this first implementation.
 */
public final class VisibleStorageScanner {
    public static final int DEFAULT_RADIUS = 12;

    public enum Resource {
        BREAD, CARROTS, POTATOES, BEETROOT, WHEAT, SEEDS, WOOD, STONE, OTHER
    }

    public record StorageContainer(int x, int y, int z, String kind, int occupiedSlots, int slots) {}

    public record Snapshot(
            int centerX,
            int centerY,
            int centerZ,
            int radius,
            List<StorageContainer> containers,
            Map<Resource, Integer> itemCounts,
            long scannedAtGameTime
    ) {
        public Snapshot {
            containers = List.copyOf(containers);
            EnumMap<Resource, Integer> copy = new EnumMap<>(Resource.class);
            copy.putAll(itemCounts);
            itemCounts = Collections.unmodifiableMap(copy);
        }

        public int count(Resource resource) {
            return itemCounts.getOrDefault(resource, 0);
        }

        /** Ready-to-eat foods only. Raw wheat and seeds are deliberately excluded. */
        public int readyFoodItems() {
            return count(Resource.BREAD) + count(Resource.CARROTS)
                    + count(Resource.POTATOES) + count(Resource.BEETROOT);
        }

        public int publicContainerCount() {
            return containers.size();
        }
    }

    private VisibleStorageScanner() {}

    /**
     * Expands the survey radius when storage is crowded or containers are found near the edge.
     * Every pass replaces the prior snapshot, so item counts are never added twice. Unloaded
     * chunks remain untouched. The caller should cache the result and schedule this infrequently.
     */
    public static Snapshot scanAdaptive(Level level, BlockPos center, int initialRadius,
                                         int maximumRadius, int minimumFreeSlots) {
        if (initialRadius < 0 || maximumRadius < initialRadius || maximumRadius > 24
                || minimumFreeSlots < 0) {
            throw new IllegalArgumentException("Invalid adaptive storage scan bounds.");
        }
        int radius = initialRadius;
        Snapshot snapshot = scan(level, center, radius);
        while (radius < maximumRadius) {
            int occupiedSlots = 0;
            int totalSlots = 0;
            boolean nearEdge = false;
            for (StorageContainer container : snapshot.containers()) {
                occupiedSlots += Math.min(container.occupiedSlots(), container.slots());
                totalSlots += container.slots();
                int dx = Math.abs(container.x() - center.getX());
                int dz = Math.abs(container.z() - center.getZ());
                if (dx >= radius - 1 || dz >= radius - 1) nearEdge = true;
            }
            int freeSlots = Math.max(0, totalSlots - occupiedSlots);
            boolean crowded = freeSlots < minimumFreeSlots
                    || (totalSlots > 0 && (double) occupiedSlots / totalSlots >= 0.80);
            if (!nearEdge && !crowded) break;
            radius = Math.min(maximumRadius, radius + 6);
            snapshot = scan(level, center, radius);
        }
        return snapshot;
    }

    public static Snapshot scan(Level level, BlockPos center, int radius) {
        if (level == null || center == null) throw new IllegalArgumentException("Level and center are required.");
        if (level.isClientSide) throw new IllegalArgumentException("Public storage must be scanned on the logical server.");
        if (radius < 0 || radius > 24) throw new IllegalArgumentException("Radius must be between 0 and 24.");

        EnumMap<Resource, Integer> totals = new EnumMap<>(Resource.class);
        for (Resource resource : Resource.values()) totals.put(resource, 0);
        List<StorageContainer> found = new ArrayList<>();

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int y = Math.max(level.getMinBuildHeight(), center.getY() - 6);
                 y <= Math.min(level.getMaxBuildHeight() - 1, center.getY() + 6); y++) {
                for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                    cursor.set(x, y, z);
                    if (!level.hasChunkAt(cursor)) continue;

                    var state = level.getBlockState(cursor);
                    String kind;
                    if (state.is(Blocks.CHEST)) kind = "chest";
                    else if (state.is(Blocks.TRAPPED_CHEST)) kind = "trapped_chest";
                    else if (state.is(Blocks.BARREL)) kind = "barrel";
                    else continue;

                    BlockEntity blockEntity = level.getBlockEntity(cursor);
                    if (!(blockEntity instanceof Container container)) continue;

                    int occupied = 0;
                    for (int slot = 0; slot < container.getContainerSize(); slot++) {
                        ItemStack stack = container.getItem(slot);
                        if (stack.isEmpty()) continue;
                        occupied++;
                        Resource resource = classify(stack.getItem());
                        totals.merge(resource, stack.getCount(), Integer::sum);
                    }
                    found.add(new StorageContainer(x, y, z, kind, occupied, container.getContainerSize()));
                }
            }
        }
        return new Snapshot(center.getX(), center.getY(), center.getZ(), radius, found, totals, level.getGameTime());
    }

    private static Resource classify(Item item) {
        if (item == Items.BREAD) return Resource.BREAD;
        if (item == Items.CARROT) return Resource.CARROTS;
        if (item == Items.POTATO) return Resource.POTATOES;
        if (item == Items.BEETROOT) return Resource.BEETROOT;
        if (item == Items.WHEAT) return Resource.WHEAT;
        if (item == Items.WHEAT_SEEDS || item == Items.BEETROOT_SEEDS
                || item == Items.MELON_SEEDS || item == Items.PUMPKIN_SEEDS) return Resource.SEEDS;
        if (item == Items.OAK_LOG || item == Items.SPRUCE_LOG || item == Items.BIRCH_LOG
                || item == Items.JUNGLE_LOG || item == Items.ACACIA_LOG || item == Items.DARK_OAK_LOG
                || item == Items.MANGROVE_LOG || item == Items.CHERRY_LOG
                || item == Items.OAK_PLANKS || item == Items.SPRUCE_PLANKS || item == Items.BIRCH_PLANKS
                || item == Items.JUNGLE_PLANKS || item == Items.ACACIA_PLANKS || item == Items.DARK_OAK_PLANKS
                || item == Items.MANGROVE_PLANKS || item == Items.CHERRY_PLANKS) {
            return Resource.WOOD;
        }
        if (item == Items.COBBLESTONE || item == Items.STONE || item == Items.ANDESITE
                || item == Items.DIORITE || item == Items.GRANITE || item == Items.COBBLED_DEEPSLATE
                || item == Items.DEEPSLATE_BRICKS || item == Items.BRICKS) return Resource.STONE;
        return Resource.OTHER;
    }
}
