# Development Status: Settlement Autonomy

Last updated: 2026-10-23
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
- Roles include FARMER, BUILDER, GATHERER, CRAFTER, MINER, LUMBERJACK, MAYOR, MASON, GUARD, SCOUT, CARETAKER. Assignment is still UUID-based, not demand-based staffing.
- Visible storage scanning covers ordinary chests, trapped chests, and barrels in loaded chunks, with bounded radius (maximum 24).
- BUILDER shelter construction consumes 33 real oak planks; MASON shelter construction consumes 33 real cobblestone. Placement is validated and rollback attempted on failure.
- Storage expansion is limited to CRAFTER, CARETAKER, MAYOR.
- Autonomy service installs equipment, storage/shelter construction, exploration, guard combat, and resident threat-avoidance goals per live villager instance.
- Equipment goal takes real equipment from storage. Guard combat has melee and a committed ranged implementation using real stored ammunition; see validation caveats below.
- Guard combat prioritizes hostile monsters targeting villagers and records successful attack outcomes.
- New ordinary-resident escape goal avoids nearby hostile monsters without attacking: checks danger periodically, picks a clear escape destination with solid footing, checks all relevant chunks are already loaded, and records the outcome. Successful escapes teach only hostile-awareness knowledge.

## Latest validation
- Commit 96351d82c1b5722f68451d13c4417e7f8f7670fd: guard ranged code; Build run #281 / 40005431735 passed.
- Commit eb738f8839d9f027a513004208d7023038e18c66: fallback to movement/close range when no real ammunition is available; Build run #284 / 40005548168 passed.
- Commit 376fdea7b04a9e7c1acb4b2f763471b5ad32ad9c: added VillageResidentFleeGoal.
- Commit 4ff7c281beb599661946c217869729c86f8b1cd6: installed resident escape behavior in VillageAutonomyService.
- Commit 9b459216e980f3eb0a8a9ec71c502ee78e57c79a: records hostile-awareness knowledge only after a successful escape.
- The latest branch build for the resident escape integration was still running at last check (Build run #290 / 40005782672); a PR-triggered build was also running. Re-check Actions before treating the new goal as CI-passed.
- No guard combat or resident escape behavior has been tested in a running Minecraft world.

## Unfinished work that must not be forgotten
1. Re-check CI for the latest resident-escape commit and fix any compile/test failures.
2. In-game test escape destination selection, navigation, fleeing from a monster, and goal interruption alongside vanilla behavior.
3. Add deterministic tests where feasible for escape destination safety and state transitions.
4. Implement real shield blocking only after API/behavior validation; merely equipping a shield is not blocking.
5. Implement durability-aware equipment maintenance and replacement only from real available supplies.
6. Coordinate guards so protection is distributed rather than every guard pursuing the same target; define defense radius and posting behavior.
7. Improve after-event learning based on observed outcomes, not arbitrary skill grants.
8. Replace static UUID role assignment with staffing based on population, skills, vanilla profession, and settlement needs.
9. Implement real gathering, crafting, logistics, mentorship/knowledge transfer, and career mobility without duplicating vanilla systems.
10. Settlement simulation (food, housing, population, territory, diplomacy, etc.) remains incomplete; do not claim otherwise based on metadata alone.
11. Regression-check scan frequency, cooldowns, goal interruption, inventory races, persistence, loaded-chunk safety, and player-build protection.

## Next execution order
1. Check Actions for the latest resident-escape commit; fix any build/test failures.
2. Review goal priority interactions and test escape behavior in Minecraft.
3. Add deterministic tests for bounded destination selection and state transitions.
4. Continue settlement staffing, resource logistics, learning, and simulation.
5. Keep CI and in-game validation status separate.

## Status language
- Committed: source is on the branch.
- CI passed: GitHub Actions completed successfully for the relevant commit.
- In-game verified: tested in Minecraft; never infer this from CI.
- Not implemented: keep listed until code and validation exist.
