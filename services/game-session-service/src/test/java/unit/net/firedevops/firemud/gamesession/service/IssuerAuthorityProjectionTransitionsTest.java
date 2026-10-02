package unit.net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.CaptureIssuerProjectionForRuntimeResponse;
import net.firedevops.firemud.account.v1.IssuerAuthorityServiceGrpc;
import net.firedevops.firemud.account.v1.IssuerAuthoritySourceSnapshot;
import net.firedevops.firemud.account.v1.ReadIssuerAuthorityForRuntimeRequest;
import net.firedevops.firemud.account.v1.ReadIssuerAuthorityForRuntimeResponse;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent;
import net.firedevops.firemud.common.account.authority.IssuerProjectionReconciliationRequestDigestV1;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.client.AccountIssuerAuthorityClient;
import net.firedevops.firemud.gamesession.client.AccountIssuerAuthorityClient.ProjectionCaptureReceipt;
import net.firedevops.firemud.gamesession.client.AccountIssuerAuthorityClient.SourceReadback;
import net.firedevops.firemud.gamesession.service.IssuerAuthorityProjectionTransitions;
import org.junit.jupiter.api.Test;

/** Decision/unit proof using AccountIssuerAuthorityClient's existing mocked response seam. */
class IssuerAuthorityProjectionTransitionsTest {
  private static final String ISSUER_ID = "https://accounts.example.test/issuer";
  private static final String NAMESPACE = "test";
  private static final String QUERY_ID = "11111111-1111-4111-8111-111111111111";
  private static final String EVENT_ID_PREFIX = "account-issuer-authority-event-v1:";

  @Test
  void bootstrapAtProvedSequenceZeroOmitsEventIdentityAndKeepsCallerAppliedAt() throws Exception {
    SourceReadback readback = readback(ISSUER_ID, "1", "1", "0", null, null);

    IssuerAuthorityProjectionTransitions.Mutation mutation =
        (IssuerAuthorityProjectionTransitions.Mutation)
            IssuerAuthorityProjectionTransitions.bootstrap(readback, "2026-10-02T11:00:00Z");

    assertThat(mutation.kind())
        .isEqualTo(IssuerAuthorityProjectionTransitions.MutationKind.BOOTSTRAP);
    assertThat(mutation.expectedProjection()).isEmpty();
    assertThat(mutation.nextProjection())
        .containsEntry("schemaVersion", IssuerAuthorityProjectionTransitions.SCHEMA_VERSION)
        .containsEntry("issuerId", ISSUER_ID)
        .containsEntry("lastAppliedIssuerGeneration", "1")
        .containsEntry("lastAppliedSourceOutboxSequence", "0")
        .containsEntry(
            "outboxStreamKey",
            IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "issuer/" + ISSUER_ID)
        .containsEntry("appliedAt", "2026-10-02T11:00:00Z")
        .containsEntry("appliedSourceEvidence", Map.of());
    assertThat(mutation.nextProjection())
        .doesNotContainKeys(
            "lastAppliedSourceEventId",
            "lastAppliedSourceEventDigest",
            "issuerAuthGeneration",
            "sourceOutboxStreamKey",
            "sourceEventId",
            "sourceEventDigest");
    assertThat(mutation.nextProjection().keySet())
        .containsExactlyInAnyOrder(
            "schemaVersion",
            "issuerId",
            "lastAppliedIssuerGeneration",
            "lastAppliedSourceOutboxSequence",
            "outboxStreamKey",
            "appliedAt",
            "appliedSourceEvidence");
  }

