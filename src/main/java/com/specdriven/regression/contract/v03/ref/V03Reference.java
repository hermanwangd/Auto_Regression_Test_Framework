package com.specdriven.regression.contract.v03.ref;

public sealed interface V03Reference permits V03Reference.Literal, V03Reference.Artifact,
        V03Reference.Step, V03Reference.Generated, V03Reference.Environment {

    record Literal(Object value) implements V03Reference {
    }

    record Artifact(String root, String path, String jsonPointer) implements V03Reference {
    }

    record Step(String stepId, String outputPath, String jsonPointer) implements V03Reference {
        public Step(String stepId, String outputPath) {
            this(stepId, outputPath, "");
        }
    }

    record Generated(String target, String output, String jsonPointer) implements V03Reference {
        public Generated(String target, String output) {
            this(target, output, "");
        }
    }

    record Environment(String name) implements V03Reference {
    }
}
