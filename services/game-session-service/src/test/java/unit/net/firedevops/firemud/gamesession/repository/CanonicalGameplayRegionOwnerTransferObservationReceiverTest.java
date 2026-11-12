package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Real RSA verification with controlled local signing custody, not deployed attestor proof. */
class CanonicalGameplayRegionOwnerTransferObservationReceiverTest {
  private static final String SCHEMA = "firemud.gs.protected-region-owner-transfer-read/v1";
  private static final String KEY_ID = "controlled-transfer-key";
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final String OTHER_DIGEST = "sha256:" + "e".repeat(64);
  private static final long NOW = 1_794_355_200_000L;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final KeyPair KEYS = controlledKeys();

  @Test
  void verifiesExactFrozenItemTwiceWithRealSignatureAndCapsAtOperationDeadline() {
    var fixture = new Fixture();
    var proof = fixture.observe(NOW + 500L);

    assertThat(proof.ownerKind())
        .isEqualTo(CanonicalGameplayProtectedIncarnationReceiver.OwnerKind.REGION_OWNER_TRANSFER);
    assertThat(proof.regionOwnerTransferContext()).isSameAs(fixture.original);
    assertThat(proof.operationId()).isEqualTo(fixture.original.transferId());
    assertThat(proof.requestId()).isEqualTo(fixture.original.requestId());
    assertThat(proof.accountId()).isEqualTo(fixture.original.accountId());
    assertThat(proof.targetOrdinal()).isEqualTo(fixture.original.targetOrdinal());
    assertThat(proof.tenantId()).isEqualTo(fixture.original.tenantId());
    assertThat(proof.playableStateNamespaceId())
        .isEqualTo(fixture.original.playableStateNamespaceId());
    assertThat(proof.tenantGameplayTag()).isEqualTo(fixture.original.tenantGameplayTag());
    assertThat(proof.issuedAtEpochMillis()).isEqualTo(NOW);
    assertThat(proof.observedAtEpochMillis()).isEqualTo(NOW);
    assertThat(proof.expiresAtEpochMillis()).isEqualTo(NOW + 500L);
    assertThat(proof.observationNonce()).matches("[A-Za-z0-9_-]{43}");
    assertThat(proof.payloadDigest()).matches("sha256:[0-9a-f]{64}");
    assertThat(proof.incarnationDigest()).matches("sha256:[0-9a-f]{64}");
    assertThat(fixture.sourceReads).hasValue(2);
    assertThat(fixture.channelCalls).hasValue(1);
    assertThat(proof.operationFence()).isNull();
    assertThat(proof.plannedAccountAdmissionFence()).isNull();
    assertThat(proof.originalCallerContextId()).isNull();
  }

