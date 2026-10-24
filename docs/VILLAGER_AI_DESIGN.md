# Villager Genesis AI Design

## Goal

Build a WorldBox-inspired simulation using original implementation code: citizens should sustain a settlement through visible, interruptible, resource-backed work. The game world is authoritative; plans never create items or blocks by themselves.

## Core model: settlement planner + worker executor

### Settlement planner
Run low-frequency planning (target: every 40 game ticks per settlement, not every villager tick). Maintain needs and an ordered task board. Do not scan the whole area independently for every villager.

Task states:
- `OPEN`: valid target and prerequisites, no worker owns it.
- `CLAIMED`: one worker owns it with a time-limited lease.
- `IN_PROGRESS`: worker is physically executing it.
- `BLOCKED`: a real prerequisite is missing; wait for a relevant state change or retry after backoff.
- `DONE`: world and inventory changes were verified.
- `FAILED`: target disappeared, path failed repeatedly, inventory was full, or another explicit failure occurred.

Each task records an id, type, target, prerequisites, expected resource input/output, owner UUID, progress, attempt count, last failure, and next retry tick. Expired leases return to OPEN. A task cannot be claimed twice.

### Task utility
Score available tasks rather than relying on a long list of unconditional goals:
- settlement shortage / urgency;
- whether the task unlocks another task;
- worker role suitability and learned skill;
- travel distance and path feasibility;
- tool, inventory, time-of-day and safety requirements;
- whether another worker already handles the same shortage.

Urgent survival and safety override ordinary production. Founding residents use flexible starter roles; specialisation is a preference, not a lock that makes the first two citizens incapable of gathering basic materials.

### Worker executor
A worker owns one task at a time. The executor must:
1. Validate prerequisites and reserve the task.
2. Navigate to a reachable target in already-loaded chunks.
3. Perform visible, timed work.
4. Revalidate the target immediately before changing the world.
5. Apply real drops, item consumption, tool durability and inventory capacity.
6. Mark DONE only after the world change is confirmed.
7. On failure, record a reason, release the reservation, and use bounded backoff rather than retrying every tick.

The executor owns navigation while working. Vanilla social Brain memories must not continually redirect an assigned worker. Social activity is allowed when work is unavailable or the settlement's urgent needs are met, not as an endless replacement for a failed task.

## Founding loop

The first residents must not depend on a pre-existing village, storage block, bed, or wool:
1. Gather natural wood.
2. Convert real logs into planks.
3. Craft and place a crafting table.
4. Craft appropriate starter tools from real ingredients.
5. Collect stone and food as available.
6. Construct a simple shelter and shared storage.
7. Establish farming and sustainable food.
8. Rebalance jobs as population and shortages change.

No step may fabricate resources. If a prerequisite is missing, the task board should create or prioritise the task that produces it instead of letting a worker idle forever.

## Scheduling and social behaviour

Priority order:
1. Immediate danger / flee.
2. Rest when unsafe to continue or during the configured rest period.
3. Critical shortage tasks: starter wood, food, required tools.
4. Construction and storage logistics.
5. Sustainable production and exploration.
6. Social / idle behaviour only when no suitable task is available.

Social interaction should have a defined simulation effect if enabled (relationship, cooperation, knowledge, or reproduction prerequisites). Otherwise it should not repeatedly interrupt a productive task.

## Resource and world safety invariants

- Never load a new chunk for sensing, pathfinding, or resource search.
- Never break a block entity, protected structure, farm, bed, door, workstation, or player-built structure without explicit ownership evidence.
- Do not remove an entire tree in one tick. Revalidate every log, use survival-like break timing, and preserve real drops.
- Do not mine ore without the required tool tier and a validated drop path.
- Reserve targets so workers do not all chase the same block.
- Bound search radius, scan cadence, retries and path recalculation.
- Full inventories and missing tools are explicit blocked states, not infinite loops.
- Resting workers must not move, harvest, craft, or build.
- Persistent task state must recover safely after save/load; expired claims must be reclaimable.

## Debuggability and acceptance tests

Expose or log each worker's role, current task, task state, target, progress, last failure and next retry tick. Add deterministic tests for:
- no social target can steal navigation from a worker in progress;
- a starter pair gathers wood without a chest or crafting table;
- no tree / full inventory / missing tool causes bounded backoff;
- two workers cannot claim the same log;
- an invalidated target produces no items;
- rest and danger interrupt routine work cleanly;
- no scan reads an unloaded chunk;
- mining takes time and only yields real, valid drops;
- after a task finishes, the worker selects the next useful task instead of idling indefinitely.

## Current implementation status

This document is the target architecture, not a claim that every item is already implemented. Current code still uses several separate Goal classes. The first repair is to stop vanilla social movement from blocking or redirecting work; subsequent work should consolidate task selection, leases, dynamic staffing and explicit failure states around the task-board model above.