  @Test
  void bootstrapPositiveCurrentCheckpointRetainsCompleteLatestEventEvidence() throws Exception {
    IssuerGenerationAuthorityEvent event = event("7", "92", "105", 1, ISSUER_ID);
    SourceReadback readback = readback(ISSUER_ID, "92", "105", "7", event, null);

    IssuerAuthorityProjectionTransitions.Mutation mutation =
        (IssuerAuthorityProjectionTransitions.Mutation)
            IssuerAuthorityProjectionTransitions.bootstrap(readback, "stable-time");

    assertThat(mutation.nextProjection())
        .containsEntry("lastAppliedIssuerGeneration", "92")
        .containsEntry("lastAppliedSourceOutboxSequence", "7")
        .containsEntry("outboxStreamKey", event.outboxStreamKey())
        .containsEntry("lastAppliedSourceEventId", event.eventId())
        .containsEntry("lastAppliedSourceEventDigest", event.eventDigest())
        .containsEntry("appliedSourceEvidence", Map.of("7", event.canonicalJson()));
    assertThat(mutation.nextProjection())
        .doesNotContainKey("admitted")
        .doesNotContainKeys(
            "issuerAuthGeneration", "sourceOutboxStreamKey", "sourceEventId", "sourceEventDigest");
    assertThat(mutation.nextProjection().keySet())
        .containsExactlyInAnyOrder(
            "schemaVersion",
            "issuerId",
            "lastAppliedIssuerGeneration",
            "lastAppliedSourceOutboxSequence",
            "outboxStreamKey",
            "lastAppliedSourceEventId",
            "lastAppliedSourceEventDigest",
            "appliedAt",
            "appliedSourceEvidence");
  }

