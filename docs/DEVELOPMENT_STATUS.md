# Development Status: Settlement Autonomy

Last updated: 2026-10-24
Repository: https://github.com/bslsjdk/Villager-Genesis
Working branch: feat/settlement-resource-jobs
Minecraft: Java 1.21.1, NeoForge 21.1.264

This is the continuity ledger. Keep committed, CI-verified, in-game-verified, and planned work separate.

## Hard constraints
- Use real items from real inventories; never create virtual equipment, materials, or ammunition.
- Do not load new chunks. Scan only already-loaded chunks.
- Do not break player structures to gather resources or create space.
- Reuse vanilla professions and systems where possible.
- Keep PRs as drafts/open unless the user explicitly authorizes merging.
- A role/knowledge flag is not proof that an executable behavior exists.
- User primarily works from an Android phone and needs direct repository work, not manual coding instructions.

## Repository and PRs
- Working branch: feat/settlement-resource-jobs
- Draft PR #14: https://github.com/bslsjdk/Villager-Genesis/pull/14
- Draft PR #13: https://github.com/bslsjdk/Villager-Genesis/pull/13
- PR #14 base noted as release/0.2-autonomous-settlement-loop
- Do not merge either PR without explicit authorization.

## Existing foundations (source exists; not all in-game verified)
- Knowledge prerequisite graph with cycle prevention; villager memory records knowledge, outcomes, and suggestions.
- Roles include FARMER, BUILDER, GATHERER, CRAFTER, MINER, LUMBERJACK, MAYOR, MASON, GUARD, SCOUT, CARETAKER. Runtime assignment is still UUID-based. A new side-effect-free RoleStaffingPlanner now proposes deterministic assignments from explicit staffing targets, incumbent roles, skill scores, and vanilla-profession fit; it is not yet wired into world/settlement membership.
- Visible storage scanning covers ordinary chests, trapped chests, and barrels in loaded chunks, with bounded radius (maximum 24).
- BUILDER shelter construction consumes 33 real oak planks; MASON shelter construction consumes 33 real cobblestone. Placement is validated and rollback attempted on failure.
- Storage expansion is limited to CRAFTER, CARETAKER, MAYOR.
- Autonomy service installs equipment, storage/shelter construction, exploration, guard combat, and resident threat-avoidance goals per live villager instance.
- Equipment goal takes real equipment from storage. Guard combat has melee and a committed ranged implementation using real stored ammunition; see validation caveats below.
- Guard combat prioritizes hostile monsters targeting villagers and records successful attack outcomes.
- New ordinary-resident escape goal avoids nearby hostile monsters without attacking: checks danger periodically, picks a clear escape destination with solid footing, checks all relevant chunks are already loaded, and records the outcome. Successful escapes teach only hostile-awareness knowledge.

## Latest validation

- Commits 09f633ca65229eb7d3a53f05dea0f23714f86eb0 and 51bb7f1f6c63e5b068c881c5d21d915032e052b1 add and test the pure `SettlementStaffingStability` gate. It resets a stability window when the desired-role fingerprint changes, enforces a minimum stable duration and post-application cooldown, and records application time only after the caller confirms success. It is not yet connected to live settlement discovery, persistence, or role application.
- Commit 96351d82c1b5722f68451d13c4417e7f8f7670fd: guard ranged code; Build run #281 / 40005431735 passed.
- Commit eb738f8839d9f027a513004208d7023038e18c66: fallback to movement/close range when no real ammunition is available; Build run #284 / 40005548168 passed.
- Commit 376fdea7b04a9e7c1acb4b2f763471b5ad32ad9c: added VillageResidentFleeGoal.
- Commit 4ff7c281beb599661946c217869729c86f8b1cd6: installed resident escape behavior in VillageAutonomyService.
- Commit 9b459216e980f3eb0a8a9ec71c502ee78e57c79a: records hostile-awareness knowledge only after a successful escape.
- Commit f7644a1d2a38a1f023f0bf64890132b49dfc3e42: Build run #294 / 40005844030 passed, including Build and test and JAR upload.
- Follow-up correctness fix afdca3579f2acb8d7c4389c0e2510e4211806e67: a threat disappearing no longer counts as a successful escape or grants hostile-awareness knowledge. CI run #296 / 40006359081 was still running at last check.
- Commits 244cdf64aee045a291835804de8d1e916be0d69f and 9d7babdc8513a61f8de70a700767880122090e38 add a deterministic, side-effect-free role staffing proposal engine and unit tests.
- Commits e4bc9516ba8bea6f32198d0b8c62b1f4cb6cbd17 and 3d47189e76cf488fe743d0fd317f8a22b8c55694 add SettlementStaffingPolicy: bounded staffing targets from food reserve, surveyed farm workload, housing shortage, and confirmed storage survey. It explicitly avoids inferring lumberjack/mason shortages when storage survey is incomplete. The policy and staffing planner are pure proposal layers, not yet connected to a live settlement roster. CI for these newest commits must be checked.
- No guard combat or resident escape behavior has been tested in a running Minecraft world.



