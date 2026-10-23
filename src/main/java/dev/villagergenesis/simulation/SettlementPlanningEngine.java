package dev.villagergenesis.simulation;

import java.util.Comparator;
import java.util.List;

/** Pure-data settlement specialization. It only reads saved node estimates and graph edges. */
public final class SettlementPlanningEngine {
    public enum Role { BALANCED, AGRICULTURE, FORESTRY, STONEWORK, TRADE }
    public record Plan(Role role, int score, String reason) {}
    private SettlementPlanningEngine() {}

    public static Plan plan(SettlementNodeGraph.Node node, List<SettlementNodeGraph.Edge> edges) {
        if (node == null) throw new IllegalArgumentException("Settlement node is required.");
        if (edges == null) edges = List.of();
        String biome = node.biomeId();
        if (biome == null || biome.equals("unobserved") || biome.equals("unknown")) {
            return new Plan(Role.BALANCED, 0,
                    "Terrain is unobserved; keep a balanced provisional plan until safe evidence exists.");
        }
        int food=node.foodPotential(), wood=node.timberPotential(), stone=node.stonePotential();
        int terrain=node.terrainScore();
        int balanced=(food+wood+stone)/3+terrain/2;
        int farm=food*2+terrain/2-(wood+stone)/4;
        int forestry=wood*2+terrain/2-(food+stone)/4;
        int quarry=stone*2+terrain/2-(food+wood)/4;
        int neighbours=0, tradeTotal=0;
        for(SettlementNodeGraph.Edge edge:edges) if(edge.a().equals(node.id())||edge.b().equals(node.id())) {
            neighbours++; tradeTotal+=edge.tradeScore();
        }
        int averageTrade=neighbours==0?0:tradeTotal/neighbours;
        int trade=averageTrade+neighbours*12+terrain/4;
        List<Plan> options=List.of(
            new Plan(Role.BALANCED,balanced,"Maintain a mixed resource base and reduce dependence on one estimate."),
            new Plan(Role.AGRICULTURE,farm,"Food potential is the strongest estimated advantage."),
            new Plan(Role.FORESTRY,forestry,"Timber potential is the strongest estimated advantage."),
            new Plan(Role.STONEWORK,quarry,"Stone potential is the strongest estimated advantage."),
            new Plan(Role.TRADE,trade,"Existing abstract links may support exchange between complementary settlements.")
        );
        Plan best=options.stream().max(Comparator.comparingInt(Plan::score)
                .thenComparing(p->p.role().name())).orElseThrow();
        if(best.role()==Role.TRADE&&(neighbours<2||averageTrade<30)) {
            best=options.stream().filter(p->p.role()!=Role.TRADE)
                    .max(Comparator.comparingInt(Plan::score).thenComparing(p->p.role().name())).orElseThrow();
        }
        return best;
    }
}