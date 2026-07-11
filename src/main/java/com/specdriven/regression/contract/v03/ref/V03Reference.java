package com.specdriven.regression.contract.v03.ref;

public sealed interface V03Reference permits V03Reference.Literal, V03Reference.Artifact,
        V03Reference.Step, V03Reference.Generated, V03Reference.Environment {

    record Literal(Object value) implements V03Reference {
    }

    record Artifact(String root, String path, String jsonPointer) implements V03Reference {
    }

    record Step(String stepId, String outputPath) implements V03Reference {
    }

    record Generated(String target, String output) implements V03Reference {
    }

    record Environment(String name) implements V03Reference {
    }
}