- Latest verified CI at time of update: Build run #317 / 40007102739 completed successfully for commit 09b6ed429201f6802979503986e0abbd7d2b0146. This verifies the source state through the staffing stability documentation commit, not the newer membership-index commits.
- New membership primitive: `SettlementMembershipIndex` (commit e9386dd46d4117ec2f5237cd776dbf08031067e9; result semantics clarified in e135db699cf5d76f1b63422fe9fb9bb5566e3765) provides immutable resident-to-settlement mapping, conflict-safe first claims, and compare-and-set transfers. Its unit tests were added in d52b72bae85b3bd07352fe4da77278a624a285e1.
- Important limitation: this membership index is currently a pure domain primitive. It is not yet connected to Minecraft entity persistent data, world SavedData, settlement discovery, or live roster surveying. Do not count it as runtime membership integration until those adapters exist and are tested.


- Bottom-up formation: `SettlementEmergenceModel` was added in commit c0248e8554ea8ca78a1556a3fff16d05db5a3770, with tests in c810c70f83feb5144dc9bcfe8053771ec215d35b. It models repeated evidence for WANDERING_GROUP -> GATHERING -> CAMP -> VILLAGE and never skips a stage. This is still a pure model, not live entity grouping, persistence, task execution, or construction.
- Architectural priority: villagers must be able to form stable local groups without waiting for abstract terrain-node discovery to find a pre-existing village. Next work is a bounded loaded-villager group tracker, persisted emergence state, and real-item shared tasks.

## Unfinished work that must not be forgotten
1. Re-check CI for the latest resident-escape commit and fix any compile/test failures.
2. In-game test escape destination selection, navigation, fleeing from a monster, and goal interruption alongside vanilla behavior.
3. Add deterministic tests where feasible for escape destination safety and state transitions.
4. Implement real shield blocking only after API/behavior validation; merely equipping a shield is not blocking.
5. Implement durability-aware equipment maintenance and replacement only from real available supplies.
6. Coordinate guards so protection is distributed rather than every guard pursuing the same target; define defense radius and posting behavior.
7. Improve after-event learning based on observed outcomes, not arbitrary skill grants.
8. Connect SettlementStaffingPolicy and RoleStaffingPlanner to settlement-scoped membership discovery, then apply changes only through a persistent, cooldown-limited service. Add hysteresis so staffing does not oscillate when needs fluctuate. Keep role assignment changes explicit and persistent.
9. Implement real gathering, crafting, logistics, mentorship/knowledge transfer, and career mobility without duplicating vanilla systems.
10. Settlement simulation (food, housing, population, territory, diplomacy, etc.) remains incomplete; do not claim otherwise based on metadata alone.
11. Regression-check scan frequency, cooldowns, goal interruption, inventory races, persistence, loaded-chunk safety, and player-build protection.

