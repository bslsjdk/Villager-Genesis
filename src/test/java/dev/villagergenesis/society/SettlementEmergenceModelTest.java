package dev.villagergenesis.society;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettlementEmergenceModelTest {
    private static SettlementEmergenceModel.Survey gatheringSurvey() {
        return new SettlementEmergenceModel.Survey(
                3, 3, 3, 0, 1, 12, 1, 0, 0, 20, true, true);
    }

    private static SettlementEmergenceModel.Survey campSurvey() {
        return new SettlementEmergenceModel.Survey(
                4, 7, 4, 2, 3, 20, 1, 1, 1, 24, true, true);
    }

    private static SettlementEmergenceModel.Survey villageSurvey() {
        return new SettlementEmergenceModel.Survey(
                6, 14, 6, 4, 5, 32, 1, 3, 3, 48, true, true);
    }

    @Test
    void incompleteSurveyDoesNotChangeStateOrProgress() {
        var state = SettlementEmergenceModel.State.initial(100);
        var incomplete = new SettlementEmergenceModel.Survey(
                5, 20, 5, 4, 4, 40, 1, 2, 3, 64, true, false);

        var result = SettlementEmergenceModel.observe(state, incomplete, 200);

        assertFalse(result.changed());
        assertSame(state, result.state());
        assertTrue(result.reason().contains("incomplete"));
    }

    @Test
    void onePasserbyCannotCreateASettlement() {
        var state = SettlementEmergenceModel.State.initial(100);
        var survey = new SettlementEmergenceModel.Survey(
                1, 20, 1, 1, 1, 100, 1, 5, 5, 100, true, true);

        var result = SettlementEmergenceModel.observe(state, survey, 200);

        assertFalse(result.changed());
        assertEquals(SettlementEmergenceModel.Stage.WANDERING_GROUP, result.state().stage());
    }

    @Test
    void gatheringRequiresRepeatedEvidence() {
        var state = SettlementEmergenceModel.State.initial(100);
        var first = SettlementEmergenceModel.observe(state, gatheringSurvey(), 120);
        assertFalse(first.changed());
        assertEquals(1, first.state().consecutiveQualifyingSurveys());

        var second = SettlementEmergenceModel.observe(first.state(), gatheringSurvey(), 140);
        assertTrue(second.changed());
        assertEquals(SettlementEmergenceModel.Stage.GATHERING, second.state().stage());
    }

    @Test
    void incompleteEvidenceResetsStreakAndCannotSkipStages() {
        var state = SettlementEmergenceModel.State.initial(100);
        var first = SettlementEmergenceModel.observe(state, villageSurvey(), 120);
        assertFalse(first.changed());

        var weak = new SettlementEmergenceModel.Survey(
                2, 0, 2, 0, 0, 0, 0, 0, 0, 0, true, true);
        var reset = SettlementEmergenceModel.observe(first.state(), weak, 140);
        assertFalse(reset.changed());
        assertEquals(0, reset.state().consecutiveQualifyingSurveys());

        var next = SettlementEmergenceModel.observe(reset.state(), villageSurvey(), 160);
        assertFalse(next.changed());
        assertEquals(1, next.state().consecutiveQualifyingSurveys());
    }

    @Test
    void matureEvidenceCanReachLaterStagesInsteadOfBeingMaskedByGatheringRules() {
        assertEquals(SettlementEmergenceModel.Stage.CAMP,
                matureStage(campSurvey()));
        assertEquals(SettlementEmergenceModel.Stage.VILLAGE,
                matureStage(villageSurvey()));
    }

    private static SettlementEmergenceModel.Stage matureStage(SettlementEmergenceModel.Survey survey) {
        var state = SettlementEmergenceModel.State.initial(0);
        for (int i = 0; i < 12; i++) {
            state = SettlementEmergenceModel.observe(state, survey, i * 20L).state();
            if (state.stage() == SettlementEmergenceModel.Stage.VILLAGE) break;
        }
        return state.stage();
    }

    @Test
    void progressionRequiresStableEvidenceForEachStage() {
        var state = SettlementEmergenceModel.State.initial(0);
        state = advanceUntilStage(state, gatheringSurvey(), 20,
                SettlementEmergenceModel.Stage.GATHERING);
        state = advanceUntilStage(state, campSurvey(), 40,
                SettlementEmergenceModel.Stage.CAMP);
        state = advanceUntilStage(state, villageSurvey(), 60,
                SettlementEmergenceModel.Stage.VILLAGE);

        assertEquals(SettlementEmergenceModel.Stage.VILLAGE, state.stage());
    }

    @Test
    void rejectsInvalidSurveyCountsAndBackwardsTime() {
        assertThrows(IllegalArgumentException.class, () ->
                new SettlementEmergenceModel.Survey(
                        2, 1, 3, 0, 0, 0, 0, 0, 0, 0, true, true));
        var state = SettlementEmergenceModel.State.initial(100);
        assertThrows(IllegalArgumentException.class, () ->
                SettlementEmergenceModel.observe(state, gatheringSurvey(), 99));
    }

    private static SettlementEmergenceModel.State advanceUntilStage(
            SettlementEmergenceModel.State state,
            SettlementEmergenceModel.Survey survey,
            long startTime,
            SettlementEmergenceModel.Stage target) {
        long now = startTime;
        for (int i = 0; i < 12 && state.stage() != target; i++) {
            state = SettlementEmergenceModel.observe(state, survey, now).state();
            now += 20;
        }
        assertEquals(target, state.stage(), "failed to reach " + target);
        return state;
    }
}
