package com.example.harness.multiagent.expert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolPolicyTest {

    @Test
    void memoryConsumerCanReadButEveryWriteIsDeniedAndCounted() {
        ToolPolicy policy = ToolPolicy.readOnlyAllowlist();

        assertEquals(ToolPolicy.Decision.ALLOW, policy.decide("read"));
        assertEquals(ToolPolicy.Decision.DENY, policy.decide("write"));
        assertEquals(ToolPolicy.Decision.DENY, policy.decide("edit"));
        assertEquals(1, policy.allowances());
        assertEquals(2, policy.denials());
    }
}