## Next execution order
1. Check Actions for the latest resident-escape correctness fix; fix any build/test failures.
2. Review goal priority interactions and test escape behavior in Minecraft.
3. Add deterministic tests for bounded destination selection and state transitions.
4. Verify the new staffing planner and stability-gate tests, then add settlement-scoped roster discovery, persisted stability state, and safe application of staffing changes.
5. Continue resource logistics, learning, and simulation.
5. Keep CI and in-game validation status separate.

## Status language
- Committed: source is on the branch.
- CI passed: GitHub Actions completed successfully for the relevant commit.
- In-game verified: tested in Minecraft; never infer this from CI.
- Not implemented: keep listed until code and validation exist.


## Bottom-up live group discovery (new source; CI pending)

- `LocalVillagerGroupTracker` is a world `SavedData` service that discovers groups from the current `ServerLevel` entity iteration, without searching for existing vanilla villages or requesting chunks.
- Membership UUIDs, stable group UUIDs, centre coordinates, first/last observation, distinct in-game resident days, and repeated presence scans are persisted.
- The existing 40-tick server-level scheduler now invokes the tracker with a per-scan cap of 256 live villagers. Group membership uses bounded proximity and a maximum group size; absent entities are not immediately treated as dead or permanently departed.
- This is the first live discovery/persistence step, not a finished settlement simulation. It currently does not yet survey actual beds, food stocks, farm plots, safe build sites, or completed shared projects, and it deliberately does not promote groups to camps/villages until those real-world checks are wired in. It does not yet assign persistent settlement IDs to the existing role allocator or create a shared task queue per live group.
- The stage-model CI failure was traced to the progression test restarting its simulated timestamp at an earlier value between helper calls. The test now keeps the simulated game clock monotonic. The updated Actions run must still pass before this is called CI-verified.


## Real wood supply (source committed; latest relevant build passed)

- Registered `VillageLumberjackGoal` for the existing persistent LUMBERJACK role. A GATHERER can also learn `wood_harvesting` after a successful task, enabling later skill sharing/role adaptation.
- The goal looks only in a small radius of already-loaded terrain, checks for logs connected vertically and nearby leaves, rejects obvious structure markers and nearby block entities, and harvests one trunk block per trip.
- It requires an existing nearby container with an empty slot. It calculates the block's real drops, breaks the block without spawning duplicate drops, then stores the computed item stack. No wood is fabricated and no chunk is requested.
- Safety limitation: tree-vs-player-build detection is heuristic, not a perfect ownership system. The current implementation is intentionally conservative and only cuts one log per trip; it needs in-game testing around player-built log structures and different tree types before broadening the harvest rules.
- GitHub Actions run for commit `b2710e49` completed successfully. This verifies that commit's CI only; it does not verify gameplay.
- This provides a real wood acquisition path for later storage/shelter construction, but it does not yet guarantee the lumberjack is staffed when a new group has no saved role policy, or provide a container-free starter phase. Group-based staffing and resource surveying remain pending.


## Founding wood bootstrap and first crafting stage (CI/gameplay pending)

- Commit fdd4cf0293e27ac986b26bde704bc5d0930c1de8 adds `VillageBootstrapCraftingGoal`: eligible founding roles can process real carried logs into four matching planks, then consume four real planks to craft a crafting table. Outputs are inserted into the villager's real inventory; ingredient consumption precedes output and no items are synthesized.
- Commit 605fc8c115638f0698078bac36968086a9edab8b registers this goal at priority 7.
- This is still only the first crafting stage. Tool crafting/equipping and tool-aware task gating remain pending.
- Commit 2533361200db48f53839d193bd92468fd6764a88 adds a physical workbench-placement goal. It consumes a carried crafting-table item only after revalidating an empty, dry, supported position in a loaded chunk; it avoids placing within 12 blocks of an existing crafting table.
- Commit e55c7c19c04ed552c36e505743416bbfc65ce2f3 registers that placement behavior. This closes the basic log -> planks -> carried crafting table -> placed workbench path in source, pending compilation and in-game validation.
- Tool crafting and tool-aware task gating are still pending: a workbench can now be placed, but this does not yet mean a villager can craft/equip a hoe, axe, or pickaxe.
- Vanilla recipe equivalence has been manually encoded for common vanilla log/plank families; this needs compile and gameplay validation. The build has not been confirmed successful.

