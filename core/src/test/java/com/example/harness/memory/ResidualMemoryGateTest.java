package com.example.harness.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResidualMemoryGateTest {

    private static final long MAINTENANCE_INTERVAL_MS = 60_000;

    private final ResidualMemoryGate gate = new ResidualMemoryGate(MAINTENANCE_INTERVAL_MS);

    @Test
    void rejectsStateThatCanBeObservedAgain() {
        ResidualMemoryGate.Decision decision = gate.evaluate(true, 24 * 3600 * 1000L);

        assertFalse(decision.accepted());
        assertTrue(decision.reason().contains("重新观测"));
    }

    @Test
    void rejectsKnowledgeWhoseHalfLifeIsBelowMaintenanceInterval() {
        ResidualMemoryGate.Decision decision = gate.evaluate(false,
                MAINTENANCE_INTERVAL_MS - 1);

        assertFalse(decision.accepted());
        assertTrue(decision.reason().contains("半衰期"));
    }

    @Test
    void acceptsResidualKnowledgeAtMaintenanceBoundary() {
        ResidualMemoryGate.Decision decision = gate.evaluate(false, MAINTENANCE_INTERVAL_MS);

        assertTrue(decision.accepted());
        assertTrue(decision.reason().contains("残差"));
    }
}
