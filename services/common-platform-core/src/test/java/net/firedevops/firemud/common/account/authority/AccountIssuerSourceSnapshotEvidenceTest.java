package net.firedevops.firemud.common.account.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountIssuerSourceSnapshotEvidenceTest {
  private static final String NAMESPACE = "test";
  private static final String CALLER = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String ISSUER = AccountIssuerSourceSnapshotEvidence.ISSUER_ID;
  private static final String STREAM = AccountIssuerSourceSnapshotEvidence.ISSUER_STREAM_KEY;

  @Test
  void sequenceZeroIsTheOriginalEnrollmentAndHasNoInventedEvent() {
    var initial = snapshot(UUID.randomUUID(), "1", "1", "0", Optional.empty());

    assertThat(initial.sourceEvent()).isEmpty();
    assertThat(initial.sourceEventBytes()).isEmpty();
    assertThatThrownBy(
            () ->
                snapshot(
                    initial.reconciliationOperationId(),
                    "1",
                    "1",
                    "0",
                    event(initial.reconciliationOperationId(), "1", "2")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sequence-zero");
    assertThatThrownBy(
            () -> snapshot(initial.reconciliationOperationId(), "2", "1", "0", Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sequence-zero");
    assertThatThrownBy(
            () -> snapshot(initial.reconciliationOperationId(), "2", "2", "1", Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires sourceEvent");
  }

  @Test
  void positiveCheckpointRequiresTheExactClosedIssuerEventAndRetainsItsBytes() {
    UUID operation = UUID.randomUUID();
    String wireEvent = sealedIssuerEvent(operation, "1", "2");
    var evidence = snapshot(operation, "2", "2", "1", Optional.of(wireEvent));

    assertThat(evidence.sourceEvent()).contains(wireEvent);
    byte[] first = evidence.sourceEventBytes().orElseThrow();
    first[0] = 0;
    assertThat(evidence.sourceEventBytes().orElseThrow())
        .isEqualTo(wireEvent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThat(AccountAuthoritySourceEventV1Codec.verify(evidence.sourceEvent().orElseThrow()))
        .isInstanceOf(AccountAuthoritySourceEventV1Codec.IssuerEvent.class);
  }

  @Test
  void acceptsHistoricalEventIdentityIndependentFromReconciliationOperation() {
    UUID operation = UUID.randomUUID();
    String historicalEventId = "retained-source-event-17";
    String wireEvent = sealedIssuerEvent(historicalEventId, historicalEventId, "1", "2");

    var evidence = snapshot(operation, "2", "2", "1", Optional.of(wireEvent));

    assertThat(evidence.reconciliationOperationId().toString()).isNotEqualTo(historicalEventId);
    var issuerEvent =
        (AccountAuthoritySourceEventV1Codec.IssuerEvent)
            AccountAuthoritySourceEventV1Codec.verify(evidence.sourceEvent().orElseThrow());
    assertThat(issuerEvent.eventId()).isEqualTo(historicalEventId);
    assertThat(issuerEvent.requestId()).isEqualTo(historicalEventId);
  }

  @Test
  void rejectsEventIdentityIssuerCheckpointAndSchemaDrift() {
    UUID operation = UUID.randomUUID();
    String mismatchedEventIdentity =
        sealedIssuerEvent("retained-source-event-17", "different-request-17", "1", "2");
    String wrongCheckpointEvent = sealedIssuerEvent(operation, "2", "3");
    String accountEvent =
        AccountAuthoritySourceEventV1Codec.sealAccount(
                new AccountAuthoritySourceEventV1Codec.AccountPreimage(
                    operation.toString(),
                    operation.toString(),
                    AccountAuthoritySourceEventV1Codec.EVENT_STREAM_PREFIX
                        + "account/"
                        + "11111111-1111-4111-8111-111111111111",
                    "1",
                    "11111111-1111-4111-8111-111111111111",
                    "2",
                    "2",
                    "2",
                    "2",
                    java.util.List.of("PASSWORD_RESET"),
                    new AccountAuthoritySourceEventV1Codec.AccountState(
                        true, java.util.List.of("PASSWORD"), "player", "ACTIVE")))
            .canonicalJson();

    assertThatThrownBy(
            () -> snapshot(operation, "2", "2", "1", Optional.of(mismatchedEventIdentity)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("eventId must equal requestId");
    assertThatThrownBy(() -> snapshot(operation, "2", "2", "1", Optional.of(wrongCheckpointEvent)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exactly match");
    assertThatThrownBy(() -> snapshot(operation, "2", "2", "1", Optional.of(accountEvent)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNoncanonicalDuplicateUnknownAndTrailingEventFields() {
    UUID operation = UUID.randomUUID();
    String canonical = sealedIssuerEvent(operation, "1", "2");
    String unknown = canonical.substring(0, canonical.length() - 1) + ",\"z\":true}";
    String duplicate = canonical.replaceFirst("\\{", "{\"eventId\":\"duplicate\",");
    String noncanonical = " " + canonical;
    String trailing = canonical + " {}";

    for (String malformed : java.util.List.of(unknown, duplicate, noncanonical, trailing)) {
      assertThatThrownBy(() -> snapshot(operation, "2", "2", "1", Optional.of(malformed)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> snapshot(operation, "2", "2", "1", Optional.of(noncanonical)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wire JSON is not the exact canonical encoding");
  }

  @Test
  void comparesLargeCanonicalCountersWithoutFloatingPointOrLongCoercion() {
    UUID operation = UUID.randomUUID();
    String sequence = "9007199254740992";
    String generation = new BigInteger(sequence).add(BigInteger.ONE).toString();
    String wireEvent = sealedIssuerEvent(operation, sequence, generation);

    var evidence = snapshot(operation, generation, generation, sequence, Optional.of(wireEvent));

    assertThat(evidence.lastCommittedOutboxSequence()).isEqualTo("9007199254740992");
    assertThat(evidence.issuerAuthGeneration()).isEqualTo("9007199254740993");
  }

  @Test
  void rejectsNoncanonicalCountersWrongCallerNamespaceIssuerAndNilOperation() {
    UUID operation = UUID.randomUUID();
    assertThatThrownBy(() -> snapshot(operation, "01", "1", "0", Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical decimal");
    assertThatThrownBy(() -> snapshot(operation, "1", "1", "00", Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical decimal");
    assertThatThrownBy(
            () ->
                new AccountIssuerSourceSnapshotEvidence(
                    operation,
                    NAMESPACE,
                    "spiffe://firemud/ns/other/sa/game-session-service",
                    ISSUER,
                    "1",
                    "1",
                    STREAM,
                    "0",
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("same-namespace");
    assertThatThrownBy(
            () ->
                new AccountIssuerSourceSnapshotEvidence(
                    operation,
                    NAMESPACE,
                    CALLER,
                    "other-issuer",
                    "1",
                    "1",
                    STREAM,
                    "0",
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("supported Account issuer");
    assertThatThrownBy(() -> snapshot(new UUID(0L, 0L), "1", "1", "0", Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-nil");
  }

  private static AccountIssuerSourceSnapshotEvidence snapshot(
      UUID operation, String generation, String version, String sequence, Optional<String> event) {
    return new AccountIssuerSourceSnapshotEvidence(
        operation, NAMESPACE, CALLER, ISSUER, generation, version, STREAM, sequence, event);
  }

  private static Optional<String> event(UUID operation, String sequence, String generation) {
    return Optional.of(sealedIssuerEvent(operation, sequence, generation));
  }

  private static String sealedIssuerEvent(UUID operation, String sequence, String generation) {
    return sealedIssuerEvent(operation.toString(), operation.toString(), sequence, generation);
  }

  private static String sealedIssuerEvent(
      String eventId, String requestId, String sequence, String generation) {
    return AccountAuthoritySourceEventV1Codec.sealIssuer(
            new AccountAuthoritySourceEventV1Codec.IssuerPreimage(
                eventId,
                requestId,
                STREAM,
                sequence,
                ISSUER,
                generation,
                generation,
                "SIGNER_COMPROMISE"))
        .canonicalJson();
  }
}
