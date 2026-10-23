package dev.villagergenesis.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Persists learned knowledge inside real book items in public chests, trapped chests, or barrels.
 * The book is not consumed when read. Archives are ordinary physical items, not a virtual inventory,
 * so players can move, remove, or destroy them like other stored items.
 */
public final class VillageKnowledgeArchiveService {
    private static final String ARCHIVE_ROOT = "villager_genesis_knowledge_archive";
    private static final String KNOWLEDGE_IDS = "knowledge_ids";
    private static final String ARCHIVE_NAME = "villager_genesis_archive_name";
    private static final int DEFAULT_RADIUS = 8;

    private VillageKnowledgeArchiveService() {}

    /** Write one knowledge ID into a real book already stored in a public container. */
    public static boolean archiveOne(ServerLevel level, BlockPos center, String knowledgeId) {
        if (knowledgeId == null || knowledgeId.isBlank()) return false;
        return visitBooks(level, center, DEFAULT_RADIUS, (container, slot, stack) -> {
            Set<String> archived = readKnowledge(stack);
            if (archived.contains(knowledgeId)) return false;
            archived.add(knowledgeId);
            writeKnowledge(stack, archived);
            container.setChanged();
            return true;
        });
    }

    /** Return the first archived knowledge this villager has not learned yet. */
    public static String findUnknownKnowledge(ServerLevel level, BlockPos center, Set<String> alreadyKnown) {
        final String[] found = {null};
        visitBooks(level, center, DEFAULT_RADIUS, (container, slot, stack) -> {
            if (found[0] != null) return false;
            for (String id : readKnowledge(stack)) {
                if (!alreadyKnown.contains(id)) {
                    found[0] = id;
                    return true;
                }
            }
            return false;
        });
        return found[0];
    }

    private static Set<String> readKnowledge(ItemStack stack) {
        Set<String> result = new LinkedHashSet<>();
        CustomData custom = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag root = custom.copyTag();
        if (!root.contains(ARCHIVE_ROOT, Tag.TAG_COMPOUND)) return result;
        ListTag ids = root.getCompound(ARCHIVE_ROOT).getList(KNOWLEDGE_IDS, Tag.TAG_STRING);
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.getString(i);
            if (!id.isBlank()) result.add(id);
        }
        return result;
    }

    private static void writeKnowledge(ItemStack stack, Set<String> ids) {
        CompoundTag root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        CompoundTag archive = root.contains(ARCHIVE_ROOT, Tag.TAG_COMPOUND)
                ? root.getCompound(ARCHIVE_ROOT) : new CompoundTag();
        ListTag list = new ListTag();
        ids.stream().sorted().forEach(id -> list.add(StringTag.valueOf(id)));
        archive.put(KNOWLEDGE_IDS, list);
        root.put(ARCHIVE_ROOT, archive);
        root.putBoolean(ARCHIVE_NAME, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        if (!stack.has(DataComponents.CUSTOM_NAME)) {
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("Village Knowledge Archive"));
        }
    }

    private static boolean visitBooks(ServerLevel level, BlockPos center, int radius, BookVisitor visitor) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int y = Math.max(level.getMinBuildHeight(), center.getY() - 6);
                 y <= Math.min(level.getMaxBuildHeight() - 1, center.getY() + 6); y++) {
                for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                    cursor.set(x, y, z);
                    if (!level.hasChunkAt(cursor)) continue;
                    var state = level.getBlockState(cursor);
                    if (!(state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST)
                            || state.is(Blocks.BARREL))) continue;
                    BlockEntity entity = level.getBlockEntity(cursor);
                    if (!(entity instanceof Container container)) continue;
                    for (int slot = 0; slot < container.getContainerSize(); slot++) {
                        ItemStack stack = container.getItem(slot);
                        if (!stack.is(Items.BOOK) && !stack.is(Items.WRITTEN_BOOK)) continue;
                        if (visitor.visit(container, slot, stack)) return true;
                    }
                }
            }
        }
        return false;
    }

    @FunctionalInterface
    private interface BookVisitor {
        boolean visit(Container container, int slot, ItemStack stack);
    }
}
