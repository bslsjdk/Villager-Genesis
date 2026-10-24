package dev.villagergenesis.society;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SettlementMembershipIndexTest {
    private final UUID villageA = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID villageB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final UUID resident = UUID.fromString("00000000-0000-0000-0000-000000000010");

    @Test
    void firstClaimAddsResidentWithoutMutatingOriginalSnapshot() {
        var original = new SettlementMembershipIndex();
        var claim = original.claim(resident, villageA);

        assertEquals(SettlementMembershipIndex.ClaimResult.ADDED, claim.result());
        assertTrue(original.settlementOf(resident).isEmpty());
        assertEquals(villageA, claim.index().settlementOf(resident).orElseThrow());
        assertEquals(1, claim.index().residentCount(villageA));
    }

    @Test
    void sameSettlementClaimIsIdempotent() {
        var index = new SettlementMembershipIndex(Map.of(resident, villageA));
        var claim = index.claim(resident, villageA);

        assertEquals(SettlementMembershipIndex.ClaimResult.ALREADY_MEMBER, claim.result());
        assertSame(index, claim.index());
    }

    @Test
    void claimCannotSilentlyMoveResidentBetweenSettlements() {
        var index = new SettlementMembershipIndex(Map.of(resident, villageA));
        var claim = index.claim(resident, villageB);

        assertEquals(SettlementMembershipIndex.ClaimResult.CONFLICT, claim.result());
        assertSame(index, claim.index());
        assertEquals(villageA, claim.index().settlementOf(resident).orElseThrow());
    }

    @Test
    void transferRequiresExpectedCurrentSettlement() {
        var index = new SettlementMembershipIndex(Map.of(resident, villageA));
        var stale = index.transfer(resident, villageB, villageA);
        assertEquals(SettlementMembershipIndex.ClaimResult.CONFLICT, stale.result());
        assertSame(index, stale.index());

        var moved = index.transfer(resident, villageA, villageB);
        assertEquals(SettlementMembershipIndex.ClaimResult.TRANSFERRED, moved.result());
        assertEquals(villageB, moved.index().settlementOf(resident).orElseThrow());
        assertEquals(villageA, index.settlementOf(resident).orElseThrow());
    }

    @Test
    void rejectsNullMembershipKeysAndValues() {
        assertThrows(NullPointerException.class,
                () -> new SettlementMembershipIndex(java.util.Collections.singletonMap(resident, null)));
        var index = new SettlementMembershipIndex();
        assertThrows(NullPointerException.class, () -> index.claim(null, villageA));
        assertThrows(NullPointerException.class, () -> index.claim(resident, null));
    }
}
