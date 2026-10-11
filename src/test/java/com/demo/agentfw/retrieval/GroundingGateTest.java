package com.demo.agentfw.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GroundingGateTest {

    @Test
    void scoreAtOrAboveThresholdIsGrounded() {
        assertThat(GroundingGate.decide(3.2, 2.0)).isEqualTo(GroundingDecision.PASSED_FLOOR);
        assertThat(GroundingGate.decide(2.0, 2.0)).isEqualTo(GroundingDecision.PASSED_FLOOR);   // boundary is inclusive
    }

    @Test
    void scoreBelowThresholdMeansNoRelevantContext() {
        assertThat(GroundingGate.decide(1.99, 2.0)).isEqualTo(GroundingDecision.NO_RELEVANT_CONTEXT);
        assertThat(GroundingGate.decide(-8.0, -3.0)).isEqualTo(GroundingDecision.NO_RELEVANT_CONTEXT);   // logits can be negative
    }

    @Test
    void noCandidatesMeansNoRelevantContext() {
        assertThat(GroundingGate.decide(null, 2.0)).isEqualTo(GroundingDecision.NO_RELEVANT_CONTEXT);
    }

    @Test
    void withoutAThresholdNoDecisionIsMade() {
        assertThat(GroundingGate.decide(9.0, null)).isEqualTo(GroundingDecision.NOT_CALIBRATED);
        assertThat(GroundingGate.decide(null, null)).isEqualTo(GroundingDecision.NOT_CALIBRATED);
    }
}