## Founding wood bootstrap (source committed; CI/gameplay pending)

- Commit 06d60dea4ecb5d8a32e74ad4100cca9cdeaeb87d changes the lumberjack goal so FARMER and GATHERER residents can perform the first bounded wood-gathering task instead of requiring a pre-existing specialist or learned flag.
- If no public container has a free slot, the goal can place the exact game-computed log drop into a fitting slot in the villager's own real inventory. It checks capacity before breaking the log and still destroys without duplicate world drops.
- This removes one circular dependency (no chest -> no wood), but it does NOT yet craft planks or a crafting table, equip tools, or make farmer role equivalent to being able to farm. Those are the next required stages.
- The starter goal still uses conservative tree heuristics and needs a compile/CI check plus in-game testing. Do not call the full bootstrap economy complete until the chain is wired end-to-end: log -> planks -> crafting table -> tools -> tool-gated work.

## Hunting and animal husbandry (role foundation committed; behaviors pending)

- Added persistent social-role identifiers `HUNTER` and `HERDER` to `VillageRoleService` and included them in the deterministic initial-role pool. Existing valid saved role IDs remain unchanged.
- These role identifiers are not executable behavior. No hunting, animal luring, pen construction, breeding, feeding, or animal-product collection is claimed implemented yet.
- Next: implement a bounded survey of nearby animals using only loaded entities; distinguish wild/nearby animals from animals already in pens; plan food acquisition from actual conditions; and make herding use real vanilla breeding foods and existing animals rather than creating animals or drops.
- Hunting must be conservative and outcome-driven, avoid exhausting nearby wildlife, and use real drops/inventory. Do not add hostile-mob combat behavior as a shortcut for hunting.
- Husbandry must not break player builds, place fences without real materials, teleport animals, or load chunks. Pen safety and ownership detection need explicit checks; uncertain player-built enclosures must be left alone.
- Role assignment is currently UUID-based and not settlement-scoped, so the next step must connect these roles to local group staffing without assigning every nearby villager the same job.


## Population-scaled housing materials (planner committed; runtime integration pending)

- Added `SettlementHousingPlanner`, a pure planner that calculates bed shortage from surveyed residents and usable beds, then caps the number of beds craftable in one planning pass.
- Bed crafting uses the vanilla material rule: three planks and three compatible same-colour wool items per bed. The planner deliberately accepts `compatibleWoolItems` rather than total wool across all colours; a real survey must group wool by colour before passing the largest usable matching group.
- The planner never creates materials, places beds, or loads chunks. Unit tests cover shortages, mixed-colour wool input, population caps, and invalid counts.
- Added animal evidence fields to `SettlementStaffingPolicy.Survey` with a backwards-compatible constructor. Hunter staffing requires both food shortage and surveyed nearby wildlife; herder staffing requires actual penned breedable animals or a documented wool/housing need with nearby animals. This is a planning foundation only.
- Important unfinished integration: the live group tracker still does not produce resident-scoped bed counts, per-colour wool inventory, plank counts, or animal surveys, and no goal yet crafts/places beds or shears/shepherds sheep. Do not describe these planners as working in-game.
- For large settlements, next implementation must use per-settlement task reservations and bounded work budgets so hundreds of residents do not all compete for the same bed, sheep, chest slot, or construction order.


## Founding-pair workload policy (committed; runtime staffing still not connected)

