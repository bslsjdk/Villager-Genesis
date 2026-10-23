package dev.villagergenesis.simulation;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

public final class SettlementTaskQueue extends SavedData {
    private static final String DATA_NAME = "villager_genesis_settlement_tasks";
    private static final Factory<SettlementTaskQueue> FACTORY =
            new Factory<>(SettlementTaskQueue::new, SettlementTaskQueue::load, DataFixTypes.LEVEL);
    public enum Type { PLAN_SETTLEMENT }
    public enum Status { PENDING, IN_PROGRESS, COMPLETE, FAILED }

    public static final class Task {
        private final UUID id, nodeId;
        private final Type type;
        private Status status;
        private int progress, attempts;
        private long createdAt, updatedAt;
        private String planCode, planReason;
        private Task(UUID id, UUID nodeId, Type type, Status status, int progress, int attempts,
                     long createdAt, long updatedAt, String planCode, String planReason) {
            this.id=id; this.nodeId=nodeId; this.type=type; this.status=status;
            this.progress=clamp(progress,0,100); this.attempts=Math.max(0,attempts);
            this.createdAt=Math.max(0,createdAt); this.updatedAt=Math.max(this.createdAt,updatedAt);
            this.planCode=planCode==null?"":planCode; this.planReason=planReason==null?"":planReason;
        }
        public UUID id(){return id;} public UUID nodeId(){return nodeId;} public Type type(){return type;}
        public Status status(){return status;} public int progress(){return progress;}
        public int attempts(){return attempts;} public long createdAt(){return createdAt;}
        public long updatedAt(){return updatedAt;} public String planCode(){return planCode;}
        public String planReason(){return planReason;}
    }
    private final List<Task> tasks = new ArrayList<>();
    public static SettlementTaskQueue get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }
    public Task enqueueUnique(UUID nodeId, Type type, long gameTime) {
        Objects.requireNonNull(nodeId,"nodeId"); Objects.requireNonNull(type,"type");
        for(Task task:tasks) if(task.nodeId.equals(nodeId)&&task.type==type
                &&(task.status==Status.PENDING||task.status==Status.IN_PROGRESS)) return task;
        Task task=new Task(UUID.randomUUID(),nodeId,type,Status.PENDING,0,0,gameTime,gameTime,"","");
        tasks.add(task); setDirty(); return task;
    }
    public Task peekPending() {
        return tasks.stream().filter(t->t.status==Status.PENDING)
                .min(Comparator.comparingLong(Task::createdAt).thenComparing(t->t.id.toString())).orElse(null);
    }
    public boolean update(UUID taskId, Status status, int progress, long gameTime) {
        Objects.requireNonNull(taskId,"taskId"); Objects.requireNonNull(status,"status");
        for(Task task:tasks) if(task.id.equals(taskId)) {
            Status old=task.status; task.status=status; task.progress=clamp(progress,0,100);
            if(status==Status.IN_PROGRESS&&old!=Status.IN_PROGRESS) task.attempts++;
            task.updatedAt=Math.max(task.updatedAt,gameTime); setDirty(); return true;
        }
        return false;
    }
    public boolean completePlan(UUID taskId,String planCode,String reason,long gameTime) {
        Objects.requireNonNull(taskId,"taskId");
        if(planCode==null||planCode.isBlank()) throw new IllegalArgumentException("Plan code must not be blank.");
        for(Task task:tasks) if(task.id.equals(taskId)) {
            task.planCode=planCode; task.planReason=reason==null?"":reason;
            task.status=Status.COMPLETE; task.progress=100;
            task.updatedAt=Math.max(task.updatedAt,gameTime); setDirty(); return true;
        }
        return false;
    }
    public List<Task> tasks(){return Collections.unmodifiableList(new ArrayList<>(tasks));}
    public static SettlementTaskQueue load(CompoundTag tag, HolderLookup.Provider registries) {
        SettlementTaskQueue q=new SettlementTaskQueue(); ListTag list=tag.getList("tasks",Tag.TAG_COMPOUND);
        for(int i=0;i<list.size();i++) {
            CompoundTag e=list.getCompound(i); if(!e.hasUUID("id")||!e.hasUUID("node")) continue;
            Type type=enumValue(Type.values(),e.getInt("type"),Type.PLAN_SETTLEMENT);
            Status status=enumValue(Status.values(),e.getInt("status"),Status.PENDING);
            q.tasks.add(new Task(e.getUUID("id"),e.getUUID("node"),type,status,e.getInt("progress"),
                    e.getInt("attempts"),e.getLong("created"),e.getLong("updated"),
                    e.getString("plan_code"),e.getString("plan_reason")));
        }
        return q;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list=new ListTag();
        for(Task t:tasks) {
            CompoundTag e=new CompoundTag(); e.putUUID("id",t.id); e.putUUID("node",t.nodeId);
            e.putInt("type",t.type.ordinal()); e.putInt("status",t.status.ordinal());
            e.putInt("progress",t.progress); e.putInt("attempts",t.attempts);
            e.putLong("created",t.createdAt); e.putLong("updated",t.updatedAt);
            e.putString("plan_code",t.planCode); e.putString("plan_reason",t.planReason); list.add(e);
        }
        tag.put("tasks",list); return tag;
    }
    private static int clamp(int v,int min,int max){return Math.max(min,Math.min(max,v));}
    private static <T>T enumValue(T[] values,int ordinal,T fallback){return ordinal>=0&&ordinal<values.length?values[ordinal]:fallback;}
}