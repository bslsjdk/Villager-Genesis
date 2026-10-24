package dev.villagergenesis.ai;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SettlementTaskManagerTest {
    private static final BlockPos TARGET = new BlockPos(12, 64, -7);

    @Test
    void onlyOneVillagerCanClaimTheSameTarget() {
        SettlementTaskManager.LeaseRegistry registry = new SettlementTaskManager.LeaseRegistry();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(registry.tryClaim(first, SettlementTaskManager.TaskType.LOGGING, TARGET, 10));
        assertFalse(registry.tryClaim(second, SettlementTaskManager.TaskType.LOGGING, TARGET, 10));
        assertTrue(registry.isClaimedBy(first, SettlementTaskManager.TaskType.LOGGING, TARGET));
        assertEquals(1, registry.activeClaims());
    }

    @Test
    void claimExpiresWhenNotRenewedAndCanBeReclaimed() {
        SettlementTaskManager.LeaseRegistry registry = new SettlementTaskManager.LeaseRegistry();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(registry.tryClaim(first, SettlementTaskManager.TaskType.MINING, TARGET, 0));
        assertFalse(registry.tryClaim(second, SettlementTaskManager.TaskType.MINING, TARGET, 99));
        assertTrue(registry.tryClaim(second, SettlementTaskManager.TaskType.MINING, TARGET,
                SettlementTaskManager.LEASE_TICKS));
        assertFalse(registry.isClaimedBy(first, SettlementTaskManager.TaskType.MINING, TARGET));
        assertTrue(registry.isClaimedBy(second, SettlementTaskManager.TaskType.MINING, TARGET));
    }

    @Test
    void renewalExtendsLeaseFromRenewalTime() {
        SettlementTaskManager.LeaseRegistry registry = new SettlementTaskManager.LeaseRegistry();
        UUID owner = UUID.randomUUID();

        assertTrue(registry.tryClaim(owner, SettlementTaskManager.TaskType.LOGGING, TARGET, 0));
        assertTrue(registry.renew(owner, SettlementTaskManager.TaskType.LOGGING, TARGET, 80));
        assertFalse(registry.tryClaim(UUID.randomUUID(), SettlementTaskManager.TaskType.LOGGING,
                TARGET, 100));
        assertTrue(registry.tryClaim(UUID.randomUUID(), SettlementTaskManager.TaskType.LOGGING,
                TARGET, 180));
    }

    @Test
    void releaseOnlyWorksForCurrentOwner() {
        SettlementTaskManager.LeaseRegistry registry = new SettlementTaskManager.LeaseRegistry();
        UUID owner = UUID.randomUUID();
        UUID intruder = UUID.randomUUID();

        assertTrue(registry.tryClaim(owner, SettlementTaskManager.TaskType.MINING, TARGET, 0));
        registry.release(intruder, new SettlementTaskManager.TaskKey(
                SettlementTaskManager.TaskType.MINING, TARGET.asLong()));
        assertTrue(registry.isClaimedBy(owner, SettlementTaskManager.TaskType.MINING, TARGET));

        registry.release(owner, new SettlementTaskManager.TaskKey(
                SettlementTaskManager.TaskType.MINING, TARGET.asLong()));
        assertEquals(0, registry.activeClaims());
        assertTrue(registry.tryClaim(intruder, SettlementTaskManager.TaskType.MINING, TARGET, 1));
    }

    @Test
    void deathCleanupReleasesOwnersClaimAndAllowsAnotherWorker() {
        SettlementTaskManager.LeaseRegistry registry = new SettlementTaskManager.LeaseRegistry();
        UUID deadVillager = UUID.randomUUID();
        UUID replacement = UUID.randomUUID();

        assertTrue(registry.tryClaim(deadVillager, SettlementTaskManager.TaskType.LOGGING, TARGET, 5));
        registry.releaseOwner(deadVillager);

        assertEquals(0, registry.activeClaims());
        assertTrue(registry.tryClaim(replacement, SettlementTaskManager.TaskType.LOGGING, TARGET, 6));
    }

    @Test
    void oneVillagerCannotClaimTwoDifferentTasksAtOnce() {
        SettlementTaskManager.LeaseRegistry registry = new SettlementTaskManager.LeaseRegistry();
        UUID owner = UUID.randomUUID();

        assertTrue(registry.tryClaim(owner, SettlementTaskManager.TaskType.LOGGING, TARGET, 0));
        assertFalse(registry.tryClaim(owner, SettlementTaskManager.TaskType.MINING,
                TARGET.above(), 1));
        assertEquals(1, registry.activeClaims());
    }
}
