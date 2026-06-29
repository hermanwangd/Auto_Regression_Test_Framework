package com.specdriven.regression.runtime;

import java.util.List;

public record GeneratedRuntimeContext(
        boolean ready,
        String profileId,
        String executionMode,
        String environmentRef,
        List<GeneratedRuntimeTarget> targets,
        List<GeneratedRuntimeGap> gaps) {

    public GeneratedRuntimeTarget target(String targetId, String runner) {
        List<GeneratedRuntimeTarget> matches = matchingTargets(targetId, runner);
        if (matches.size() == 1) {
            return matches.get(0);
        }
        return null;
    }

    public List<GeneratedRuntimeTarget> matchingTargets(String targetId, String runner) {
        if (!targetId.isBlank()) {
            List<GeneratedRuntimeTarget> matches = new java.util.ArrayList<>();
            for (GeneratedRuntimeTarget target : targets) {
                if (target.targetId().equals(targetId)
                        && (runner.isBlank() || target.runner().equals(runner))) {
                    matches.add(target);
                }
            }
            return List.copyOf(matches);
        }
        List<GeneratedRuntimeTarget> matches = new java.util.ArrayList<>();
        for (GeneratedRuntimeTarget target : targets) {
            if (!runner.isBlank() && target.runner().equals(runner)) {
                matches.add(target);
            }
        }
        if (!matches.isEmpty()) {
            return List.copyOf(matches);
        }
        return targetId.isBlank() && runner.isBlank() ? List.copyOf(targets) : List.of();
    }

    public List<String> dependencies(String targetId, String runner) {
        GeneratedRuntimeTarget target = target(targetId, runner);
        return target == null ? List.of() : target.dependencies();
    }

    public int order(String targetId, String runner) {
        GeneratedRuntimeTarget target = target(targetId, runner);
        return target == null ? Integer.MAX_VALUE : target.order();
    }
}
