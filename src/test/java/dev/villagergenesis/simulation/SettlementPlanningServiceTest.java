package dev.villagergenesis.simulation;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SettlementPlanningServiceTest {
    @Test void processesOnePendingTaskIntoPersistedPlan() {
        var graph=new SettlementNodeGraph(); UUID id=UUID.randomUUID();
        var node=graph.addNode(id,0,0,70,"minecraft:plains",85,0);
        node.updateTerrain(70,"minecraft:plains",85,90,30,35);
        var queue=new SettlementTaskQueue(); var task=queue.enqueueUnique(id,SettlementTaskQueue.Type.PLAN_SETTLEMENT,10);
        assertTrue(SettlementPlanningService.processOne(graph,queue,20));
        assertEquals(SettlementTaskQueue.Status.COMPLETE,task.status());
        assertEquals("AGRICULTURE",task.planCode()); assertEquals(100,task.progress()); assertEquals(1,task.attempts());
    }
    @Test void missingNodeFailsWithoutWorldSideEffects() {
        var graph=new SettlementNodeGraph(); var queue=new SettlementTaskQueue();
        var task=queue.enqueueUnique(UUID.randomUUID(),SettlementTaskQueue.Type.PLAN_SETTLEMENT,10);
        assertTrue(SettlementPlanningService.processOne(graph,queue,20));
        assertEquals(SettlementTaskQueue.Status.FAILED,task.status());
        assertFalse(SettlementPlanningService.processOne(graph,queue,21));
    }
    @Test void processesAtMostOneTaskPerCall() {
        var graph=new SettlementNodeGraph(); var queue=new SettlementTaskQueue();
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        graph.addNode(a,0,0,70,"minecraft:plains",85,0);
        graph.addNode(b,1000,0,70,"minecraft:forest",85,0);
        queue.enqueueUnique(a,SettlementTaskQueue.Type.PLAN_SETTLEMENT,10);
        queue.enqueueUnique(b,SettlementTaskQueue.Type.PLAN_SETTLEMENT,11);
        assertTrue(SettlementPlanningService.processOne(graph,queue,20));
        assertEquals(1,queue.tasks().stream().filter(t->t.status()==SettlementTaskQueue.Status.PENDING).count());
    }
}