package dev.villagergenesis.simulation;

import java.util.UUID;

/** Bounded executor for persistent abstract plans. Never queries terrain or constructs buildings. */
public final class SettlementPlanningService {
    private SettlementPlanningService() {}
    /** Processes at most one pending task; returns true when a task was consumed. */
    public static boolean processOne(SettlementNodeGraph graph, SettlementTaskQueue queue, long gameTime) {
        if(graph==null||queue==null) return false;
        SettlementTaskQueue.Task task=queue.peekPending();
        if(task==null) return false;
        UUID taskId=task.id();
        queue.update(taskId,SettlementTaskQueue.Status.IN_PROGRESS,10,gameTime);
        SettlementNodeGraph.Node node=graph.node(task.nodeId());
        if(node==null) {
            queue.update(taskId,SettlementTaskQueue.Status.FAILED,100,gameTime);
            return true;
        }
        SettlementPlanningEngine.Plan plan=SettlementPlanningEngine.plan(node,graph.edges());
        node.setPlanningRole(plan.role().name());
        graph.markChanged();
        queue.completePlan(taskId,plan.role().name(),plan.reason(),gameTime);
        return true;
    }
}