package dev.villagergenesis.simulation;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VillageOfflineSimulationProgressTest {
    @Test
    void fertileRemoteSettlementSpecializesAndGrowsWithoutPlayerPlanningTask() {
        var graph = new SettlementNodeGraph();
        var node = graph.addNode(UUID.randomUUID(), 0, 0, 70, "minecraft:plains", 85, 0);
        node.updateTerrain(70, "minecraft:plains", 85, 90, 30, 35);

        int initialPopulation = node.population();
        for (int step = 1; step <= 40; step++) {
            VillageOfflineSimulationService.simulateDataOnly(node, step * 100L);
        }

        assertEquals("AGRICULTURE", node.planningRole());
        assertTrue(node.population() > initialPopulation,
                "A fertile remote settlement should grow from its own bounded economy simulation.");
        assertTrue(node.foodReserve() >= 0);
        assertTrue(node.population() <= 256);
        assertTrue(node.kind().ordinal() >= SettlementNodeGraph.Kind.VILLAGE.ordinal());
    }

    @Test
    void unknownSiteDoesNotInventAHighYieldBiomeOrExceedPopulationCap() {
        var graph = new SettlementNodeGraph();
        var node = graph.addNode(UUID.randomUUID(), 0, 0, 63, "unobserved", 45, 0);
        node.updateTerrain(63, "unobserved", 45, 40, 35, 45);

        for (int step = 1; step <= 40; step++) {
            VillageOfflineSimulationService.simulateDataOnly(node, step * 100L);
        }

        assertEquals("BALANCED", node.planningRole());
        assertTrue(node.population() >= 0 && node.population() <= 256);
        assertTrue(node.foodReserve() >= 0);
        assertTrue(node.materials() >= 0);
    }

    @Test
    void strongAgricultureCanAdvanceSettlementStage() {
        var graph = new SettlementNodeGraph();
        var node = graph.addNode(UUID.randomUUID(), 0, 0, 70, "minecraft:plains", 85, 0);
        node.updateTerrain(70, "minecraft:plains", 85, 100, 25, 25);
        node.setPlanningRole("AGRICULTURE");
        node.setEstimates(24, 400, 0, 0, 0);

        for (int step = 1; step <= 80; step++) {
            VillageOfflineSimulationService.simulateDataOnly(node, step * 100L);
        }

        assertTrue(node.population() > 24);
        assertTrue(node.population() <= 256);
        assertTrue(node.kind().ordinal() >= SettlementNodeGraph.Kind.TOWN.ordinal());
    }
}