  @Test
  void mutationCandidateAccessorReturnsDefensiveImmutableCopies() throws Exception {
    IssuerGenerationAuthorityEvent event = event("1", "2", "3", 24, ISSUER_ID);
    SourceReadback readback = readback(ISSUER_ID, "2", "3", "1", event, null);
    IssuerAuthorityProjectionTransitions.Mutation mutation =
        (IssuerAuthorityProjectionTransitions.Mutation)
            IssuerAuthorityProjectionTransitions.bootstrap(readback, "stable-time");

    Map<String, Object> first = mutation.nextProjection();
    Map<String, Object> second = mutation.nextProjection();

    assertThat(first).isEqualTo(second).isNotSameAs(second);
    assertThatThrownBy(() -> first.put("unexpected", true))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void bootstrapRejectsHistoricalSelectionWithoutProducingProjection() throws Exception {
    IssuerGenerationAuthorityEvent latest = event("3", "4", "6", 2, ISSUER_ID);
    IssuerGenerationAuthorityEvent selected = event("2", "3", "5", 1, ISSUER_ID);
    SourceReadback readback = readback(ISSUER_ID, "4", "6", "3", latest, selected);

    IssuerAuthorityProjectionTransitions.Quarantine decision =
        (IssuerAuthorityProjectionTransitions.Quarantine)
            IssuerAuthorityProjectionTransitions.bootstrap(readback, "stable-time");

    assertThat(decision.reason())
        .isEqualTo(
            IssuerAuthorityProjectionTransitions.QuarantineReason
                .BOOTSTRAP_REQUIRES_CURRENT_READBACK);
    assertThat(decision.expectedProjection()).isEmpty();
  }

  @Test
  void selectedContiguousEventAdvancesWatermarkAndCheckpointEvenWhenGenerationIsEqual()
      throws Exception {
    IssuerGenerationAuthorityEvent prior = event("4", "80", "102", 4, ISSUER_ID);
    Map<String, Object> existing = bootstrapProjection("80", "102", "4", prior);
    IssuerGenerationAuthorityEvent next = event("5", "80", "103", 5, ISSUER_ID);
    SourceReadback readback = readback(ISSUER_ID, "80", "103", "5", next, next);

    IssuerAuthorityProjectionTransitions.Mutation mutation =
        (IssuerAuthorityProjectionTransitions.Mutation)
            IssuerAuthorityProjectionTransitions.decide(existing, readback, "stable-applied-at");

    assertThat(mutation.kind())
        .isEqualTo(IssuerAuthorityProjectionTransitions.MutationKind.ADVANCE);
    assertThat(mutation.expectedProjection()).contains(existing);
    assertThat(mutation.nextProjection())
        .containsEntry("lastAppliedIssuerGeneration", "80")
        .containsEntry("lastAppliedSourceOutboxSequence", "5")
        .containsEntry("outboxStreamKey", next.outboxStreamKey())
        .containsEntry("lastAppliedSourceEventId", next.eventId())
        .containsEntry("lastAppliedSourceEventDigest", next.eventDigest())
        .containsEntry("appliedSourceEvidence", Map.of("5", next.canonicalJson()));
  }

  @Test
  void selectedContiguousEventUsesHigherGenerationWhenAccountAdvancedIt() throws Exception {
    IssuerGenerationAuthorityEvent prior = event("4", "80", "102", 20, ISSUER_ID);
    Map<String, Object> existing = bootstrapProjection("80", "102", "4", prior);
    IssuerGenerationAuthorityEvent next = event("5", "81", "103", 21, ISSUER_ID);
    SourceReadback readback = readback(ISSUER_ID, "81", "103", "5", next, next);

    IssuerAuthorityProjectionTransitions.Mutation mutation =
        (IssuerAuthorityProjectionTransitions.Mutation)
            IssuerAuthorityProjectionTransitions.decide(existing, readback, "stable-applied-at");

    assertThat(mutation.nextProjection())
        .containsEntry("lastAppliedIssuerGeneration", "81")
        .containsEntry("lastAppliedSourceOutboxSequence", "5");
  }

  @Test
  void sequenceGapQuarantinesWithoutReturningAMutationCandidate() throws Exception {
    IssuerGenerationAuthorityEvent prior = event("1", "2", "3", 6, ISSUER_ID);
    Map<String, Object> existing = bootstrapProjection("2", "3", "1", prior);
    IssuerGenerationAuthorityEvent gap = event("3", "4", "5", 7, ISSUER_ID);
    SourceReadback readback = readback(ISSUER_ID, "4", "5", "3", gap, gap);

    IssuerAuthorityProjectionTransitions.Quarantine decision =
        (IssuerAuthorityProjectionTransitions.Quarantine)
            IssuerAuthorityProjectionTransitions.decide(existing, readback, "stable-applied-at");

    assertThat(decision.reason())
        .isEqualTo(IssuerAuthorityProjectionTransitions.QuarantineReason.EVENT_SEQUENCE_GAP);
    assertThat(decision.expectedProjection()).contains(existing);
  }

  @Test
  void malformedExistingProjectionQuarantinesBeforeConsideringAccountEvent() throws Exception {
    IssuerGenerationAuthorityEvent first = event("1", "2", "2", 22, ISSUER_ID);
    Map<String, Object> validProjection = bootstrapProjection("2", "2", "1", first);
    Map<String, Object> malformedProjection = new LinkedHashMap<>(validProjection);
    malformedProjection.put("lastAppliedIssuerGeneration", "02");
    IssuerGenerationAuthorityEvent next = event("2", "2", "3", 23, ISSUER_ID);
    SourceReadback readback = readback(ISSUER_ID, "2", "3", "2", next, next);

    IssuerAuthorityProjectionTransitions.Quarantine decision =
        (IssuerAuthorityProjectionTransitions.Quarantine)
            IssuerAuthorityProjectionTransitions.decide(
                malformedProjection, readback, "stable-applied-at");

    assertThat(decision.reason())
        .isEqualTo(
            IssuerAuthorityProjectionTransitions.QuarantineReason.MALFORMED_EXISTING_PROJECTION);
    assertThat(decision.expectedProjection()).contains(malformedProjection);
  }

  @Test
  void obsoleteStoredAliasesQuarantineBeforeConsideringAccountEvent() throws Exception {
    IssuerGenerationAuthorityEvent first = event("1", "2", "2", 22, ISSUER_ID);
    Map<String, Object> validProjection = bootstrapProjection("2", "2", "1", first);
    Map<String, Object> obsoleteProjection = new LinkedHashMap<>(validProjection);
    obsoleteProjection.remove("lastAppliedIssuerGeneration");
    obsoleteProjection.put("issuerAuthGeneration", "2");
    IssuerGenerationAuthorityEvent next = event("2", "2", "3", 23, ISSUER_ID);
    SourceReadback readback = readback(ISSUER_ID, "2", "3", "2", next, next);

    IssuerAuthorityProjectionTransitions.Quarantine decision =
        (IssuerAuthorityProjectionTransitions.Quarantine)
            IssuerAuthorityProjectionTransitions.decide(
                obsoleteProjection, readback, "stable-applied-at");

    assertThat(decision.reason())
        .isEqualTo(
            IssuerAuthorityProjectionTransitions.QuarantineReason.MALFORMED_EXISTING_PROJECTION);
    assertThat(decision.expectedProjection()).contains(obsoleteProjection);
  }

  @Test
  void exactDuplicateIsNoOpAndDoesNotReplaceAppliedAt() throws Exception {
    IssuerGenerationAuthorityEvent applied = event("2", "3", "4", 8, ISSUER_ID);
    Map<String, Object> existing = bootstrapProjection("3", "4", "2", applied);
    SourceReadback readback = readback(ISSUER_ID, "3", "4", "2", applied, applied);

    IssuerAuthorityProjectionTransitions.NoOp decision =
        (IssuerAuthorityProjectionTransitions.NoOp)
            IssuerAuthorityProjectionTransitions.decide(existing, readback, "ignored-for-no-op");

    assertThat(decision.reason())
        .isEqualTo(IssuerAuthorityProjectionTransitions.NoOpReason.EXACT_DUPLICATE);
    assertThat(existing).containsEntry("appliedAt", "stable-time");
  }

  @Test
  void duplicateWithDifferentCompleteCanonicalEventQuarantines() throws Exception {
    IssuerGenerationAuthorityEvent applied = event("1", "2", "2", 9, ISSUER_ID);
    Map<String, Object> existing = bootstrapProjection("2", "2", "1", applied);
    IssuerGenerationAuthorityEvent conflicting = event("1", "2", "2", 10, ISSUER_ID);
    IssuerGenerationAuthorityEvent current = event("2", "3", "3", 11, ISSUER_ID);
    SourceReadback readback = readback(ISSUER_ID, "3", "3", "2", current, conflicting);

    IssuerAuthorityProjectionTransitions.Quarantine decision =
        (IssuerAuthorityProjectionTransitions.Quarantine)
            IssuerAuthorityProjectionTransitions.decide(existing, readback, "stable-applied-at");

    assertThat(decision.reason())
        .isEqualTo(IssuerAuthorityProjectionTransitions.QuarantineReason.DUPLICATE_EVENT_CONFLICT);
    assertThat(existing).containsEntry("lastAppliedSourceOutboxSequence", "1");
  }

  @Test
  void authenticatedHistoricalDuplicateMayHaveLowerGenerationAndSourceVersion() throws Exception {
    IssuerGenerationAuthorityEvent first = event("1", "2", "3", 12, ISSUER_ID);
    IssuerGenerationAuthorityEvent later = event("2", "4", "6", 13, ISSUER_ID);
    Map<String, Object> existing = bootstrapProjection("4", "6", "2", later);
    SourceReadback readback = readback(ISSUER_ID, "4", "6", "2", later, first);

    IssuerAuthorityProjectionTransitions.NoOp decision =
        (IssuerAuthorityProjectionTransitions.NoOp)
            IssuerAuthorityProjectionTransitions.decide(existing, readback, "stable-applied-at");

    assertThat(decision.reason())
        .isEqualTo(IssuerAuthorityProjectionTransitions.NoOpReason.VERIFIED_HISTORICAL_DUPLICATE);
  }

  @Test
  void currentAccountCheckpointRegressionQuarantinesEvenForAContiguousSelectedEvent()
      throws Exception {
    IssuerGenerationAuthorityEvent installed = event("2", "8", "9", 14, ISSUER_ID);
    Map<String, Object> existing = bootstrapProjection("8", "9", "2", installed);
    IssuerGenerationAuthorityEvent regressedCurrent = event("3", "7", "8", 15, ISSUER_ID);
    SourceReadback readback =
        readback(ISSUER_ID, "7", "8", "3", regressedCurrent, regressedCurrent);

    IssuerAuthorityProjectionTransitions.Quarantine decision =
        (IssuerAuthorityProjectionTransitions.Quarantine)
            IssuerAuthorityProjectionTransitions.decide(existing, readback, "stable-applied-at");

    assertThat(decision.reason())
        .isEqualTo(
            IssuerAuthorityProjectionTransitions.QuarantineReason.CURRENT_CHECKPOINT_REGRESSED);
  }

  @Test
  void differentIssuerStreamProjectionQuarantines() throws Exception {
    String otherIssuer = "https://other.example.test/issuer";
    IssuerGenerationAuthorityEvent otherEvent = event("1", "2", "3", 16, otherIssuer);
    Map<String, Object> existing = bootstrapProjection(otherIssuer, "2", "3", "1", otherEvent);
    IssuerGenerationAuthorityEvent current = event("1", "2", "3", 17, ISSUER_ID);
    SourceReadback readback = readback(ISSUER_ID, "2", "3", "1", current, current);

    IssuerAuthorityProjectionTransitions.Quarantine decision =
        (IssuerAuthorityProjectionTransitions.Quarantine)
            IssuerAuthorityProjectionTransitions.decide(existing, readback, "stable-applied-at");

    assertThat(decision.reason())
        .isEqualTo(IssuerAuthorityProjectionTransitions.QuarantineReason.ISSUER_OR_STREAM_CHANGED);
  }

  @Test
  void countersBeyondLongRemainExactAndNoSequenceGenerationEquationIsAssumed() throws Exception {
    BigInteger sequence = new BigInteger("922337203685477580812345678901234567891");
    BigInteger priorSequence = sequence.subtract(BigInteger.ONE);
    String generation = "800000000000000000000000000000000000001";
    BigInteger priorVersion = new BigInteger("800000000000000000000000000000000000010");
    String version = priorVersion.add(BigInteger.ONE).toString();
    IssuerGenerationAuthorityEvent prior =
        event(priorSequence.toString(), generation, priorVersion.toString(), 18, ISSUER_ID);
    Map<String, Object> existing =
        bootstrapProjection(generation, priorVersion.toString(), priorSequence.toString(), prior);
    IssuerGenerationAuthorityEvent next =
        event(sequence.toString(), generation, version, 19, ISSUER_ID);
    SourceReadback readback =
        readback(ISSUER_ID, generation, version, sequence.toString(), next, next);

    IssuerAuthorityProjectionTransitions.Mutation mutation =
        (IssuerAuthorityProjectionTransitions.Mutation)
            IssuerAuthorityProjectionTransitions.decide(existing, readback, "stable-applied-at");

    assertThat(mutation.nextProjection())
        .containsEntry("lastAppliedSourceOutboxSequence", sequence.toString())
        .containsEntry("lastAppliedIssuerGeneration", generation)
        .containsEntry("lastAppliedSourceEventId", next.eventId());
  }

  @Test
  void captureReconciliationBootstrapsZeroAndPositiveSnapshots() throws Exception {
    CaptureEvidence zero = captureEvidence(ISSUER_ID, "1", "1", "0", null);
    IssuerAuthorityProjectionTransitions.Mutation zeroMutation =
        (IssuerAuthorityProjectionTransitions.Mutation)
            IssuerAuthorityProjectionTransitions.reconcile(
                null, zero.capture(), zero.current(), "zero-applied-at");
    assertThat(zeroMutation.kind())
        .isEqualTo(IssuerAuthorityProjectionTransitions.MutationKind.CAPTURE_RECONCILIATION);
    assertThat(zeroMutation.nextProjection())
        .containsEntry("lastAppliedSourceOutboxSequence", "0")
        .containsEntry("appliedAt", "zero-applied-at")
        .containsEntry("appliedSourceEvidence", Map.of())
        .doesNotContainKeys("lastAppliedSourceEventId", "lastAppliedSourceEventDigest");

    IssuerGenerationAuthorityEvent event = event("5", "11", "19", 64, ISSUER_ID);
    CaptureEvidence positive = captureEvidence(ISSUER_ID, "11", "19", "5", event);
    IssuerAuthorityProjectionTransitions.Mutation positiveMutation =
        (IssuerAuthorityProjectionTransitions.Mutation)
            IssuerAuthorityProjectionTransitions.reconcile(
                null, positive.capture(), positive.current(), "positive-applied-at");
    assertThat(positiveMutation.nextProjection())
        .containsEntry("lastAppliedSourceOutboxSequence", "5")
        .containsEntry("lastAppliedSourceEventId", event.eventId())
        .containsEntry("lastAppliedSourceEventDigest", event.eventDigest())
        .containsEntry("appliedSourceEvidence", Map.of("5", event.canonicalJson()));
  }

  @Test
  void captureReconciliationMayJumpAValidBehindProjectionAcrossMissedEvents() throws Exception {
    IssuerGenerationAuthorityEvent existingEvent = event("1", "2", "3", 65, ISSUER_ID);
    Map<String, Object> existing = bootstrapProjection("2", "3", "1", existingEvent);
    IssuerGenerationAuthorityEvent capturedEvent = event("5", "8", "12", 66, ISSUER_ID);
    CaptureEvidence evidence = captureEvidence(ISSUER_ID, "8", "12", "5", capturedEvent);

    IssuerAuthorityProjectionTransitions.Mutation mutation =
        (IssuerAuthorityProjectionTransitions.Mutation)
            IssuerAuthorityProjectionTransitions.reconcile(
                existing, evidence.capture(), evidence.current(), "reconciled-at");

    assertThat(mutation.kind())
        .isEqualTo(IssuerAuthorityProjectionTransitions.MutationKind.CAPTURE_RECONCILIATION);
    assertThat(mutation.expectedProjection()).contains(existing);
    assertThat(mutation.nextProjection())
        .containsEntry("lastAppliedSourceOutboxSequence", "5")
        .containsEntry("lastAppliedIssuerGeneration", "8")
        .containsEntry("appliedAt", "reconciled-at")
        .containsEntry("appliedSourceEvidence", Map.of("5", capturedEvent.canonicalJson()));
  }

  @Test
  void captureReconciliationExactCheckpointIsNoOpAndPreservesStoredAppliedAt() throws Exception {
    IssuerGenerationAuthorityEvent currentEvent = event("3", "6", "9", 67, ISSUER_ID);
    Map<String, Object> existing = bootstrapProjection("6", "9", "3", currentEvent);
    CaptureEvidence evidence = captureEvidence(ISSUER_ID, "6", "9", "3", currentEvent);

    IssuerAuthorityProjectionTransitions.NoOp noOp =
        (IssuerAuthorityProjectionTransitions.NoOp)
            IssuerAuthorityProjectionTransitions.reconcile(
                existing, evidence.capture(), evidence.current(), "new-retry-time");

    assertThat(noOp.reason())
        .isEqualTo(IssuerAuthorityProjectionTransitions.NoOpReason.CAPTURE_ALREADY_INSTALLED);
    assertThat(existing).containsEntry("appliedAt", "stable-time");
  }

  @Test
  void captureReconciliationDeniesAheadAndOrdinaryEventGapsRemainDenied() throws Exception {
    IssuerGenerationAuthorityEvent currentEvent = event("3", "6", "9", 68, ISSUER_ID);
    Map<String, Object> existing =
        bootstrapProjection("7", "12", "4", event("4", "7", "12", 69, ISSUER_ID));
    CaptureEvidence evidence = captureEvidence(ISSUER_ID, "6", "9", "3", currentEvent);

    IssuerAuthorityProjectionTransitions.Quarantine ahead =
        (IssuerAuthorityProjectionTransitions.Quarantine)
            IssuerAuthorityProjectionTransitions.reconcile(
                existing, evidence.capture(), evidence.current(), "ignored");
    assertThat(ahead.reason())
        .isEqualTo(IssuerAuthorityProjectionTransitions.QuarantineReason.CAPTURE_PROJECTION_AHEAD);

    IssuerGenerationAuthorityEvent prior = event("1", "2", "3", 70, ISSUER_ID);
    Map<String, Object> behind = bootstrapProjection("2", "3", "1", prior);
    IssuerGenerationAuthorityEvent gap = event("3", "4", "5", 71, ISSUER_ID);
    SourceReadback selectedGap = readback(ISSUER_ID, "4", "5", "3", gap, gap);
    IssuerAuthorityProjectionTransitions.Quarantine ordinaryGap =
        (IssuerAuthorityProjectionTransitions.Quarantine)
            IssuerAuthorityProjectionTransitions.decide(behind, selectedGap, "ignored");
    assertThat(ordinaryGap.reason())
        .isEqualTo(IssuerAuthorityProjectionTransitions.QuarantineReason.EVENT_SEQUENCE_GAP);
  }

  @Test
  void captureReconciliationRejectsASeparatelyVerifiedReadbackFromAnotherNamespace()
      throws Exception {
    CaptureEvidence captureEvidence = captureEvidence(ISSUER_ID, "1", "1", "0", null);
    SourceReadback otherNamespace = currentReadback("other", ISSUER_ID, "1", "1", "0", null);

    IssuerAuthorityProjectionTransitions.Quarantine decision =
        (IssuerAuthorityProjectionTransitions.Quarantine)
            IssuerAuthorityProjectionTransitions.reconcile(
                null, captureEvidence.capture(), otherNamespace, "stable-time");

    assertThat(decision.reason())
        .isEqualTo(IssuerAuthorityProjectionTransitions.QuarantineReason.CAPTURE_BINDING_MISMATCH);
  }

  private static CaptureEvidence captureEvidence(
      String issuerId,
      String generation,
      String sourceVersion,
      String sequence,
      IssuerGenerationAuthorityEvent event)
      throws Exception {
    return captureEvidence(NAMESPACE, issuerId, generation, sourceVersion, sequence, event);
  }

  private static CaptureEvidence captureEvidence(
      String namespace,
      String issuerId,
      String generation,
      String sourceVersion,
      String sequence,
      IssuerGenerationAuthorityEvent event)
      throws Exception {
    String scope = "issuer/" + issuerId;
    IssuerAuthoritySourceSnapshot.Builder source =
        IssuerAuthoritySourceSnapshot.newBuilder()
            .setIssuerId(issuerId)
            .setSourceScope(scope)
            .setOutboxStreamKey(IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + scope)
            .setIssuerAuthGeneration(generation)
            .setSourceVersion(sourceVersion)
            .setOutboxSequence(sequence);
    if (event != null) {
      source.setLatestEventCanonicalJson(event.canonicalJson());
    }
    IssuerAuthoritySourceSnapshot snapshot = source.build();
    String caller = "spiffe://firemud/ns/" + namespace + "/sa/game-session-service";
    String projectionKey = IssuerAuthorityProjectionTransitions.KEY_PREFIX + issuerId;
    java.util.UUID requestId = java.util.UUID.fromString(QUERY_ID);
    java.util.UUID operationId = java.util.UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub =
        mock(IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.captureIssuerProjectionForRuntime(any()))
        .thenReturn(
            CaptureIssuerProjectionForRuntimeResponse.newBuilder()
                .setSchemaVersion("account-auth-issuer-projection-capture/v1")
                .setTargetNamespace(namespace)
                .setOperationId(operationId.toString())
                .setRequestId(requestId.toString())
                .setIssuerId(issuerId)
                .setCallerWorkloadIdentity(caller)
                .setProjectionKey(projectionKey)
                .setRequestDigestVersion(1)
                .setRequestDigest(
                    IssuerProjectionReconciliationRequestDigestV1.digest(
                        issuerId, caller, projectionKey, requestId))
                .setCapturedSourceSnapshot(snapshot)
                .build());
    when(stub.readIssuerAuthorityForRuntime(any(ReadIssuerAuthorityForRuntimeRequest.class)))
        .thenReturn(
            ReadIssuerAuthorityForRuntimeResponse.newBuilder()
                .setSchemaVersion("account-auth-issuer-source-readback/v1")
                .setTargetNamespace(namespace)
                .setRequestId(QUERY_ID)
                .setSourceSnapshot(snapshot)
                .build());
    AccountIssuerAuthorityClient client = newClient(stub, issuerId, namespace);
    ProjectionCaptureReceipt capture = client.captureProjection(QUERY_ID);
    SourceReadback current = client.readCurrent(QUERY_ID);
    return new CaptureEvidence(capture, current);
  }

  private static SourceReadback currentReadback(
      String namespace,
      String issuerId,
      String generation,
      String sourceVersion,
      String sequence,
      IssuerGenerationAuthorityEvent event)
      throws Exception {
    String scope = "issuer/" + issuerId;
    IssuerAuthoritySourceSnapshot.Builder snapshot =
        IssuerAuthoritySourceSnapshot.newBuilder()
            .setIssuerId(issuerId)
            .setSourceScope(scope)
            .setOutboxStreamKey(IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + scope)
            .setIssuerAuthGeneration(generation)
            .setSourceVersion(sourceVersion)
            .setOutboxSequence(sequence);
    if (event != null) {
      snapshot.setLatestEventCanonicalJson(event.canonicalJson());
    }
    ReadIssuerAuthorityForRuntimeResponse response =
        ReadIssuerAuthorityForRuntimeResponse.newBuilder()
            .setSchemaVersion("account-auth-issuer-source-readback/v1")
            .setTargetNamespace(namespace)
            .setRequestId(QUERY_ID)
            .setSourceSnapshot(snapshot.build())
            .build();
    IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub =
        mock(IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.readIssuerAuthorityForRuntime(any(ReadIssuerAuthorityForRuntimeRequest.class)))
        .thenReturn(response);
    return newClient(stub, issuerId, namespace).readCurrent(QUERY_ID);
  }

  private record CaptureEvidence(ProjectionCaptureReceipt capture, SourceReadback current) {}

  private static Map<String, Object> bootstrapProjection(
      String generation,
      String sourceVersion,
      String sequence,
      IssuerGenerationAuthorityEvent latest)
      throws Exception {
    return bootstrapProjection(ISSUER_ID, generation, sourceVersion, sequence, latest);
  }

  private static Map<String, Object> bootstrapProjection(
      String issuerId,
      String generation,
      String sourceVersion,
      String sequence,
      IssuerGenerationAuthorityEvent latest)
      throws Exception {
    SourceReadback readback = readback(issuerId, generation, sourceVersion, sequence, latest, null);
    return ((IssuerAuthorityProjectionTransitions.Mutation)
            IssuerAuthorityProjectionTransitions.bootstrap(readback, "stable-time"))
        .nextProjection();
  }

  private static SourceReadback readback(
      String issuerId,
      String generation,
      String sourceVersion,
      String sequence,
      IssuerGenerationAuthorityEvent latest,
      IssuerGenerationAuthorityEvent selected)
      throws Exception {
    String sourceScope = "issuer/" + issuerId;
    String streamKey = IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + sourceScope;
    IssuerAuthoritySourceSnapshot.Builder snapshot =
        IssuerAuthoritySourceSnapshot.newBuilder()
            .setIssuerId(issuerId)
            .setSourceScope(sourceScope)
            .setOutboxStreamKey(streamKey)
            .setIssuerAuthGeneration(generation)
            .setSourceVersion(sourceVersion)
            .setOutboxSequence(sequence);
    if (latest != null) {
      snapshot.setLatestEventCanonicalJson(latest.canonicalJson());
    }
    ReadIssuerAuthorityForRuntimeResponse.Builder response =
        ReadIssuerAuthorityForRuntimeResponse.newBuilder()
            .setSchemaVersion("account-auth-issuer-source-readback/v1")
            .setTargetNamespace(NAMESPACE)
            .setRequestId(QUERY_ID)
            .setSourceSnapshot(snapshot.build());
    if (selected != null) {
      response.setRequestedEventCanonicalJson(selected.canonicalJson());
    }

    IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub =
        mock(IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.readIssuerAuthorityForRuntime(any(ReadIssuerAuthorityForRuntimeRequest.class)))
        .thenReturn(response.build());
    AccountIssuerAuthorityClient client = newClient(stub, issuerId);
    if (selected == null) {
      return client.readCurrent(QUERY_ID);
    }
    return client.readCommittedEvent(QUERY_ID, selected.outboxSequence());
  }

  private static IssuerGenerationAuthorityEvent event(
      String sequence, String generation, String sourceVersion, int identity, String issuerId) {
    String requestId = String.format(Locale.ROOT, "00000000-0000-4000-8000-%012d", identity);
    String scope = "issuer/" + issuerId;
    return IssuerGenerationAuthorityEventV1Codec.seal(
        Map.of(
            "schemaVersion", IssuerGenerationAuthorityEventV1Codec.SCHEMA_VERSION,
            "eventType", IssuerGenerationAuthorityEventV1Codec.EVENT_TYPE,
            "eventId", EVENT_ID_PREFIX + requestId,
            "requestId", requestId,
            "issuerId", issuerId,
            "sourceScope", scope,
            "outboxStreamKey", IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + scope,
            "outboxSequence", sequence,
            "issuerAuthGeneration", generation,
            "sourceVersion", sourceVersion));
  }

  private static AccountIssuerAuthorityClient newClient(
      IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub, String issuerId)
      throws Exception {
    return newClient(stub, issuerId, NAMESPACE);
  }

  private static AccountIssuerAuthorityClient newClient(
      IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub,
      String issuerId,
      String namespace)
      throws Exception {
    AccountIssuerAuthorityClient client =
        new AccountIssuerAuthorityClient(
            new ServiceEndpointsProperties(),
            mtlsProperties(),
            mock(GrpcChannelFactory.class),
            BlockingGrpcStubCustomizer.noop(),
            namespace,
            issuerId);
    Field stubField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    stubField.setAccessible(true);
    stubField.set(client, stub);
    return client;
  }

  private static CommonGrpcClientProperties mtlsProperties() {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain("certs/game-session-client.crt");
    tls.setPrivateKey("certs/game-session-client.key");
    tls.setCaCert("certs/account-ca.crt");
    return tls;
  }
}
