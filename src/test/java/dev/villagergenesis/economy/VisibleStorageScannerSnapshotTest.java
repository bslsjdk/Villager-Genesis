package dev.villagergenesis.economy;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VisibleStorageScannerSnapshotTest {
    @Test
    void compatibleWoolUsesLargestSingleColourGroupRatherThanMixedTotal() {
        EnumMap<VisibleStorageScanner.Resource, Integer> counts =
                new EnumMap<>(VisibleStorageScanner.Resource.class);
        counts.put(VisibleStorageScanner.Resource.WOOL_WHITE, 2);
        counts.put(VisibleStorageScanner.Resource.WOOL_RED, 2);
        counts.put(VisibleStorageScanner.Resource.WOOL_BLUE, 1);

        var snapshot = new VisibleStorageScanner.Snapshot(0, 64, 0, 8,
                List.of(), counts, 0L);

        assertEquals(2, snapshot.compatibleWoolItems());
    }

    @Test
    void exposesPlanksAndExistingBedsAsSeparateStocks() {
        EnumMap<VisibleStorageScanner.Resource, Integer> counts =
                new EnumMap<>(VisibleStorageScanner.Resource.class);
        counts.put(VisibleStorageScanner.Resource.PLANKS, 17);
        counts.put(VisibleStorageScanner.Resource.BEDS, 3);
        counts.put(VisibleStorageScanner.Resource.WOOD, 29);

        var snapshot = new VisibleStorageScanner.Snapshot(0, 64, 0, 8,
                List.of(), counts, 0L);

        assertEquals(17, snapshot.availablePlanks());
        assertEquals(3, snapshot.availableBeds());
        assertEquals(29, snapshot.count(VisibleStorageScanner.Resource.WOOD));
    }
}
