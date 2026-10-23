package dev.villagergenesis.society;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VillageLeadershipTest {
    @Test
    void electsHighestScoringEligibleResidentAndKeepsThemWhileEligible() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        VillageLeadership leadership = new VillageLeadership();

        assertEquals(second, leadership.updateLeader(List.of(
                new VillageLeadership.Candidate(first, true, true, 5),
                new VillageLeadership.Candidate(second, true, true, 9))).orElseThrow());
        assertEquals(second, leadership.updateLeader(List.of(
                new VillageLeadership.Candidate(first, true, true, 100),
                new VillageLeadership.Candidate(second, true, true, 9))).orElseThrow());
    }

    @Test
    void replacesDeadOrAbsentLeaderAndHasNoAgeRequirement() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        VillageLeadership leadership = new VillageLeadership();
        leadership.updateLeader(List.of(
                new VillageLeadership.Candidate(first, true, true, 10),
                new VillageLeadership.Candidate(second, true, true, 5)));

        assertEquals(second, leadership.updateLeader(List.of(
                new VillageLeadership.Candidate(first, false, true, 10),
                new VillageLeadership.Candidate(second, true, true, 5))).orElseThrow());
    }
}
