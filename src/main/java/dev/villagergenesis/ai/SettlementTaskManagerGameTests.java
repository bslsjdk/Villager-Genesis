package dev.villagergenesis.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Server-backed integration tests for task leases. These exercise real entities and the
 * per-level manager, but deliberately do not claim to prove that a work Goal chooses a target.
 */
@GameTestHolder("villager_genesis")
@PrefixGameTestTemplate(false)
public final class SettlementTaskManagerGameTests {
    private SettlementTaskManagerGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void twoRealVillagersCannotClaimTheSameLog(GameTestHelper helper) {
        BlockPos target = helper.absolutePos(new BlockPos(2, 1, 2));
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.OAK_LOG);

        Villager first = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 1, 2));
        Villager second = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 1, 2));
        long now = helper.getLevel().getGameTime();

        helper.assertTrue(SettlementTaskManager.tryClaim(helper.getLevel(), first.getUUID(),
                SettlementTaskManager.TaskType.LOGGING, target, now),
                "First villager should claim the target");
        helper.assertTrue(!SettlementTaskManager.tryClaim(helper.getLevel(), second.getUUID(),
                SettlementTaskManager.TaskType.LOGGING, target, now),
                "Second villager must not claim an already-owned target");
        helper.assertTrue(SettlementTaskManager.renew(helper.getLevel(), first.getUUID(),
                SettlementTaskManager.TaskType.LOGGING, target, now + 1),
                "The first villager should still own and renew its lease");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void villagerDeathReleasesItsClaim(GameTestHelper helper) {
        BlockPos target = helper.absolutePos(new BlockPos(2, 1, 2));
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.OAK_LOG);

        Villager first = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 1, 2));
        Villager replacement = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 1, 2));
        long now = helper.getLevel().getGameTime();

        helper.assertTrue(SettlementTaskManager.tryClaim(helper.getLevel(), first.getUUID(),
                SettlementTaskManager.TaskType.LOGGING, target, now),
                "Initial villager should claim the target");
        helper.assertTrue(!SettlementTaskManager.tryClaim(helper.getLevel(), replacement.getUUID(),
                SettlementTaskManager.TaskType.LOGGING, target, now),
                "Replacement must initially be blocked by the lease");

        first.hurt(helper.getLevel().damageSources().genericKill(), 1000.0F);

        helper.assertTrue(!first.isAlive(), "The first villager should have died in the test");
        helper.assertTrue(SettlementTaskManager.tryClaim(helper.getLevel(), replacement.getUUID(),
                SettlementTaskManager.TaskType.LOGGING, target, helper.getLevel().getGameTime()),
                "Death event should release the old owner's lease immediately");
        helper.succeed();
    }
}
