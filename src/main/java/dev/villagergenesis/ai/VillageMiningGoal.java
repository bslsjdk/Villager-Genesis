package dev.villagergenesis.ai;

import dev.villagergenesis.memory.VillagerMemory;
import dev.villagergenesis.society.VillageRoleService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;
import java.util.List;

/**
 * Conservative starter mining: only exposed natural stone-like blocks in already loaded chunks.
 * It never mines ores, block entities, or blocks next to obvious player/settlement fixtures.
 * Real vanilla drops are calculated before the block is removed and must fit in inventory.
 */
public final class VillageMiningGoal extends Goal {
    private static final String NEXT_TRY = "villager_genesis_next_mining";
    private static final String NEXT_SCAN = "villager_genesis_next_mining_scan";
    private static final int SCAN_COOLDOWN = 100;
    private static final int WORK_COOLDOWN = 60;
    private static final int MAX_TRAVEL_TICKS = 180;
    private static final int SEARCH_RADIUS = 6;
    private final Villager villager;
    private BlockPos target;
    private int travelTicks;
    private int breakingTicks;
    private int requiredBreakingTicks = -1;
    private boolean finished;

    public VillageMiningGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!VillageWorkPolicy.canPerformRoutineWork(villager)
                || !(villager.level() instanceof ServerLevel level)
                || !villager.isAlive() || villager.isBaby() || villager.getTarget() != null) return false;
        long now = level.getGameTime();
        if (now < villager.getPersistentData().getLong(NEXT_TRY)
                || now < villager.getPersistentData().getLong(NEXT_SCAN)) return false;
        if (VillageRoleService.ensureRole(villager) != VillageRoleService.Role.MINER) return false;
        if (!(villager.getMainHandItem().getItem() instanceof PickaxeItem)
                || villager.getMainHandItem().getDamageValue() >= villager.getMainHandItem().getMaxDamage()) return false;
        villager.getPersistentData().putLong(NEXT_SCAN, now + SCAN_COOLDOWN);
        target = findTarget(level, villager.blockPosition());
        return target != null;
    }

    @Override
    public void start() {
        travelTicks = 0;
        breakingTicks = 0;
        requiredBreakingTicks = -1;
        finished = false;
        villager.getPersistentData().putString("villager_genesis_current_goal", "mine_stone");
        if (target != null) villager.getNavigation().moveTo(
                target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 0.75D);
    }

    @Override
    public void tick() {
        VillageWorkPolicy.claimRoutineWork(villager);
        travelTicks++;
        if (!(villager.level() instanceof ServerLevel level) || target == null || !level.hasChunkAt(target)) {
            finished = true;
            return;
        }
        if (villager.blockPosition().distSqr(target) > 4.0D) {
            if (travelTicks >= MAX_TRAVEL_TICKS) finished = true;
            else if (travelTicks % 10 == 0
                    && (villager.getNavigation().isDone()
                    || !VillageWorkPolicy.navigationTargets(villager, target))) {
                // Reclaim the path if vanilla Brain tried to redirect the worker to a social target.
                villager.getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 0.75D);
            }
            return;
        }
        villager.getNavigation().stop();
        BlockState state = level.getBlockState(target);
        if (!isMineableStone(state) || isProtected(level, target)) {
            finished = true;
            return;
        }
        if (requiredBreakingTicks < 0) requiredBreakingTicks = calculateBreakingTicks(level, target, state);
        breakingTicks++;
        if (breakingTicks % 4 == 0) villager.swing(InteractionHand.MAIN_HAND);
        if (breakingTicks >= requiredBreakingTicks) {
            mineOneBlock(level);
            finished = true;
        }
    }

    @Override
    public boolean canContinueToUse() {
        return VillageWorkPolicy.canPerformRoutineWork(villager) && !finished && target != null
                && travelTicks < MAX_TRAVEL_TICKS && villager.isAlive() && villager.getTarget() == null;
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        if (villager.level() instanceof ServerLevel level) {
            villager.getPersistentData().putLong(NEXT_TRY, level.getGameTime() + WORK_COOLDOWN);
        }
        villager.getPersistentData().putString("villager_genesis_current_goal", "observe");
        target = null;
        travelTicks = 0;
        breakingTicks = 0;
        requiredBreakingTicks = -1;
        finished = false;
    }

    private BlockPos findTarget(ServerLevel level, BlockPos origin) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    if (!isMineableStone(state) || isProtected(level, pos) || !isExposed(level, pos)) continue;
                    double distance = pos.distToCenterSqr(origin.getX() + 0.5D, origin.getY() + 0.5D, origin.getZ() + 0.5D);
                    if (distance < bestDistance) { best = pos; bestDistance = distance; }
                }
            }
        }
        return best;
    }

    private boolean isMineableStone(BlockState state) {
        return state.is(Blocks.STONE) || state.is(Blocks.ANDESITE) || state.is(Blocks.DIORITE)
                || state.is(Blocks.GRANITE) || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.DEEPSLATE) || state.is(Blocks.COBBLED_DEEPSLATE)
                || state.is(Blocks.TUFF);
    }

    private boolean isExposed(ServerLevel level, BlockPos pos) {
        for (var direction : net.minecraft.core.Direction.values()) {
            BlockPos neighbour = pos.relative(direction);
            if (level.hasChunkAt(neighbour) && level.getBlockState(neighbour).isAir()) return true;
        }
        return false;
    }

    private boolean isProtected(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos) || level.getBlockEntity(pos) != null) return true;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            BlockPos check = pos.offset(dx, dy, dz);
            if (!level.hasChunkAt(check) || level.getBlockEntity(check) != null) return true;
            BlockState state = level.getBlockState(check);
            if (state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.FURNACE) || state.is(Blocks.CHEST)
                    || state.is(Blocks.BARREL) || state.is(Blocks.BEDROCK)
                    || state.is(Blocks.BEEHIVE) || state.is(Blocks.BEE_NEST) || state.is(Blocks.LADDER)
                    || state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH) || state.is(Blocks.LANTERN)
                    || state.is(BlockTags.BEDS) || state.is(BlockTags.DOORS) || state.is(Blocks.FARMLAND)
                    || state.is(BlockTags.CROPS)) return true;
        }
        return false;
    }

    private int calculateBreakingTicks(ServerLevel level, BlockPos pos, BlockState state) {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F) return 20 * 60;
        ItemStack tool = villager.getMainHandItem();
        float speed = tool.getDestroySpeed(state);
        if (speed <= 0.0F) speed = 1.0F;
        return Math.max(1, (int) Math.ceil(hardness * 30.0F / speed));
    }

    private void mineOneBlock(ServerLevel level) {
        if (!level.hasChunkAt(target) || isProtected(level, target)) return;
        BlockState state = level.getBlockState(target);
        if (!isMineableStone(state) || !isExposed(level, target)) return;
        ItemStack tool = villager.getMainHandItem();
        if (!(tool.getItem() instanceof PickaxeItem) || tool.getDamageValue() >= tool.getMaxDamage()) return;
        List<ItemStack> drops = Block.getDrops(state, level, target, level.getBlockEntity(target), villager, tool);
        if (drops.size() != 1 || drops.getFirst().isEmpty()) return;
        ItemStack drop = drops.getFirst();
        Container inventory = villager.getInventory();
        int slot = findInventorySlot(inventory, drop);
        if (slot < 0) return;
        if (!level.destroyBlock(target, false, villager)) return;
        ItemStack existing = inventory.getItem(slot);
        if (existing.isEmpty()) inventory.setItem(slot, drop.copy());
        else existing.grow(drop.getCount());
        inventory.setChanged();
        VillagerMemory memory = VillagerMemory.forVillager(villager);
        memory.learnKnowledge("stone_mining");
        memory.recordActionOutcome("mine_stone", "success",
                "Mined one exposed natural stone block with a real pickaxe and stored its real drop.", level.getGameTime());
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, target.getX() + 0.5D, target.getY() + 0.5D,
                target.getZ() + 0.5D, 3, 0.15D, 0.15D, 0.15D, 0.01D);
    }

    private int findInventorySlot(Container inventory, ItemStack drop) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack existing = inventory.getItem(i);
            if (existing.isEmpty()) return i;
            if (ItemStack.isSameItemSameComponents(existing, drop)
                    && existing.getCount() + drop.getCount() <= Math.min(existing.getMaxStackSize(), inventory.getMaxStackSize())) return i;
        }
        return -1;
    }
}
