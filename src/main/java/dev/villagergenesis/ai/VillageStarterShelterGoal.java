package dev.villagergenesis.ai;

import dev.villagergenesis.memory.VillagerMemory;
import dev.villagergenesis.society.VillageRoleService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Founding residents can build a tiny lean-to from nine real planks in their own inventory.
 * It intentionally needs neither a chest, crafting table, bed, nor wool.
 */
public final class VillageStarterShelterGoal extends Goal {
    private static final String NEXT_TRY = "villager_genesis_next_starter_shelter";
    private static final int COST = 9;
    private static final int COOLDOWN = 1200;
    private static final int MAX_TRAVEL_TICKS = 160;

    private final Villager villager;
    private BlockPos site;
    private Block buildingBlock;
    private int materialSlot = -1;
    private int travelTicks;
    private boolean finished;

    public VillageStarterShelterGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!VillageWorkPolicy.canPerformRoutineWork(villager)) return false;
        if (!(villager.level() instanceof ServerLevel level) || !villager.isAlive()
                || villager.isBaby() || villager.getTarget() != null
                || level.getGameTime() < villager.getPersistentData().getLong(NEXT_TRY)) {
            return false;
        }

        Container inventory = villager.getInventory();

        // Shelter materials must not consume the founding resident's first tool/workbench budget.
        // Resting on the ground remains available independently, so this is a priority guard,
        // not a survival blocker.
        if (!hasStarterTool(inventory) || !hasNearbyCraftingTable(level, villager.blockPosition())) {
            return false;
        }

        materialSlot = -1;
        buildingBlock = null;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.getCount() < COST || !(stack.getItem() instanceof BlockItem blockItem)) continue;
            Block candidate = blockItem.getBlock();
            if (!candidate.defaultBlockState().is(BlockTags.PLANKS)) continue;
            materialSlot = i;
            buildingBlock = candidate;
            break;
        }
        if (materialSlot < 0 || buildingBlock == null) return false;

        VillageStructureRegistry registry = VillageStructureRegistry.get(level);
        for (VillageStructureRegistry.OwnedBlock block : registry.blocksOfType("starter_shelter")) {
            if (block.pos().distSqr(villager.blockPosition()) <= 12L * 12L
                    && level.hasChunkAt(block.pos())
                    && level.getBlockState(block.pos()).is(BlockTags.PLANKS)) {
                return false;
            }
        }
        site = findSite(level, villager.blockPosition());
        return site != null;
    }

    @Override
    public void start() {
        travelTicks = 0;
        finished = false;
        villager.getPersistentData().putString("villager_genesis_current_goal", "build_starter_shelter");
        navigate();
    }

    @Override
    public void tick() {
        VillageWorkPolicy.claimRoutineWork(villager);
        if (!(villager.level() instanceof ServerLevel level) || site == null) {
            finished = true;
            return;
        }
        travelTicks++;
        if (villager.blockPosition().distSqr(site) <= 4.0D) {
            build(level);
            finished = true;
        } else if (travelTicks >= MAX_TRAVEL_TICKS) {
            finished = true;
        } else if (villager.getNavigation().isDone() && travelTicks % 30 == 0) {
            navigate();
        }
    }

    @Override
    public boolean canContinueToUse() {
        if (!VillageWorkPolicy.canPerformRoutineWork(villager)) return false;
        return !finished && travelTicks < MAX_TRAVEL_TICKS && villager.isAlive()
                && villager.getTarget() == null;
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        if (villager.level() instanceof ServerLevel level) {
            villager.getPersistentData().putLong(NEXT_TRY, level.getGameTime() + COOLDOWN);
        }
        villager.getPersistentData().putString("villager_genesis_current_goal", "observe");
        site = null;
        buildingBlock = null;
        materialSlot = -1;
        travelTicks = 0;
        finished = false;
    }

    private boolean hasStarterTool(Container inventory) {
        VillageRoleService.Role role = VillageRoleService.ensureRole(villager);
        ItemStack held = villager.getMainHandItem();
        if (isRoleTool(held, role)) return true;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (isRoleTool(inventory.getItem(i), role)) return true;
        }
        // Non-tool roles can still build once the shared workbench exists.
        return role != VillageRoleService.Role.FARMER
                && role != VillageRoleService.Role.GATHERER
                && role != VillageRoleService.Role.LUMBERJACK
                && role != VillageRoleService.Role.MINER;
    }

    private boolean isRoleTool(ItemStack stack, VillageRoleService.Role role) {
        if (stack.isEmpty()) return false;
        if (stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage()) return false;
        return switch (role) {
            case FARMER -> stack.getItem() instanceof HoeItem;
            case GATHERER, LUMBERJACK -> stack.getItem() instanceof AxeItem;
            case MINER -> stack.getItem() instanceof PickaxeItem;
            default -> true;
        };
    }

    private boolean hasNearbyCraftingTable(ServerLevel level, BlockPos origin) {
        // Bounded scan over already-loaded chunks only; do not expand the world for a shelter.
        for (int dy = -2; dy <= 3; dy++) {
            for (int dx = -6; dx <= 6; dx++) {
                for (int dz = -6; dz <= 6; dz++) {
                    if (dx * dx + dz * dz > 36) continue;
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (level.hasChunkAt(pos)
                            && level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) return true;
                }
            }
        }
        return false;
    }

    private BlockPos findSite(ServerLevel level, BlockPos origin) {
        for (int radius = 2; radius <= 5; radius++) {
            for (int attempt = 0; attempt < 24; attempt++) {
                int dx = villager.getRandom().nextInt(radius * 2 + 1) - radius;
                int dz = villager.getRandom().nextInt(radius * 2 + 1) - radius;
                if (Math.max(Math.abs(dx), Math.abs(dz)) < 2) continue;
                BlockPos center = origin.offset(dx, 0, dz);
                BlockPos floor = center.below();
                if (!level.hasChunkAt(floor) || !level.hasChunkAt(center.offset(1, 3, 0))
                        || !level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) continue;
                BlockState floorState = level.getBlockState(floor);
                if (floorState.getFluidState().isEmpty() == false) continue;

                List<BlockPos> positions = structurePositions(center);
                boolean clear = true;
                for (BlockPos pos : positions) {
                    if (!level.hasChunkAt(pos) || !level.getBlockState(pos).isAir()
                            || !level.getFluidState(pos).isEmpty()
                            || level.getBlockEntity(pos) != null) {
                        clear = false;
                        break;
                    }
                }
                if (clear) return center.immutable();
            }
        }
        return null;
    }

    private List<BlockPos> structurePositions(BlockPos center) {
        List<BlockPos> positions = new ArrayList<>(COST);
        positions.add(center.offset(0, 1, -1));
        positions.add(center.offset(0, 2, -1));
        for (int y = 1; y <= 2; y++) {
            positions.add(center.offset(-1, y, 0));
            positions.add(center.offset(1, y, 0));
        }
        for (int x = -1; x <= 1; x++) positions.add(center.offset(x, 3, 0));
        return positions;
    }

    private void navigate() {
        if (site != null) villager.getNavigation().moveTo(
                site.getX() + 0.5D, site.getY(), site.getZ() + 0.5D, 0.7D);
    }

    private void build(ServerLevel level) {
        if (site == null || buildingBlock == null || materialSlot < 0
                || !level.hasChunkAt(site.offset(1, 3, 0))) return;
        Container inventory = villager.getInventory();
        if (materialSlot >= inventory.getContainerSize()) return;
        ItemStack materials = inventory.getItem(materialSlot);
        if (materials.getCount() < COST || !(materials.getItem() instanceof BlockItem blockItem)
                || blockItem.getBlock() != buildingBlock
                || !buildingBlock.defaultBlockState().is(BlockTags.PLANKS)) return;

        List<BlockPos> positions = structurePositions(site);
        for (BlockPos pos : positions) {
            if (!level.hasChunkAt(pos) || !level.getBlockState(pos).isAir()
                    || !level.getFluidState(pos).isEmpty() || level.getBlockEntity(pos) != null) return;
        }

        ItemStack original = materials.copy();
        List<BlockPos> placed = new ArrayList<>(COST);
        BlockState state = buildingBlock.defaultBlockState();
        for (BlockPos pos : positions) {
            if (!level.setBlock(pos, state, Block.UPDATE_ALL)) {
                for (BlockPos rollback : placed) level.setBlock(rollback, Blocks.AIR.defaultBlockState(), 3);
                inventory.setItem(materialSlot, original);
                inventory.setChanged();
                return;
            }
            placed.add(pos);
        }

        materials.shrink(COST);
        if (materials.isEmpty()) inventory.setItem(materialSlot, ItemStack.EMPTY);
        inventory.setChanged();
        VillageStructureRegistry registry = VillageStructureRegistry.get(level);
        for (BlockPos pos : placed) registry.registerPlacedBlock(
                pos, "starter_shelter", villager.getUUID(), level.getGameTime());
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, site.getX() + 0.5D,
                site.getY() + 1.0D, site.getZ() + 0.5D, 10, 0.5D, 0.4D, 0.5D, 0.02D);
        level.playSound(null, site, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.6F, 0.9F);
        VillagerMemory memory = VillagerMemory.forVillager(villager);
        memory.learnKnowledge("starter_shelter_construction");
        memory.recordActionOutcome("build_starter_shelter", "success",
                "Built a tiny open-front shelter using nine real planks from personal inventory.", level.getGameTime());
    }
}
