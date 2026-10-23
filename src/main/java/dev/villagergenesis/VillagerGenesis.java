package dev.villagergenesis;

import dev.villagergenesis.memory.VillagerMemoryService;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(VillagerGenesis.MOD_ID)
public final class VillagerGenesis {
    public static final String MOD_ID = "villager_genesis";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public VillagerGenesis(IEventBus modEventBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.addListener(VillagerMemoryService::onEntityTickPost);
        LOGGER.info("Villager Genesis initialized. Observation memory is enabled; vanilla AI remains in control.");
    }
}