- Updated `SettlementStaffingPolicy`: a dedicated mayor is not requested below five residents, preserving scarce labor for survival work during the first two-person stage.
- Completed the role-trimming order so builder and animal roles are also reduced when the combined requested slots exceed population. Added regression tests for two residents and a three-resident high-shortage case.
- The staffing policy remains a pure planner. It does not yet override the one-time UUID-based initial role assignment, so this change is not evidence that two newly spawned villagers will immediately receive the ideal jobs in-game.
- Early survival should be opportunistic rather than over-specialized: gatherers may eventually perform safe, bounded resource tasks when there are only one or two residents; dedicated hunter/herder roles should become worthwhile only when population and actual nearby resources can support specialization.
- Hunting behavior is still pending. A future sheep-harvesting goal must use actual vanilla animal drops, avoid animals clearly inside player-built pens, apply cooldowns and a small local wildlife quota, and never spawn replacement drops or animals.


## Housing inventory survey follow-up (2026-10-24)
- Added separate real-storage categories for planks, beds, and each of the 16 vanilla wool colours in `VisibleStorageScanner`. Mixed-colour wool is not incorrectly combined into a usable bed recipe stock.
- Added snapshot helpers for largest same-colour wool stock, total planks, and existing beds. These are survey primitives only; the live settlement housing loop still does not consume items, craft/place beds, or reserve stock.
- CI and in-game status for these latest commits must be checked separately. No in-game validation has been performed.


## Founding-role bootstrap update (2026-10-24)
- New, unassigned adult residents now try a bounded local bootstrap policy before UUID fallback: first observed founder is FARMER, the second complements a FARMER/GATHERER pair, and a third may become BUILDER if no nearby builder exists. It considers only already-loaded nearby villagers and never rewrites a saved role.
- This mitigates the founding-pair role lottery, but it is not yet a settlement-owned roster allocator. Existing villagers keep old roles; radius-based local grouping may not match eventual settlement boundaries. A role is still not proof that its task can execute, and the lumberjack first-storage deadlock remains unresolved.
- CI and in-game behavior for this change remain pending until verified.

## Real starter crafting and wooden tools (2026-10-24)

- Added `VillageBootstrapCraftingGoal`: eligible founding roles can convert a real carried vanilla log into four matching planks, then consume four real planks to craft one crafting table. No items are created without ingredients.
- Added `VillageWorkbenchPlacementGoal`: a carried table is placed only on revalidated, empty, dry, supported ground in an already-loaded chunk. Nearby-workbench scans now use a bounded 8-block horizontal radius and a 100-tick scan throttle, including negative results, to reduce repeated per-villager block scans.
- Added and registered `VillageToolCraftingGoal`: FARMER can craft/equip a wooden hoe; GATHERER/LUMBERJACK a wooden axe; MINER a wooden pickaxe. It consumes real planks and sticks and requires a nearby placed crafting table for new crafting. If a suitable tool already exists in inventory, it can equip that tool without crafting another.
- Stick crafting is a separate goal pass: two real planks are consumed to produce four sticks, then a later pass crafts the tool. Tool crafting currently supports the wooden starter tier only; stone/iron upgrades, recipe-registry integration, crop-task tool gating, and settlement-wide resource reservations remain unfinished.
- Runtime behavior and compilation are not yet confirmed by in-game testing. Treat this as implementation committed to the feature branch, not as a verified end-to-end bootstrap economy. Check GitHub Actions and then test a clean world with two newly spawned villagers.

## Tool-gated starter farmland (2026-10-24)

- Added and registered `VillageFarmlandPreparationGoal`. Only FARMER residents holding a usable hoe can convert existing dirt/grass into farmland; a water block must be nearby, the target and surrounding checks must remain in loaded chunks, and obvious beds/doors/workstations/storage/path blocks are protected.
- Each successful till consumes durability from the real equipped hoe and records the outcome in villager memory. It creates farmland only; it does not generate seeds, crops, water, or tools. Crop planting and harvesting remain separate behavior that must be tested against the vanilla farmer AI.
- Negative candidate scans are throttled to avoid repeating bounded terrain scans every AI tick. The search is intentionally local and does not load chunks.
- CI is the next gate. Even if compilation passes, the full founding sequence (wood -> planks -> table -> sticks/tool -> farmland -> seed/crop cycle) still requires a clean-world in-game test. Bed production/placement and animal husbandry remain incomplete.

