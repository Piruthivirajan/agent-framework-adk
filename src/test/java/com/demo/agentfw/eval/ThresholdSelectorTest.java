package com.demo.agentfw.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class ThresholdSelectorTest {

    @Test
    void separableGroupsGetTheMidpointOfTheGap() {
        ThresholdSelector.Selection s = ThresholdSelector.choose(
                List.of(4.0, 6.5, 9.0, 3.0),          // in-corpus
                List.of(-7.0, -2.0, 0.5, -9.0));      // out-of-corpus

        assertThat(s.separable()).isTrue();
        assertThat(s.lowestInCorpus()).isEqualTo(3.0);
        assertThat(s.highestOutOfCorpus()).isEqualTo(0.5);
        assertThat(s.chosen().threshold()).isCloseTo(1.75, offset(1e-9));   // midpoint of 0.5 and 3.0
        assertThat(s.chosen().balancedAccuracy()).isEqualTo(1.0);
        assertThat(s.chosen().falseNegatives()).isZero();
        assertThat(s.chosen().falsePositives()).isZero();
    }

    @Test
    void theWidestGapWinsAmongPerfectThresholds() {
        // perfect separation is possible between 1 and 2 (gap 1) only; other candidates are not perfect
        ThresholdSelector.Selection s = ThresholdSelector.choose(List.of(2.0, 3.0), List.of(0.0, 1.0));

        assertThat(s.chosen().threshold()).isCloseTo(1.5, offset(1e-9));
    }

    @Test
    void overlappingGroupsPickTheBestBalancedAccuracyAndSayNotSeparable() {
        // in-corpus: 1, 5, 6, 7 ; out-of-corpus: -3, 0, 2, 5.5
        ThresholdSelector.Selection s = ThresholdSelector.choose(
                List.of(1.0, 5.0, 6.0, 7.0), List.of(-3.0, 0.0, 2.0, 5.5));

        assertThat(s.separable()).isFalse();
        ThresholdSelector.Point c = s.chosen();
        // threshold 3.5: accepts 5,6,7 (3 of 4) and rejects -3,0,2 (3 of 4) -> 0.75
        // threshold 5.25: accepts 6,7 + out 5.5 ... worse; threshold 0.5: accepts 1,5,6,7 (4/4), rejects -3,0 (2/4) -> 0.75
        assertThat(c.balancedAccuracy()).isCloseTo(0.75, offset(1e-9));
        assertThat(c.truePositives() + c.falseNegatives()).isEqualTo(4);
        assertThat(c.trueNegatives() + c.falsePositives()).isEqualTo(4);
    }

    @Test
    void evaluateCountsAcceptedAndRejected() {
        ThresholdSelector.Point p = ThresholdSelector.evaluate(2.0, List.of(1.0, 3.0, 4.0), List.of(0.0, 2.0), 0);

        assertThat(p.truePositives()).isEqualTo(2);
        assertThat(p.falseNegatives()).isEqualTo(1);
        assertThat(p.falsePositives()).isEqualTo(1);   // 2.0 is accepted: the boundary is inclusive
        assertThat(p.trueNegatives()).isEqualTo(1);
    }

    @Test
    void needsBothGroups() {
        assertThatThrownBy(() -> ThresholdSelector.choose(List.of(), List.of(1.0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ThresholdSelector.choose(List.of(1.0), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