  @Test
  void longerOperationStillUsesConfiguredObservationTtlAndFreshNonce() {
    var fixture = new Fixture();
    var first = fixture.observe(NOW + 60_000L);
    var second = fixture.observe(NOW + 60_000L);
    assertThat(first.expiresAtEpochMillis()).isEqualTo(NOW + 30_000L);
    assertThat(second.observationNonce()).isNotEqualTo(first.observationNonce());
    assertThat(second.incarnationDigest()).isEqualTo(first.incarnationDigest());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "firemud.gs.protected-incarnation-read/v1",
        "firemud.gs.protected-initial-account-barrier-read/v1"
      })
  void accountAndInitialSignedSchemasCannotBeReplayedAsTransfer(String schema) {
    var fixture = new Fixture();
    fixture.mutation = root -> root.put("schema", schema);
    assertRejected(fixture);
    assertThat(fixture.sourceReads).hasValue(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ACCOUNT_FENCE", "INITIAL_ACCOUNT_BARRIER"})
  void accountAndInitialOwnerProofsCannotBeReplayedAsTransfer(String kind) {
    var fixture = new Fixture();
    fixture.mutation = root -> object(root, "owner").put("ownerKind", kind);
    assertRejected(fixture);
  }

  @Test
  void transferSignedPurposeCannotBeReplayedThroughAccountReceiver() {
    var fixture = new Fixture();
    var repository = mock(CanonicalGameplayAccountAdmissionBarrierRepository.class);
    var intent = mock(CanonicalGameplayAccountAdmissionFenceIntent.class);
    var context = fixture.original;
    when(repository.readCurrentFenceIntent(context.transferId())).thenReturn(intent);
    when(intent.operationId()).thenReturn(context.transferId());
    when(intent.requestId()).thenReturn(context.requestId());
    when(intent.accountId()).thenReturn(context.accountId());
    when(intent.operationFence()).thenReturn(BigInteger.ONE);
    when(intent.plannedAccountAdmissionFence()).thenReturn(UUID.randomUUID());
    when(intent.inventorySnapshotRevision()).thenReturn(BigInteger.ONE);
    when(intent.cohortDigest()).thenReturn(DIGEST);
    when(intent.barrierTargetDigest()).thenReturn(DIGEST);
    when(intent.state())
        .thenReturn(CanonicalGameplayAccountAdmissionFenceIntent.State.ACQUISITION_INTENT);
    when(intent.barrierTargets())
        .thenReturn(
            List.of(
                CanonicalGameplayAccountAdmissionFenceIntent.barrierTarget(
                    context.targetOrdinal(),
                    context.tenantId(),
                    context.playableStateNamespaceId())));
    when(fixture.assignment.isBindingTransition()).thenReturn(false);
    when(fixture.assignment.admissionFenceIntentOperationId()).thenReturn(context.transferId());
    when(fixture.assignment.barrierTargetOrdinal()).thenReturn(context.targetOrdinal());
    when(fixture.tags.assignmentForBarrierTarget(context.transferId(), context.targetOrdinal()))
        .thenReturn(fixture.assignment);
    var receiver =
        new CanonicalGameplayProtectedIncarnationReceiver(
            repository, fixture.tags, fixture::signed, fixture.trust(), fixture.clock);
    assertThatThrownBy(
            () -> receiver.observeForFenceTarget(context.transferId(), context.targetOrdinal()))
        .isInstanceOf(
            CanonicalGameplayProtectedIncarnationReceiver.ObservationRejectedException.class);
    assertThat(fixture.channelCalls).hasValue(1);
  }

  @Test
  void transferSignedPurposeCannotBeReplayedThroughInitialReceiver() {
    var fixture = new Fixture();
    var repository = mock(CanonicalGameplayAccountInitialBarrierInitializerRepository.class);
    var context = fixture.original;
    var initial =
        new CanonicalGameplayAccountInitialBarrierInitializerRepository.InitialObservationContext(
            UUID.randomUUID(),
            context.requestId(),
            UUID.randomUUID(),
            1L,
            context.accountId(),
            context.tenantId(),
            context.playableStateNamespaceId(),
            "NO_ACTIVE_ACCOUNT_WIDE_FLOW",
            BigInteger.ZERO,
            BigInteger.ONE,
            DIGEST,
            DIGEST,
            DIGEST);
    when(repository.readCurrentObservationContext(context.requestId())).thenReturn(initial);
    when(fixture.assignment.isBindingTransition()).thenReturn(false);
    when(fixture.assignment.isInitialBarrierTarget()).thenReturn(true);
    when(fixture.assignment.initialBarrierInitializerId()).thenReturn(initial.initializerId());
    when(fixture.assignment.initialBarrierOriginalRequestId()).thenReturn(context.requestId());
    when(fixture.tags.assignmentForInitialBarrierTarget(context.requestId()))
        .thenReturn(fixture.assignment);
    var receiver =
        new CanonicalGameplayProtectedIncarnationReceiver(
            repository, fixture.tags, fixture::signed, fixture.trust(), fixture.clock);
    assertThatThrownBy(() -> receiver.observeForInitialAccountBarrier(context.requestId()))
        .isInstanceOf(
            CanonicalGameplayProtectedIncarnationReceiver.ObservationRejectedException.class);
    assertThat(fixture.channelCalls).hasValue(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "transferId",
        "transferIntentDigest",
        "frozenSourceDigest",
        "oldRegionOwnerId",
        "oldRegionOwnerFence",
        "gameInstanceId",
        "regionId",
        "regionEpoch",
        "transitionId",
        "requestId",
        "accountId",
        "executionDigest",
        "candidateDigest",
        "controlState",
        "completionDigest"
      })
  void everySignedTransferOwnerTupleMutationRefuses(String field) {
    var fixture = new Fixture();
    fixture.mutation = root -> mutate(object(root, "owner"), field);
    assertRejected(fixture);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "targetOrdinal",
        "tenantId",
        "playableStateNamespaceId",
        "tenantGameplayTag",
        "workloadNamespace",
        "allocationVersion"
      })
  void everySignedTargetTupleMutationRefuses(String field) {
    var fixture = new Fixture();
    fixture.mutation = root -> mutate(object(root, "target"), field);
    assertRejected(fixture);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "deploymentIdentity", "deploymentGeneration", "canonicalNodeSet", "redisBuildIdentity",
        "luaScriptRegistryVersion", "luaScriptRegistryDigest", "startupState", "bootGeneration",
        "startupGeneration", "storageGeneration", "aofGeneration"
      })
  void everySignedDeploymentIncarnationMutationRefuses(String field) {
    var fixture = new Fixture();
    fixture.mutation = root -> mutate(object(root, "targetDeploymentEvidence"), field);
    assertRejected(fixture);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "attestorBinding",
        "attestorKeyId",
        "acceptedAttestationDigest",
        "observationNonce",
        "coordOpsRoAclDigest",
        "coordRedisConfigObservedDigest"
      })
  void signedTrustAndCorrelationMutationRefuses(String field) {
    var fixture = new Fixture();
    fixture.mutation = root -> mutate(root, field);
    assertRejected(fixture);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "isBindingTransition",
        "transitionId",
        "accountId",
        "tenantId",
        "gameInstanceId",
        "playableStateNamespaceId",
        "tenantGameplayTag",
        "workloadNamespace",
        "allocationVersion"
      })
  void independentlyResolvedAssignmentMustMatchBeforeRemoteCall(String field) {
    var fixture = new Fixture();
    fixture.breakAssignment(field);
    assertRejected(fixture);
    assertThat(fixture.channelCalls).hasValue(0);
  }

  @Test
  void assignmentMustStillMatchAfterSignatureVerification() {
    var fixture = new Fixture();
    fixture.afterSign = () -> fixture.breakAssignment("allocationVersion");
    assertRejected(fixture);
    assertThat(fixture.sourceReads).hasValue(2);
    assertThat(fixture.channelCalls).hasValue(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "transferIntentDigest",
        "frozenSourceDigest",
        "oldRegionOwnerId",
        "oldRegionOwnerFence",
        "regionEpoch",
        "executionDigest",
        "candidateDigest"
      })
  void frozenSourceMutationDuringSignedReadRefuses(String field) {
    var fixture = new Fixture();
    fixture.afterSign = () -> fixture.current.set(changed(fixture.original, field));
    assertRejected(fixture);
    assertThat(fixture.sourceReads).hasValue(2);
  }

  @Test
  void missingOrNoLongerFrozenSourceAfterSignatureCannotProduceProof() {
    var fixture = new Fixture();
    fixture.afterSign = () -> fixture.current.set(null);
    assertRejected(fixture);
    assertThat(fixture.sourceReads).hasValue(2);
  }

  @Test
  void unavailableCurrentSourceAfterSignatureCannotProduceProof() {
    var fixture = new Fixture();
    fixture.afterSign = () -> fixture.sourceUnavailable = true;
    assertThatThrownBy(() -> fixture.observe(NOW + 1_000L))
        .isInstanceOf(
            CanonicalGameplayProtectedIncarnationReceiver.ObservationUnavailableException.class);
    assertThat(fixture.sourceReads).hasValue(2);
  }

  @Test
  void sealedCompletedContextProducesOnlyItsExactReadOnlyCurrentnessObservation() {
    var fixture = new Fixture("game-session-original", "COMPLETED", DIGEST);
    var proof = fixture.observe(NOW + 1_000L);
    assertThat(proof.regionOwnerTransferContext()).isSameAs(fixture.original);
    assertThat(proof.regionOwnerTransferContext().controlState()).isEqualTo("COMPLETED");
    assertThat(proof.regionOwnerTransferContext().completionDigest()).isEqualTo(DIGEST);
    assertThat(fixture.sourceReads).hasValue(2);
  }

  @Test
  void completionRacingSignatureVerificationCannotReuseFrozenContextProof() {
    var fixture = new Fixture();
    fixture.afterSign = () -> fixture.current.set(withPhase(fixture.original, "COMPLETED", DIGEST));
    assertRejected(fixture);
    assertThat(fixture.sourceReads).hasValue(2);
  }

  @Test
  void changedSealedCompletionDuringObservationRefuses() {
    var fixture = new Fixture("game-session-original", "COMPLETED", DIGEST);
    fixture.afterSign =
        () -> fixture.current.set(withPhase(fixture.original, "COMPLETED", OTHER_DIGEST));
    assertRejected(fixture);
    assertThat(fixture.sourceReads).hasValue(2);
  }

  @ParameterizedTest
  @ValueSource(strings = {"FROZEN", "COMPLETED"})
  void signedProofCannotSubstituteTheOtherControlPhase(String currentPhase) {
    var fixture =
        new Fixture(
            "game-session-original",
            currentPhase,
            "COMPLETED".equals(currentPhase) ? DIGEST : null);
    fixture.mutation =
        root -> {
          var owner = object(root, "owner");
          boolean wasFrozen = "FROZEN".equals(currentPhase);
          owner.put("controlState", wasFrozen ? "COMPLETED" : "FROZEN");
          owner.put("completionDigest", wasFrozen ? DIGEST : null);
        };
    assertRejected(fixture);
  }

  @Test
  void completedSignedDigestMustMatchExactSealedCompletion() {
    var fixture = new Fixture("game-session-original", "COMPLETED", DIGEST);
    fixture.mutation = root -> object(root, "owner").put("completionDigest", OTHER_DIGEST);
    assertRejected(fixture);
  }

  @Test
  void phaseAndCompletionFieldsMustBothBePresentEvenForFrozenNullCompletion() {
    for (String field : List.of("controlState", "completionDigest")) {
      var fixture = new Fixture();
      fixture.mutation = root -> object(root, "owner").remove(field);
      assertRejected(fixture);
    }
  }

  @Test
  void sourceContextRejectsUnsupportedOrMismatchedControlPhaseAndCompletion() {
    var context = new Fixture().original;
    assertThatThrownBy(() -> withPhase(context, "UNKNOWN", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> withPhase(context, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> withPhase(context, "FROZEN", DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> withPhase(context, "COMPLETED", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> withPhase(context, "COMPLETED", "not-a-digest"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void retainedTrimmedOwnerIdentityNeedsNoAdditionalCharacterGrammar() {
    var fixture = new Fixture("game-session/worker α", "FROZEN", null);
    var proof = fixture.observe(NOW + 1_000L);
    assertThat(proof.regionOwnerTransferContext().oldRegionOwnerId())
        .isEqualTo("game-session/worker α");
    assertThat(fixture.sourceReads).hasValue(2);
  }

  @Test
  void retainedOwnerLengthUsesThePostgresCharacterBound() {
    String ownerId = "\uD83C\uDFAE".repeat(120);
    var fixture = new Fixture(ownerId, "FROZEN", null);
    assertThat(fixture.observe(NOW + 1_000L).regionOwnerTransferContext().oldRegionOwnerId())
        .isEqualTo(ownerId);
    assertThatThrownBy(() -> new Fixture(ownerId + "x", "FROZEN", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "issued",
        "observed-before",
        "observed-after-expiry",
        "expires",
        "expired",
        "clock-back"
      })
  void timeWindowMismatchOrDeadlineExpiryRefuses(String mode) {
    var fixture = new Fixture();
    switch (mode) {
      case "issued" ->
          fixture.mutation = root -> root.put("observationIssuedAtEpochMillis", NOW - 1L);
      case "observed-before" ->
          fixture.mutation = root -> root.put("observationObservedAtEpochMillis", NOW - 1L);
      case "observed-after-expiry" ->
          fixture.mutation = root -> root.put("observationObservedAtEpochMillis", NOW + 1_001L);
      case "expires" ->
          fixture.mutation = root -> root.put("observationExpiresAtEpochMillis", NOW + 999L);
      case "expired" -> fixture.afterSign = () -> fixture.now.set(NOW + 1_000L);
      case "clock-back" -> fixture.afterSign = () -> fixture.now.set(NOW - 1L);
      default -> throw new IllegalArgumentException(mode);
    }
    assertRejected(fixture);
  }

  @Test
  void futureObservationWithinSignedExpiryReachesCurrentClockRefusal() {
    var fixture = new Fixture();
    fixture.mutation = root -> root.put("observationObservedAtEpochMillis", NOW + 1L);
    assertThatThrownBy(() -> fixture.observe(NOW + 1_000L))
        .isInstanceOf(
            CanonicalGameplayProtectedIncarnationReceiver.ObservationRejectedException.class)
        .hasMessage("Protected observation is expired or out of window");
    // A second source read occurs only after the signed payload and its time window are verified.
    assertThat(fixture.sourceReads).hasValue(2);
    assertThat(fixture.channelCalls).hasValue(1);
  }

  @Test
  void expiredOperationNeverReadsSourceOrCallsChannel() {
    var fixture = new Fixture();
    assertThatThrownBy(() -> fixture.observe(NOW))
        .isInstanceOf(
            CanonicalGameplayProtectedIncarnationReceiver.ObservationUnavailableException.class);
    assertThat(fixture.sourceReads).hasValue(0);
    assertThat(fixture.channelCalls).hasValue(0);
  }

  @Test
  void sourceReadCannotRenewAnOperationThatExpiredBeforeChannelDispatch() {
    var fixture = new Fixture();
    fixture.onSourceRead = () -> fixture.now.set(NOW + 1_000L);
    assertThatThrownBy(() -> fixture.observe(NOW + 1_000L))
        .isInstanceOf(
            CanonicalGameplayProtectedIncarnationReceiver.ObservationUnavailableException.class);
    assertThat(fixture.channelCalls).hasValue(0);
  }

  @ParameterizedTest
  @ValueSource(strings = {"root", "owner", "target", "targetDeploymentEvidence"})
  void signedUnknownFieldsAreRejectedInEveryClosedObject(String object) {
    var fixture = new Fixture();
    fixture.mutation =
        root -> ("root".equals(object) ? root : object(root, object)).put("unknown", "value");
    assertRejected(fixture);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "operationId",
        "operationFence",
        "initializerId",
        "originalAttemptFence",
        "progressCursor",
        "completedCount"
      })
  void accountInitialAndMutableProgressFieldsAreNotTransferOwnerFields(String field) {
    var fixture = new Fixture();
    fixture.mutation = root -> object(root, "owner").put(field, "unexpected");
    assertRejected(fixture);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"signature", "unpinned", "noncanonical", "duplicate", "oversized", "utf8"})
  void cryptographicCanonicalParsingAndSizeFailuresRefuse(String mode) {
    var fixture = new Fixture();
    fixture.encodingFailure = mode;
    assertRejected(fixture);
  }

  @Test
  void ambientTransactionRefusesBeforeReadingSqlOrCallingChannel() {
    var fixture = new Fixture();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> fixture.observe(NOW + 1_000L))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      TransactionSynchronizationManager.clear();
    }
    assertThat(fixture.sourceReads).hasValue(0);
    assertThat(fixture.channelCalls).hasValue(0);
  }

  @Test
  void sourceContextRejectsInvalidUuidFenceEpochDigestAndBoundedIdentifiers() {
    var context = new Fixture().original;
    for (String field :
        List.of(
            "nil-transfer",
            "negative-ordinal",
            "zero-fence",
            "zero-epoch",
            "digest",
            "owner",
            "owner-null",
            "owner-blank",
            "owner-untrimmed",
            "workload",
            "allocation")) {
      assertThatThrownBy(() -> invalid(context, field))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static void assertRejected(Fixture fixture) {
    assertThatThrownBy(() -> fixture.observe(NOW + 1_000L))
        .isInstanceOf(
            CanonicalGameplayProtectedIncarnationReceiver.ObservationRejectedException.class);
  }

  private static CanonicalGameplayRegionOwnerTransferObservationSource.Context changed(
      CanonicalGameplayRegionOwnerTransferObservationSource.Context c, String field) {
    return new CanonicalGameplayRegionOwnerTransferObservationSource.Context(
        c.transferId(),
        c.targetOrdinal(),
        "transferIntentDigest".equals(field) ? OTHER_DIGEST : c.transferIntentDigest(),
        "frozenSourceDigest".equals(field) ? OTHER_DIGEST : c.frozenSourceDigest(),
        "oldRegionOwnerId".equals(field) ? "different-owner" : c.oldRegionOwnerId(),
        "oldRegionOwnerFence".equals(field)
            ? c.oldRegionOwnerFence() + 1L
            : c.oldRegionOwnerFence(),
        c.tenantId(),
        c.gameInstanceId(),
        c.regionId(),
        "regionEpoch".equals(field) ? c.regionEpoch() + 1L : c.regionEpoch(),
        c.transitionId(),
        c.requestId(),
        c.accountId(),
        c.playableStateNamespaceId(),
        "executionDigest".equals(field) ? OTHER_DIGEST : c.executionDigest(),
        "candidateDigest".equals(field) ? OTHER_DIGEST : c.candidateDigest(),
        c.tenantGameplayTag(),
        c.workloadNamespace(),
        c.allocationVersion(),
        c.controlState(),
        c.completionDigest());
  }

  private static CanonicalGameplayRegionOwnerTransferObservationSource.Context withPhase(
      CanonicalGameplayRegionOwnerTransferObservationSource.Context c,
      String phase,
      String completionDigest) {
    return new CanonicalGameplayRegionOwnerTransferObservationSource.Context(
        c.transferId(),
        c.targetOrdinal(),
        c.transferIntentDigest(),
        c.frozenSourceDigest(),
        c.oldRegionOwnerId(),
        c.oldRegionOwnerFence(),
        c.tenantId(),
        c.gameInstanceId(),
        c.regionId(),
        c.regionEpoch(),
        c.transitionId(),
        c.requestId(),
        c.accountId(),
        c.playableStateNamespaceId(),
        c.executionDigest(),
        c.candidateDigest(),
        c.tenantGameplayTag(),
        c.workloadNamespace(),
        c.allocationVersion(),
        phase,
        completionDigest);
  }

  private static CanonicalGameplayRegionOwnerTransferObservationSource.Context invalid(
      CanonicalGameplayRegionOwnerTransferObservationSource.Context c, String field) {
    String owner =
        switch (field) {
          case "owner" -> "x".repeat(121);
          case "owner-null" -> null;
          case "owner-blank" -> " ";
          case "owner-untrimmed" -> " owner ";
          default -> c.oldRegionOwnerId();
        };
    return new CanonicalGameplayRegionOwnerTransferObservationSource.Context(
        "nil-transfer".equals(field) ? new UUID(0L, 0L) : c.transferId(),
        "negative-ordinal".equals(field) ? -1L : c.targetOrdinal(),
        "digest".equals(field) ? "sha256:" + "A".repeat(64) : c.transferIntentDigest(),
        c.frozenSourceDigest(),
        owner,
        "zero-fence".equals(field) ? 0L : c.oldRegionOwnerFence(),
        c.tenantId(),
        c.gameInstanceId(),
        c.regionId(),
        "zero-epoch".equals(field) ? 0L : c.regionEpoch(),
        c.transitionId(),
        c.requestId(),
        c.accountId(),
        c.playableStateNamespaceId(),
        c.executionDigest(),
        c.candidateDigest(),
        c.tenantGameplayTag(),
        "workload".equals(field) ? "INVALID_NAMESPACE" : c.workloadNamespace(),
        "allocation".equals(field) ? 0 : c.allocationVersion(),
        c.controlState(),
        c.completionDigest());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Map<String, Object> root, String field) {
    return (Map<String, Object>) root.get(field);
  }

  private static void mutate(Map<String, Object> object, String field) {
    Object old = object.get(field);
    Object replacement;
    if (old instanceof Number number) {
      replacement = number.longValue() + 1L;
    } else if (old instanceof List<?>) {
      replacement = List.of("different-node");
    } else if (old instanceof String text && text.startsWith("sha256:")) {
      replacement = OTHER_DIGEST;
    } else {
      replacement = "different-" + old;
    }
    object.put(field, replacement);
  }

  private static KeyPair controlledKeys() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (java.security.GeneralSecurityException failure) {
      throw new IllegalStateException("Controlled test key generation failed", failure);
    }
  }

  private static final class Fixture {
    private final AtomicLong now = new AtomicLong(NOW);
    private final Clock clock = mock(Clock.class);
    private final AtomicInteger sourceReads = new AtomicInteger();
    private final AtomicInteger channelCalls = new AtomicInteger();
    private final CanonicalGameplayRegionOwnerTransferObservationSource.Context original;
    private final AtomicReference<CanonicalGameplayRegionOwnerTransferObservationSource.Context>
        current;
    private final CanonicalGameplayTenantGameplayTagRepository tags =
        mock(CanonicalGameplayTenantGameplayTagRepository.class);
    private final CanonicalGameplayTenantGameplayTagRepository.Assignment assignment =
        mock(CanonicalGameplayTenantGameplayTagRepository.Assignment.class);
    private Consumer<Map<String, Object>> mutation = root -> {};
    private Runnable afterSign = () -> {};
    private Runnable onSourceRead = () -> {};
    private boolean sourceUnavailable;
    private String encodingFailure = "";

    private Fixture() {
      this("game-session-original", "FROZEN", null);
    }

    private Fixture(String ownerId, String controlState, String completionDigest) {
      UUID tenant = UUID.randomUUID();
      original =
          new CanonicalGameplayRegionOwnerTransferObservationSource.Context(
              UUID.randomUUID(),
              3L,
              DIGEST,
              DIGEST,
              ownerId,
              7L,
              tenant,
              UUID.randomUUID(),
              UUID.randomUUID(),
              11L,
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              DIGEST,
              DIGEST,
              "g1-" + tenant.toString().replace("-", ""),
              "test-workload",
              1,
              controlState,
              completionDigest);
      current = new AtomicReference<>(original);
      when(clock.millis()).thenAnswer(invocation -> now.get());
      when(tags.assignmentForTransition(original.transitionId())).thenReturn(assignment);
      when(assignment.isBindingTransition()).thenReturn(true);
      when(assignment.transitionId()).thenReturn(original.transitionId());
      when(assignment.accountId()).thenReturn(original.accountId());
      when(assignment.tenantId()).thenReturn(original.tenantId());
      when(assignment.gameInstanceId()).thenReturn(original.gameInstanceId());
      when(assignment.playableStateNamespaceId()).thenReturn(original.playableStateNamespaceId());
      when(assignment.tenantGameplayTag()).thenReturn(original.tenantGameplayTag());
      when(assignment.workloadNamespace()).thenReturn(original.workloadNamespace());
      when(assignment.allocationVersion()).thenReturn(original.allocationVersion());
    }

    private CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation observe(
        long deadline) {
      return CanonicalGameplayProtectedIncarnationReceiver.forRegionOwnerTransfer(
              (transferId, ordinal) -> {
                sourceReads.incrementAndGet();
                onSourceRead.run();
                if (sourceUnavailable) {
                  throw new CanonicalGameplayProtectedIncarnationReceiver
                      .ObservationUnavailableException(
                      "Controlled current SQL transfer source became unavailable");
                }
                return current.get();
              },
              tags,
              this::signed,
              trust(),
              clock)
          .observeForRegionOwnerTransfer(original.transferId(), original.targetOrdinal(), deadline);
    }

    private void breakAssignment(String field) {
      switch (field) {
        case "isBindingTransition" -> when(assignment.isBindingTransition()).thenReturn(false);
        case "transitionId" -> when(assignment.transitionId()).thenReturn(UUID.randomUUID());
        case "accountId" -> when(assignment.accountId()).thenReturn(UUID.randomUUID());
        case "tenantId" -> when(assignment.tenantId()).thenReturn(UUID.randomUUID());
        case "gameInstanceId" -> when(assignment.gameInstanceId()).thenReturn(UUID.randomUUID());
        case "playableStateNamespaceId" ->
            when(assignment.playableStateNamespaceId()).thenReturn(UUID.randomUUID());
        case "tenantGameplayTag" ->
            when(assignment.tenantGameplayTag()).thenReturn("g1-" + "f".repeat(32));
        case "workloadNamespace" ->
            when(assignment.workloadNamespace()).thenReturn("different-workload");
        case "allocationVersion" -> when(assignment.allocationVersion()).thenReturn(2);
        default -> throw new IllegalArgumentException(field);
      }
    }

    private CanonicalGameplayProtectedIncarnationReceiver.TrustedConfiguration trust() {
      var evidence =
          new CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence(
              "controlled-target",
              "generation-1",
              List.of("redis-a", "redis-b"),
              "redis-build",
              "registry-1",
              DIGEST,
              "READY",
              "boot-1",
              "startup-1",
              "storage-1",
              "aof-1");
      return new CanonicalGameplayProtectedIncarnationReceiver.TrustedConfiguration(
          "controlled-transfer-binding",
          DIGEST,
          Map.of(KEY_ID, (RSAPublicKey) KEYS.getPublic()),
          evidence,
          DIGEST,
          DIGEST,
          16 * 1024,
          2,
          Duration.ofSeconds(30));
    }

    private CanonicalGameplayProtectedIncarnationReceiver.SignedObservation signed(
        CanonicalGameplayProtectedIncarnationReceiver.ObservationRequest request) {
      channelCalls.incrementAndGet();
      Map<String, Object> root = payload(request);
      mutation.accept(root);
      try {
        byte[] bytes = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(root));
        switch (encodingFailure) {
          case "noncanonical" ->
              bytes =
                  (new String(bytes, StandardCharsets.UTF_8) + " ")
                      .getBytes(StandardCharsets.UTF_8);
          case "duplicate" ->
              bytes =
                  ("{\"schema\":\"duplicate\","
                          + new String(bytes, StandardCharsets.UTF_8).substring(1))
                      .getBytes(StandardCharsets.UTF_8);
          case "oversized" -> bytes = new byte[17 * 1024];
          case "utf8" -> bytes = new byte[] {(byte) 0xc3, (byte) 0x28};
          default -> {}
        }
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(KEYS.getPrivate());
        signer.update(bytes);
        byte[] signature = signer.sign();
        if ("signature".equals(encodingFailure)) signature[0] ^= 1;
        afterSign.run();
        return new CanonicalGameplayProtectedIncarnationReceiver.SignedObservation(
            "unpinned".equals(encodingFailure) ? "unknown-key" : KEY_ID, bytes, signature);
      } catch (java.security.GeneralSecurityException | java.io.IOException failure) {
        throw new IllegalStateException("Controlled transfer signing failed", failure);
      }
    }

    private Map<String, Object> payload(
        CanonicalGameplayProtectedIncarnationReceiver.ObservationRequest request) {
      var c = original;
      Map<String, Object> owner = new LinkedHashMap<>();
      owner.put("ownerKind", "REGION_OWNER_TRANSFER");
      owner.put("transferId", c.transferId().toString());
      owner.put("transferIntentDigest", c.transferIntentDigest());
      owner.put("frozenSourceDigest", c.frozenSourceDigest());
      owner.put("oldRegionOwnerId", c.oldRegionOwnerId());
      owner.put("oldRegionOwnerFence", c.oldRegionOwnerFence());
      owner.put("gameInstanceId", c.gameInstanceId().toString());
      owner.put("regionId", c.regionId().toString());
      owner.put("regionEpoch", c.regionEpoch());
      owner.put("transitionId", c.transitionId().toString());
      owner.put("requestId", c.requestId().toString());
      owner.put("accountId", c.accountId().toString());
      owner.put("executionDigest", c.executionDigest());
      owner.put("candidateDigest", c.candidateDigest());
      owner.put("controlState", c.controlState());
      owner.put("completionDigest", c.completionDigest());
      Map<String, Object> target = new LinkedHashMap<>();
      target.put("targetOrdinal", c.targetOrdinal());
      target.put("tenantId", c.tenantId().toString());
      target.put("playableStateNamespaceId", c.playableStateNamespaceId().toString());
      target.put("tenantGameplayTag", c.tenantGameplayTag());
      target.put("workloadNamespace", c.workloadNamespace());
      target.put("allocationVersion", c.allocationVersion());
      var e = request.expectedEvidence();
      Map<String, Object> evidence = new LinkedHashMap<>();
      evidence.put("deploymentIdentity", e.deploymentIdentity());
      evidence.put("deploymentGeneration", e.deploymentGeneration());
      evidence.put("canonicalNodeSet", e.canonicalNodeSet());
      evidence.put("redisBuildIdentity", e.redisBuildIdentity());
      evidence.put("luaScriptRegistryVersion", e.luaScriptRegistryVersion());
      evidence.put("luaScriptRegistryDigest", e.luaScriptRegistryDigest());
      evidence.put("startupState", e.startupState());
      evidence.put("bootGeneration", e.bootGeneration());
      evidence.put("startupGeneration", e.startupGeneration());
      evidence.put("storageGeneration", e.storageGeneration());
      evidence.put("aofGeneration", e.aofGeneration());
      Map<String, Object> root = new LinkedHashMap<>();
      root.put("schema", SCHEMA);
      root.put("attestorBinding", request.attestorBinding());
      root.put("attestorKeyId", KEY_ID);
      root.put("acceptedAttestationDigest", request.acceptedAttestationDigest());
      root.put("observationNonce", request.nonce());
      root.put("observationIssuedAtEpochMillis", request.issuedAtEpochMillis());
      root.put("observationObservedAtEpochMillis", request.issuedAtEpochMillis());
      root.put("observationExpiresAtEpochMillis", request.expiresAtEpochMillis());
      root.put("owner", owner);
      root.put("target", target);
      root.put("targetDeploymentEvidence", evidence);
      root.put("coordOpsRoAclDigest", request.coordOpsRoAclDigest());
      root.put("coordRedisConfigObservedDigest", request.coordRedisConfigObservedDigest());
      return root;
    }
  }
}
