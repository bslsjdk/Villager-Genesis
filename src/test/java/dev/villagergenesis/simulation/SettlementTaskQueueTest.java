package dev.villagergenesis.simulation;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SettlementTaskQueueTest {
    @Test void duplicateActiveTasksAreDeduplicated() {
        var q=new SettlementTaskQueue(); UUID node=UUID.randomUUID();
        var a=q.enqueueUnique(node,SettlementTaskQueue.Type.PLAN_SETTLEMENT,10);
        var b=q.enqueueUnique(node,SettlementTaskQueue.Type.PLAN_SETTLEMENT,20);
        assertEquals(a.id(),b.id()); assertEquals(1,q.tasks().size());
    }
    @Test void attemptsIncrementOnlyWhenEnteringInProgress() {
        var q=new SettlementTaskQueue(); var t=q.enqueueUnique(UUID.randomUUID(),SettlementTaskQueue.Type.PLAN_SETTLEMENT,10);
        q.update(t.id(),SettlementTaskQueue.Status.IN_PROGRESS,10,11);
        q.update(t.id(),SettlementTaskQueue.Status.IN_PROGRESS,50,12);
        assertEquals(1,t.attempts());
    }
    @Test void completedPlanSurvivesNbtRoundTrip() {
        var q=new SettlementTaskQueue(); var t=q.enqueueUnique(UUID.randomUUID(),SettlementTaskQueue.Type.PLAN_SETTLEMENT,10);
        q.update(t.id(),SettlementTaskQueue.Status.IN_PROGRESS,10,11);
        q.completePlan(t.id(),"AGRICULTURE","Strong food potential.",12);
        var restored=SettlementTaskQueue.load(q.save(new CompoundTag(),null),null);
        var copy=restored.tasks().get(0);
        assertEquals(t.id(),copy.id()); assertEquals(SettlementTaskQueue.Status.COMPLETE,copy.status());
        assertEquals(100,copy.progress()); assertEquals(1,copy.attempts());
        assertEquals("AGRICULTURE",copy.planCode()); assertEquals("Strong food potential.",copy.planReason());
    }
    @Test void pendingQueueUsesOldestCreationTime() {
        var q=new SettlementTaskQueue(); q.enqueueUnique(UUID.randomUUID(),SettlementTaskQueue.Type.PLAN_SETTLEMENT,20);
        var earlier=q.enqueueUnique(UUID.randomUUID(),SettlementTaskQueue.Type.PLAN_SETTLEMENT,10);
        assertEquals(earlier.id(),q.peekPending().id());
    }
    @Test void unknownTaskUpdatesAreRejected() {
        var q=new SettlementTaskQueue(); UUID missing=UUID.randomUUID();
        assertFalse(q.update(missing,SettlementTaskQueue.Status.COMPLETE,100,1));
        assertFalse(q.completePlan(missing,"BALANCED","No task.",1));
    }
}