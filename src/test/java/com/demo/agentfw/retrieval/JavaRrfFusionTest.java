package com.demo.agentfw.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class JavaRrfFusionTest {

    @Test
    void chunkRankedFirstInBothListsScoresTwoOverSixtyOne() {
        // the number the Day 1 spike printed for $rankFusion: 1/(60+1) + 1/(60+1)
        List<JavaRrfFusion.Scored> out = JavaRrfFusion.fuse(
                Map.of("vector", List.of("d1", "d2", "d3"), "text", List.of("d1", "d2")),
                Map.of("vector", 1.0, "text", 1.0), 60);

        assertThat(out).extracting(JavaRrfFusion.Scored::id).containsExactly("d1", "d2", "d3");
        assertThat(out.get(0).score()).isCloseTo(2.0 / 61, offset(1e-12));
        assertThat(out.get(1).score()).isCloseTo(2.0 / 62, offset(1e-12));
        assertThat(out.get(2).score()).isCloseTo(1.0 / 63, offset(1e-12));   // only in the vector list, rank 3
    }

    @Test
    void agreementBeatsASingleHighRank() {
        // "b" is third in both lists, "a" is first in only one: RRF prefers the consistent chunk
        List<JavaRrfFusion.Scored> out = JavaRrfFusion.fuse(
                Map.of("vector", List.of("a", "x", "b"), "text", List.of("y", "z", "b")),
                Map.of(), 60);

        // b: 2/63 = 0.03175 ; a: 1/61 = 0.01639 ; y: 1/61
        assertThat(out.get(0).id()).isEqualTo("b");
    }

    @Test
    void weightsScaleEachPipelineContribution() {
        List<JavaRrfFusion.Scored> out = JavaRrfFusion.fuse(
                Map.of("vector", List.of("v"), "text", List.of("t")),
                Map.of("vector", 1.0, "text", 3.0), 60);

        assertThat(out.get(0).id()).isEqualTo("t");
        assertThat(out.get(0).score()).isCloseTo(3.0 / 61, offset(1e-12));
        assertThat(out.get(1).score()).isCloseTo(1.0 / 61, offset(1e-12));
    }

    @Test
    void tiesAreBrokenByIdSoTheOutputIsDeterministic() {
        List<JavaRrfFusion.Scored> out = JavaRrfFusion.fuse(
                Map.of("vector", List.of("b"), "text", List.of("a")), Map.of(), 60);

        assertThat(out).extracting(JavaRrfFusion.Scored::id).containsExactly("a", "b");
    }

    @Test
    void emptyInputGivesEmptyOutput() {
        assertThat(JavaRrfFusion.fuse(Map.of("vector", List.of(), "text", List.of()), Map.of(), 60)).isEmpty();
    }
}
