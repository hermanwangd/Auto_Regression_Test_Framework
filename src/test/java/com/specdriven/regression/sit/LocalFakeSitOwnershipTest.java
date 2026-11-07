package com.specdriven.regression.sit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class LocalFakeSitOwnershipTest {

    private static final String ENVIRONMENT = "local-fake-environment";
    private static final String NAMESPACE = "orders";

    @Test
    void claimPublishesAnOwnedEmptyNamespaceAndReleaseAllowsAFreshToken() {
        var store = new LocalFakeSitOwnership();
        var initial = assertDoesNotThrow(() -> store.snapshot(ENVIRONMENT, NAMESPACE));
        assertThat(initial.state())
                .isEqualTo(LocalFakeSitOwnership.State.UNCLAIMED);

        var first = store.claim(ENVIRONMENT, NAMESPACE, "team-a", "run-a");
        var owned = store.snapshot(ENVIRONMENT, NAMESPACE);
        assertThat(owned.state()).isEqualTo(LocalFakeSitOwnership.State.OWNED);
        assertThat(owned.owner()).isEqualTo("team-a");
        assertThat(owned.runId()).isEqualTo("run-a");
        assertThat(owned.resources()).isEmpty();

        assertThat(store.finish(first, false).state()).isEqualTo(LocalFakeSitOwnership.State.RELEASED);
        var second = store.claim(ENVIRONMENT, NAMESPACE, "team-a", "run-a");
        assertThat(second).isNotSameAs(first);
        assertThat(store.snapshot(ENVIRONMENT, NAMESPACE).state())
                .isEqualTo(LocalFakeSitOwnership.State.OWNED);
    }

    @Test
    void createWriteAndDeletePreserveExactResourceOwnership() {
        var store = new LocalFakeSitOwnership();
        var token = store.claim(ENVIRONMENT, NAMESPACE, "team-a", "run-a");

        store.create(token, "fixture", "initial");
        assertThat(store.snapshot(ENVIRONMENT, NAMESPACE).resources()).containsExactlyEntriesOf(Map.of(
                "fixture", new LocalFakeSitOwnership.Resource("team-a", "run-a", "initial")));
        store.write(token, "fixture", "changed");
        assertThat(store.snapshot(ENVIRONMENT, NAMESPACE).resources().get("fixture").data()).isEqualTo("changed");
        assertThat(store.delete(token, "fixture")).isTrue();
        assertThat(store.delete(token, "fixture")).isFalse();
        assertThat(store.snapshot(ENVIRONMENT, NAMESPACE).resources()).isEmpty();
    }

    @Test
    void resourceCreationCannotOverwriteAndWriteCannotInventAResource() {
        var store = new LocalFakeSitOwnership();
        var token = store.claim(ENVIRONMENT, NAMESPACE, "team-a", "run-a");
        store.create(token, "fixture", "original");
        var before = store.snapshot(ENVIRONMENT, NAMESPACE);

        assertThatThrownBy(() -> store.create(token, "fixture", "replacement"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RESOURCE_EXISTS");
        assertThatThrownBy(() -> store.write(token, "unknown", "new"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RESOURCE_MISSING");
        assertThat(store.snapshot(ENVIRONMENT, NAMESPACE)).isEqualTo(before);
    }

    @Test
    void cleanupDeletesOnlyTheExactClaimAndLeavesOtherScopesUntouched() {
        var store = new LocalFakeSitOwnership();
        var mine = store.claim(ENVIRONMENT, NAMESPACE, "team-a", "run-a");
        var otherNamespace = store.claim(ENVIRONMENT, NAMESPACE + "-archive", "team-a", "run-a");
        var otherEnvironment = store.claim("other-environment", NAMESPACE, "team-a", "run-a");
        store.create(mine, "fixture", "mine");
        store.create(mine, "fixture-extra", "mine-too");
        store.create(otherNamespace, "fixture", "other-namespace");
        store.create(otherEnvironment, "fixture", "other-environment");
        var namespaceBefore = store.snapshot(ENVIRONMENT, NAMESPACE + "-archive");
        var environmentBefore = store.snapshot("other-environment", NAMESPACE);

        assertThat(store.cleanup(mine)).isEqualTo(2);
        assertThat(store.snapshot(ENVIRONMENT, NAMESPACE).resources()).isEmpty();
        assertThat(store.snapshot(ENVIRONMENT, NAMESPACE + "-archive")).isEqualTo(namespaceBefore);
        assertThat(store.snapshot("other-environment", NAMESPACE)).isEqualTo(environmentBefore);
        assertThat(store.finish(mine, false).state()).isEqualTo(LocalFakeSitOwnership.State.RELEASED);
    }

    @Test
    void finishWithoutCleanupRetainsResourcesAndBlocksEveryLaterClaim() {
        var store = new LocalFakeSitOwnership();
        var token = store.claim(ENVIRONMENT, NAMESPACE, "team-a", "run-a");
        store.create(token, "fixture", "retained");
        var resources = store.snapshot(ENVIRONMENT, NAMESPACE).resources();

        var retained = store.finish(token, false);
        assertThat(retained.state()).isEqualTo(LocalFakeSitOwnership.State.RETAINED);
        assertThat(retained.resources()).isEqualTo(resources);
        assertThat(retained.owner()).isEqualTo("team-a");
        assertThat(retained.runId()).isEqualTo("run-a");
        assertThatThrownBy(() -> store.claim(ENVIRONMENT, NAMESPACE, "team-b", "run-b"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("NAMESPACE_RETAINED");
        assertThatThrownBy(() -> store.claim(ENVIRONMENT, NAMESPACE, "team-a", "run-a"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("NAMESPACE_RETAINED");
        assertThat(store.snapshot(ENVIRONMENT, NAMESPACE)).isEqualTo(retained);
    }

    @Test
    void cleanupFailureRetainsEvenAnEmptyClaim() {
        var store = new LocalFakeSitOwnership();
        var token = store.claim(ENVIRONMENT, NAMESPACE, "team-a", "run-a");
        store.create(token, "fixture", "value");
        store.cleanup(token);

        var retained = store.finish(token, true);
        assertThat(retained.state()).isEqualTo(LocalFakeSitOwnership.State.RETAINED);
        assertThat(retained.resources()).isEmpty();
        assertThatThrownBy(() -> store.claim(ENVIRONMENT, NAMESPACE, "team-b", "run-b"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("NAMESPACE_RETAINED");
        assertThat(store.snapshot(ENVIRONMENT, NAMESPACE)).isEqualTo(retained);
    }

    @Test
    void cleanupFailureRetainsTheOriginalResourceIdentities() {
        var store = new LocalFakeSitOwnership();
        var token = store.claim(ENVIRONMENT, NAMESPACE, "team-a", "run-a");
        store.create(token, "fixture", "must-survive");
        var before = store.snapshot(ENVIRONMENT, NAMESPACE);

        var retained = store.finish(token, true);
        assertThat(retained.state()).isEqualTo(LocalFakeSitOwnership.State.RETAINED);
        assertThat(retained.owner()).isEqualTo(before.owner());
        assertThat(retained.runId()).isEqualTo(before.runId());
        assertThat(retained.resources()).isEqualTo(before.resources());
    }

    @ParameterizedTest(name = "retained claim rejects {0}")
    @MethodSource("mutatingOperations")
    void retainedTokensCannotResumeEffectsOrFinalization(String operation,
            BiConsumer<LocalFakeSitOwnership, LocalFakeSitOwnership.Token> effect) {
        var store = new LocalFakeSitOwnership();
        var token = store.claim(ENVIRONMENT, NAMESPACE, "team-a", "run-a");
        store.create(token, "fixture", "retained");
        var retained = store.finish(token, false);

        assertThatThrownBy(() -> effect.accept(store, token))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OWNERSHIP_LOST");
        assertThat(store.snapshot(ENVIRONMENT, NAMESPACE)).isEqualTo(retained);
    }

    @ParameterizedTest(name = "foreign store token rejects {0}")
    @MethodSource("mutatingOperations")
    void tokenFromAnotherStoreCannotAffectEvenAnIdenticalClaim(String operation,
            BiConsumer<LocalFakeSitOwnership, LocalFakeSitOwnership.Token> effect) {
        var store = new LocalFakeSitOwnership();
        var otherStore = new LocalFakeSitOwnership();
        var valid = store.claim(ENVIRONMENT, NAMESPACE, "team-a", "run-a");
        var foreign = otherStore.claim(ENVIRONMENT, NAMESPACE, "team-a", "run-a");
        store.create(valid, "fixture", "mine");
        var before = store.snapshot(ENVIRONMENT, NAMESPACE);

        assertThatThrownBy(() -> effect.accept(store, foreign))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OWNERSHIP_LOST");
        assertThat(store.snapshot(ENVIRONMENT, NAMESPACE)).isEqualTo(before);
    }

    @ParameterizedTest(name = "paused stale {0} preserves replacement {2}/{3}")
    @MethodSource("replacementOperations")
    @Timeout(10)
    void operationPausedBeforeItsAtomicCheckCannotChangeAReplacementClaim(String operation,
            BiConsumer<LocalFakeSitOwnership, LocalFakeSitOwnership.Token> effect,
            String replacementOwner, String replacementRun) throws Exception {
        var store = new LocalFakeSitOwnership();
        var old = store.claim(ENVIRONMENT, NAMESPACE, "team-a", "run-a");
        store.create(old, "fixture", "old-data");
        var pausedBeforeOperation = new CountDownLatch(1);
        var resumeOperation = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> staleEffect = executor.submit(() -> {
                // Pause before entering the real compare-and-effect operation, never between check and effect.
                pausedBeforeOperation.countDown();
                await(resumeOperation);
                assertThatThrownBy(() -> effect.accept(store, old))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("OWNERSHIP_LOST");
            });
            await(pausedBeforeOperation);
            store.cleanup(old);
            store.finish(old, false);
            // Cover both a new owner and reused owner/run labels, which require exact token comparison.
            var replacement = store.claim(ENVIRONMENT, NAMESPACE, replacementOwner, replacementRun);
            store.create(replacement, "fixture", "replacement-data");
            var before = store.snapshot(ENVIRONMENT, NAMESPACE);
            resumeOperation.countDown();
            staleEffect.get(5, TimeUnit.SECONDS);

            assertThat(store.snapshot(ENVIRONMENT, NAMESPACE)).isEqualTo(before);
            store.write(replacement, "fixture", "replacement-still-owns");
            assertThat(store.snapshot(ENVIRONMENT, NAMESPACE).resources().get("fixture").data())
                    .isEqualTo("replacement-still-owns");
        } finally {
            resumeOperation.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @Timeout(10)
    void simultaneousClaimsHaveExactlyOneWinnerWithoutRetry() throws Exception {
        var store = new LocalFakeSitOwnership();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> attempts = new ArrayList<>();
            for (String owner : List.of("team-a", "team-b")) {
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    try {
                        return store.claim(ENVIRONMENT, NAMESPACE, owner, "run-" + owner);
                    } catch (IllegalStateException blocked) {
                        return blocked;
                    }
                }));
            }
            await(ready);
            start.countDown();
            List<Object> outcomes = List.of(attempts.get(0).get(5, TimeUnit.SECONDS),
                    attempts.get(1).get(5, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(LocalFakeSitOwnership.Token.class::isInstance)).hasSize(1);
            assertThat(outcomes.stream().filter(IllegalStateException.class::isInstance)).hasSize(1);
            var blocked = (IllegalStateException) outcomes.stream()
                    .filter(IllegalStateException.class::isInstance).findFirst().orElseThrow();
            assertThat(blocked).hasMessageContaining("NAMESPACE_OWNED");
            var winner = (LocalFakeSitOwnership.Token) outcomes.stream()
                    .filter(LocalFakeSitOwnership.Token.class::isInstance).findFirst().orElseThrow();
            store.create(winner, "fixture", "winner-data");
            assertThat(store.snapshot(ENVIRONMENT, NAMESPACE).resources()).hasSize(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @Timeout(10)
    void simultaneousUnrelatedNamespacesBothMakeProgress() throws Exception {
        var store = new LocalFakeSitOwnership();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> attempts = new ArrayList<>();
            for (String namespace : List.of("orders", "payments")) {
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    var token = store.claim(ENVIRONMENT, namespace, "team-a", "run-a");
                    store.create(token, "fixture", namespace);
                }));
            }
            await(ready);
            start.countDown();
            for (var attempt : attempts) attempt.get(5, TimeUnit.SECONDS);
            assertThat(store.snapshot(ENVIRONMENT, "orders").resources().get("fixture").data())
                    .isEqualTo("orders");
            assertThat(store.snapshot(ENVIRONMENT, "payments").resources().get("fixture").data())
                    .isEqualTo("payments");
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void snapshotsCannotMutateOrTrackLaterStoreChanges() {
        var store = new LocalFakeSitOwnership();
        var token = store.claim(ENVIRONMENT, NAMESPACE, "team-a", "run-a");
        store.create(token, "fixture", "initial");
        var snapshot = store.snapshot(ENVIRONMENT, NAMESPACE);

        assertThatThrownBy(() -> snapshot.resources().clear()).isInstanceOf(UnsupportedOperationException.class);
        store.write(token, "fixture", "later");
        assertThat(snapshot.resources().get("fixture").data()).isEqualTo("initial");
        assertThat(token.toString()).doesNotContain(ENVIRONMENT, NAMESPACE, "team-a", "run-a");
    }

    @Test
    void readOnlyObservationRejectsOwnershipLostBeforeTheReadWithoutChangingResources() {
        var store = new LocalFakeSitOwnership();
        var old = store.claim(ENVIRONMENT, NAMESPACE, "team-a", "run-a");
        store.create(old, "fixture", "original");
        var original = store.snapshot(ENVIRONMENT, NAMESPACE);
        assertDoesNotThrow(() -> store.assertCurrent(old));
        assertThat(store.snapshot(ENVIRONMENT, NAMESPACE)).isEqualTo(original);

        store.cleanup(old);
        store.finish(old, false);
        var replacement = store.claim(ENVIRONMENT, NAMESPACE, "team-a", "run-a");
        store.create(replacement, "fixture", "replacement");
        var beforeRead = store.snapshot(ENVIRONMENT, NAMESPACE);
        assertThatThrownBy(() -> store.assertCurrent(old))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OWNERSHIP_LOST");
        assertDoesNotThrow(() -> store.assertCurrent(replacement));
        assertThat(store.snapshot(ENVIRONMENT, NAMESPACE)).isEqualTo(beforeRead);

        var retained = store.finish(replacement, false);
        assertThatThrownBy(() -> store.assertCurrent(replacement))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OWNERSHIP_LOST");
        assertThat(store.snapshot(ENVIRONMENT, NAMESPACE)).isEqualTo(retained);
    }

    static Stream<Arguments> mutatingOperations() {
        return Stream.of(
                operation("create", (store, token) -> store.create(token, "new-fixture", "old-data")),
                operation("write", (store, token) -> store.write(token, "fixture", "old-data")),
                operation("delete", (store, token) -> store.delete(token, "fixture")),
                operation("cleanup", (store, token) -> store.cleanup(token)),
                operation("finish", (store, token) -> store.finish(token, false)),
                operation("failed cleanup finalizer", (store, token) -> store.finish(token, true)));
    }

    static Stream<Arguments> replacementOperations() {
        return mutatingOperations().flatMap(operation -> {
            Object[] arguments = operation.get();
            return Stream.of(Arguments.of(arguments[0], arguments[1], "team-b", "run-b"),
                    Arguments.of(arguments[0], arguments[1], "team-a", "run-a"));
        });
    }

    private static Arguments operation(String name,
            BiConsumer<LocalFakeSitOwnership, LocalFakeSitOwnership.Token> effect) {
        return Arguments.of(name, effect);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out at deterministic test barrier");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted at deterministic test barrier", interrupted);
        }
    }
}
