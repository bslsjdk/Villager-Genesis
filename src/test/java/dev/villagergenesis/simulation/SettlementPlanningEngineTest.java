package dev.villagergenesis.simulation;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SettlementPlanningEngineTest {
    @Test void unobservedSiteUsesConservativeBalancedPlan() {
        var graph=new SettlementNodeGraph();
        var node=graph.addNode(UUID.randomUUID(),100,200,63,"unobserved",45,0);
        node.updateTerrain(63,"unobserved",45,40,35,45);
        var plan=SettlementPlanningEngine.plan(node,List.of());
        assertEquals(SettlementPlanningEngine.Role.BALANCED,plan.role());
        assertTrue(plan.reason().contains("unobserved"));
    }
    @Test void highFoodPotentialFavorsAgriculture() {
        var graph=new SettlementNodeGraph();
        var node=graph.addNode(UUID.randomUUID(),100,200,70,"minecraft:plains",85,0);
        node.updateTerrain(70,"minecraft:plains",85,90,30,35);
        assertEquals(SettlementPlanningEngine.Role.AGRICULTURE,SettlementPlanningEngine.plan(node,List.of()).role());
    }
    @Test void highTimberPotentialFavorsForestry() {
        var graph=new SettlementNodeGraph();
        var node=graph.addNode(UUID.randomUUID(),100,200,70,"minecraft:forest",85,0);
        node.updateTerrain(70,"minecraft:forest",85,35,95,35);
        assertEquals(SettlementPlanningEngine.Role.FORESTRY,SettlementPlanningEngine.plan(node,List.of()).role());
    }
    @Test void plannerNeedsNoWorldOrChunkAccess() {
        var graph=new SettlementNodeGraph();
        var node=graph.addNode(UUID.randomUUID(),100,200,70,"minecraft:windswept_hills",60,0);
        node.updateTerrain(70,"minecraft:windswept_hills",60,35,35,95);
        assertDoesNotThrow(()->SettlementPlanningEngine.plan(node,graph.edges()));
    }
}