package net.firedevops.firemud.common.account.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AccountSecurityCutoff;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.TenantBillingCutoff;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.junit.jupiter.api.Test;

class RuntimeMembershipAuthorityEvidenceValidatorTest {
  private static final String ISSUER_ID = "firemud-account-service";
  private static final String ACCOUNT_ID = "11111111-1111-4111-8111-111111111111";
  private static final String TENANT_ID = "33333333-3333-4333-8333-333333333333";
  private static final String OTHER_TENANT_ID = "44444444-4444-4444-8444-444444444444";
  private static final String PREFIX = MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX;
  private static final String ACCOUNT_STREAM = PREFIX + "account/" + ACCOUNT_ID;
  private static final String ISSUER_STREAM = PREFIX + "issuer/" + ISSUER_ID;
  private static final String TENANT_STREAM = PREFIX + "tenant/" + TENANT_ID;
  private static final String MEMBERSHIP_STREAM =
      PREFIX + "membership/" + ACCOUNT_ID + "/" + TENANT_ID;
  private static final String LARGE_COUNTER = "9223372036854775807";

  @Test
  void validatesCurrentMembershipAgainstAccountSourceAndLargeDecimalCheckpoints() {
    RuntimeMembershipAuthorityEvidenceValidator.Snapshot snapshot = currentSnapshot();

    Optional<MembershipEvent> result =
        RuntimeMembershipAuthorityEvidenceValidator.validate(snapshot);

    assertThat(result).isPresent();
    assertThat(result.orElseThrow().accountId()).isEqualTo(ACCOUNT_ID);
    assertThat(result.orElseThrow().tenantId()).isEqualTo(TENANT_ID);
    assertThat(result.orElseThrow().outboxSequence()).isEqualTo("1");
    assertThat(snapshot.checkpoints().get(0).outboxSequence()).isEqualTo(LARGE_COUNTER);
    assertThat(snapshot.authorityTuple().accountAuthorityGeneration()).isEqualTo(LARGE_COUNTER);
  }

  @Test
  void returnsEmptyOnlyForAWellFormedMissingMembershipBaseline() {
    RuntimeMembershipAuthorityEvidenceValidator.Snapshot missing = missingMembershipSnapshot();

    assertThat(RuntimeMembershipAuthorityEvidenceValidator.validate(missing)).isEmpty();

    var admitting =
        new RuntimeMembershipAuthorityEvidenceValidator.Snapshot(
            missing.issuerId(),
            missing.accountId(),
            missing.tenantId(),
            false,
            "MISSING",
            true,
            missing.membershipVersion(),
            missing.membershipAuthorityGeneration(),
            missing.roles(),
            missing.authorityTuple(),
            missing.issuanceFence(),
            missing.checkpoints(),
            missing.sourceEvidence());
    assertThatThrownBy(() -> RuntimeMembershipAuthorityEvidenceValidator.validate(admitting))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("zero checkpoint");
  }

  @Test
  void validatesAdvancedTenantSourceAsCurrentProvenanceWithoutAddingBillingAuthority() {
    var snapshot = currentSnapshotWithTenantAuthority(tenantAuthorityEvent(TENANT_ID));

    Optional<MembershipEvent> result =
        RuntimeMembershipAuthorityEvidenceValidator.validate(snapshot);

    assertThat(result).isPresent();
    assertThat(result.orElseThrow().tenantId()).isEqualTo(TENANT_ID);
    assertThat(snapshot.authorityTuple().tenantAuthorityGeneration()).containsEntry(TENANT_ID, "2");
    assertThat(snapshot.authorityTuple().tenantBillingCutoff())
        .contains(Map.of(TENANT_ID, tenantBillingCutoff(tenantAuthorityEvent(TENANT_ID))));
    assertThat(snapshot.authorityTuple().privateRealmGrantVersions()).isEmpty();
  }

  @Test
  void rejectsLowerAndHigherUnequalOpaqueTenantGenerationsWithoutOrdering() {
    var tenantEvent = tenantAuthorityEvent(TENANT_ID);
    var valid = currentSnapshotWithTenantAuthority(tenantEvent);
    var cutoff = tenantBillingCutoff(tenantEvent);

    for (String unequalGeneration : List.of("1", "3")) {
      MembershipEvent membership =
          membershipEvent(ACCOUNT_ID, TENANT_ID, unequalGeneration, cutoff);
      assertThatThrownBy(
              () ->
                  RuntimeMembershipAuthorityEvidenceValidator.validate(
                      copyWithMembershipSource(valid, membership)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("membership source tenantAuthorityGeneration current equality");
    }
  }

  @Test
  void validatesCurrentBillingCutoffAgainstTheExactTenantSourceAndCheckpoint() {
    var tenantEvent = tenantAuthorityEvent(TENANT_ID);
    var valid = currentSnapshotWithTenantAuthority(tenantEvent);
    var goodTuple = valid.authorityTuple();
    var wrongCutoff =
        new TenantBillingCutoff(
            Long.toString(tenantEvent.tenantAuthorityGeneration()),
            Long.toString(tenantEvent.tenantBillingSequence() + 1),
            tenantEvent.outboxStreamKey(),
            Long.toString(tenantEvent.outboxSequence()));
    var tupleWithWrongCutoff =
        new AuthorityTuple(
            goodTuple.issuerAuthGeneration(),
            goodTuple.accountAuthorityGeneration(),
            goodTuple.tenantAuthorityGeneration(),
            goodTuple.membershipAuthorityGeneration(),
            goodTuple.privateRealmGrantVersions(),
            goodTuple.accountSecurityCutoff(),
            Optional.of(Map.of(TENANT_ID, wrongCutoff)));

    assertThatThrownBy(
            () ->
                RuntimeMembershipAuthorityEvidenceValidator.validate(
                    copyWithTenantTuple(valid, tupleWithWrongCutoff)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("current tenant cutoff billing sequence");
  }

  @Test
  void rejectsDifferentSameGenerationHistoricalBillingCutoff() {
    var tenantEvent = tenantAuthorityEvent(TENANT_ID);
    var valid = currentSnapshotWithTenantAuthority(tenantEvent);
    var actual = tenantBillingCutoff(tenantEvent);
    var differentHistoricalCutoff =
        new TenantBillingCutoff(
            actual.tenantAuthorityGeneration(),
            Long.toString(tenantEvent.tenantBillingSequence() + 1),
            actual.outboxStreamKey(),
            actual.outboxSequence());
    MembershipEvent membership =
        membershipEvent(ACCOUNT_ID, TENANT_ID, "2", differentHistoricalCutoff);

    assertThatThrownBy(
            () ->
                RuntimeMembershipAuthorityEvidenceValidator.validate(
                    copyWithMembershipSource(valid, membership)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("membership source tenantBillingCutoff current exact equality");
  }

  @Test
  void rejectsStaleCrossTenantAndMismatchedCheckpointTenantSourceEvidence() {
    var tenantEvent = tenantAuthorityEvent(TENANT_ID);
    var valid = currentSnapshotWithTenantAuthority(tenantEvent);
    var staleTuple =
        new AuthorityTuple(
            valid.authorityTuple().issuerAuthGeneration(),
            valid.authorityTuple().accountAuthorityGeneration(),
            Map.of(TENANT_ID, "3"),
            valid.authorityTuple().membershipAuthorityGeneration(),
            valid.authorityTuple().privateRealmGrantVersions(),
            valid.authorityTuple().accountSecurityCutoff(),
            valid.authorityTuple().tenantBillingCutoff());
    assertThatThrownBy(
            () ->
                RuntimeMembershipAuthorityEvidenceValidator.validate(
                    copyWithTenantSource(valid, staleTuple, "1", valid.sourceEvidence().get(2))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("current tenant authority generation");

    var otherTenantEvent = tenantAuthorityEvent(OTHER_TENANT_ID);
    var crossTenantCarrier =
        new RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence(
            TENANT_STREAM,
            "1",
            otherTenantEvent.eventId().toString(),
            otherTenantEvent.eventDigest(),
            new String(otherTenantEvent.payload(), java.nio.charset.StandardCharsets.UTF_8));
    assertThatThrownBy(
            () ->
                RuntimeMembershipAuthorityEvidenceValidator.validate(
                    copyWithTenantSource(valid, valid.authorityTuple(), "1", crossTenantCarrier)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source event stream key");

    assertThatThrownBy(
            () ->
                RuntimeMembershipAuthorityEvidenceValidator.validate(
                    copyWithTenantSource(
                        valid, valid.authorityTuple(), "2", valid.sourceEvidence().get(2))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sourceEvidence sequence must equal its exact current checkpoint");
  }

  @Test
  void rejectsCrossTenantFreshSourceAndBillingReceiptReferencesAtCodecBoundary() {
    var source = tenantCreationEvidence(UUID.fromString(TENANT_ID));
    UUID requestId = UUID.fromString("55555555-5555-4555-8555-555555555555");
    String requestDigest = "sha256:" + "5".repeat(64);
    var authorityRequest =
        new TenantAuthorityEventV1Codec.AuthorityRequest(
            requestId,
            requestDigest,
            UUID.fromString(TENANT_ID),
            source.creationRequestId(),
            source.requestDigest());
    var billingReceipt =
        new TenantAuthorityEventV1Codec.BillingReceipt(
            UUID.fromString(TENANT_ID),
            requestId,
            requestDigest,
            TenantAuthorityEventV1Codec.tenantBillingStreamKey(UUID.fromString(OTHER_TENANT_ID)),
            1L,
            TenantAuthorityEventV1Codec.tenantBillingEventId(requestId),
            "sha256:" + "6".repeat(64));

    assertThatThrownBy(
            () ->
                TenantAuthorityEventV1Codec.seal(
                    authorityRequest, source, 2L, 2L, 1L, billingReceipt))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("billing request");

    var otherTenantSource = tenantCreationEvidence(UUID.fromString(OTHER_TENANT_ID));
    assertThatThrownBy(
            () ->
                TenantAuthorityEventV1Codec.seal(
                    authorityRequest,
                    otherTenantSource,
                    2L,
                    2L,
                    1L,
                    new TenantAuthorityEventV1Codec.BillingReceipt(
                        UUID.fromString(TENANT_ID),
                        requestId,
                        requestDigest,
                        TenantAuthorityEventV1Codec.tenantBillingStreamKey(
                            UUID.fromString(TENANT_ID)),
                        1L,
                        TenantAuthorityEventV1Codec.tenantBillingEventId(requestId),
                        "sha256:" + "6".repeat(64))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact source");
  }

  @Test
  void deniesMismatchedSourceScopeOriginalCarrierDigestAndMembershipVersion() {
    var valid = currentSnapshot();
    var currentAccount =
        PasswordResetAuthorityEventV1Codec.verify(
            valid.sourceEvidence().get(0).canonicalEventJson());
    var forgedAccountCarrier =
        new RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence(
            ACCOUNT_STREAM,
            LARGE_COUNTER,
            currentAccount.eventId(),
            "sha256:" + "0".repeat(64),
            currentAccount.canonicalJson());
    assertThatThrownBy(
            () ->
                RuntimeMembershipAuthorityEvidenceValidator.validate(
                    copyWith(
                        valid,
                        valid.checkpoints(),
                        List.of(forgedAccountCarrier, valid.sourceEvidence().get(1)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source carrier eventDigest");

    var alteredCanonicalBytes =
        new RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence(
            ACCOUNT_STREAM,
            LARGE_COUNTER,
            currentAccount.eventId(),
            currentAccount.eventDigest(),
            " " + currentAccount.canonicalJson());
    assertThatThrownBy(
            () ->
                RuntimeMembershipAuthorityEvidenceValidator.validate(
                    copyWith(
                        valid,
                        valid.checkpoints(),
                        List.of(alteredCanonicalBytes, valid.sourceEvidence().get(1)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source carrier canonical event bytes");

    MembershipEvent otherScope = membershipEvent(ACCOUNT_ID, OTHER_TENANT_ID);
    var mismatchedScopeCarrier =
        new RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence(
            MEMBERSHIP_STREAM,
            "1",
            otherScope.eventId(),
            otherScope.eventDigest(),
            otherScope.canonicalJson());
    assertThatThrownBy(
            () ->
                RuntimeMembershipAuthorityEvidenceValidator.validate(
                    copyWith(
                        valid,
                        valid.checkpoints(),
                        List.of(valid.sourceEvidence().get(0), mismatchedScopeCarrier))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source event stream key");

    var changedVersion =
        new RuntimeMembershipAuthorityEvidenceValidator.Snapshot(
            valid.issuerId(),
            valid.accountId(),
            valid.tenantId(),
            valid.membershipExists(),
            valid.membershipLifecycleState(),
            valid.gameplayAdmissionAllowed(),
            Map.of(TENANT_ID, "2"),
            valid.membershipAuthorityGeneration(),
            valid.roles(),
            valid.authorityTuple(),
            valid.issuanceFence(),
            valid.checkpoints(),
            valid.sourceEvidence());
    assertThatThrownBy(() -> RuntimeMembershipAuthorityEvidenceValidator.validate(changedVersion))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("membershipVersion");
  }

  @Test
  void preservesMalformedResetDigestDiagnosticWhenSecurityStateVerificationFails() {
    var valid = currentSnapshot();
    var accountSource = valid.sourceEvidence().get(0);
    String malformedResetDigest =
        accountSource
            .canonicalEventJson()
            .replaceFirst("sha256:[0-9a-f]{64}", "sha256:" + "0".repeat(64));
    var malformedSource =
        new RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence(
            accountSource.outboxStreamKey(),
            accountSource.outboxSequence(),
            accountSource.eventId(),
            accountSource.eventDigest(),
            malformedResetDigest);

    assertThatThrownBy(
            () ->
                RuntimeMembershipAuthorityEvidenceValidator.validate(
                    copyWith(
                        valid,
                        valid.checkpoints(),
                        List.of(malformedSource, valid.sourceEvidence().get(1)))))
        .isInstanceOf(IllegalArgumentException.class)
        .satisfies(
            failure ->
                assertThat(failure.getSuppressed())
                    .anySatisfy(
                        diagnostic ->
                            assertThat(diagnostic.getMessage())
                                .contains("eventDigest")
                                .contains("canonical event preimage")));
  }

  @Test
  void reportsAccountSourceFailureWithEverySupportedDecoderDiagnostic() {
    var valid = currentSnapshot();
    var accountSource = valid.sourceEvidence().get(0);
    var malformedSource =
        new RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence(
            accountSource.outboxStreamKey(),
            accountSource.outboxSequence(),
            accountSource.eventId(),
            accountSource.eventDigest(),
            "{\"unsupported\":true}");

    assertThatThrownBy(
            () ->
                RuntimeMembershipAuthorityEvidenceValidator.validate(
                    copyWith(
                        valid,
                        valid.checkpoints(),
                        List.of(malformedSource, valid.sourceEvidence().get(1)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("account source")
        .hasMessageContaining("supported Account event schema")
        .satisfies(
            failure -> {
              assertThat(failure.getSuppressed()).hasSize(4);
              assertThat(failure.getSuppressed())
                  .allSatisfy(
                      diagnostic -> assertThat(diagnostic.getMessage()).isNotNull().isNotBlank());
            });
  }

  @Test
  void requiresSortedExactCheckpointAndSourceInventories() {
    var valid = currentSnapshot();
    List<RuntimeMembershipAuthorityEvidenceValidator.Checkpoint> reversed =
        new ArrayList<>(valid.checkpoints());
    java.util.Collections.reverse(reversed);
    assertThatThrownBy(
            () ->
                RuntimeMembershipAuthorityEvidenceValidator.validate(
                    copyWith(valid, reversed, valid.sourceEvidence())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sorted by stream key");

    List<RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence> duplicateSource =
        new ArrayList<>(valid.sourceEvidence());
    duplicateSource.add(valid.sourceEvidence().get(0));
    assertThatThrownBy(
            () ->
                RuntimeMembershipAuthorityEvidenceValidator.validate(
                    copyWith(valid, valid.checkpoints(), duplicateSource)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sourceEvidence");
  }

  private static RuntimeMembershipAuthorityEvidenceValidator.Snapshot currentSnapshot() {
    var accountEvent = PasswordResetAuthorityEventV1Codec.seal(accountSourcePreimage());
    MembershipEvent memberEvent = membershipEvent(ACCOUNT_ID, TENANT_ID);
    var currentCutoff = new AccountSecurityCutoff(LARGE_COUNTER, ACCOUNT_STREAM, LARGE_COUNTER);
    AuthorityTuple currentTuple =
        new AuthorityTuple(
            "1",
            LARGE_COUNTER,
            Map.of(TENANT_ID, "1"),
            Map.of(TENANT_ID, "1"),
            List.of(),
            Optional.of(currentCutoff),
            Optional.empty());
    return new RuntimeMembershipAuthorityEvidenceValidator.Snapshot(
        ISSUER_ID,
        ACCOUNT_ID,
        TENANT_ID,
        true,
        "ACTIVE",
        true,
        Map.of(TENANT_ID, "1"),
        "1",
        List.of("player"),
        currentTuple,
        "1",
        List.of(
            new RuntimeMembershipAuthorityEvidenceValidator.Checkpoint(
                ACCOUNT_STREAM, LARGE_COUNTER),
            new RuntimeMembershipAuthorityEvidenceValidator.Checkpoint(ISSUER_STREAM, "0"),
            new RuntimeMembershipAuthorityEvidenceValidator.Checkpoint(MEMBERSHIP_STREAM, "1"),
            new RuntimeMembershipAuthorityEvidenceValidator.Checkpoint(TENANT_STREAM, "0")),
        List.of(
            new RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence(
                ACCOUNT_STREAM,
                LARGE_COUNTER,
                accountEvent.eventId(),
                accountEvent.eventDigest(),
                accountEvent.canonicalJson()),
            new RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence(
                MEMBERSHIP_STREAM,
                "1",
                memberEvent.eventId(),
                memberEvent.eventDigest(),
                memberEvent.canonicalJson())));
  }

  private static RuntimeMembershipAuthorityEvidenceValidator.Snapshot
      currentSnapshotWithTenantAuthority(TenantAuthorityEventV1Codec.Event tenantEvent) {
    var current = currentSnapshot();
    MembershipEvent currentMembership =
        membershipEvent(ACCOUNT_ID, TENANT_ID, "2", tenantBillingCutoff(tenantEvent));
    var tenantCheckpoint =
        new RuntimeMembershipAuthorityEvidenceValidator.Checkpoint(TENANT_STREAM, "1");
    var tenantSource =
        new RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence(
            TENANT_STREAM,
            "1",
            tenantEvent.eventId().toString(),
            tenantEvent.eventDigest(),
            new String(tenantEvent.payload(), java.nio.charset.StandardCharsets.UTF_8));
    var currentCutoff = new AccountSecurityCutoff(LARGE_COUNTER, ACCOUNT_STREAM, LARGE_COUNTER);
    TenantBillingCutoff currentTenantCutoff = tenantBillingCutoff(tenantEvent);
    AuthorityTuple currentTuple =
        new AuthorityTuple(
            "1",
            LARGE_COUNTER,
            Map.of(TENANT_ID, "2"),
            Map.of(TENANT_ID, "1"),
            List.of(),
            Optional.of(currentCutoff),
            Optional.of(Map.of(TENANT_ID, currentTenantCutoff)));
    return new RuntimeMembershipAuthorityEvidenceValidator.Snapshot(
        current.issuerId(),
        current.accountId(),
        current.tenantId(),
        true,
        "ACTIVE",
        true,
        Map.of(TENANT_ID, "1"),
        "1",
        List.of("player"),
        currentTuple,
        "1",
        List.of(
            current.checkpoints().get(0),
            current.checkpoints().get(1),
            current.checkpoints().get(2),
            tenantCheckpoint),
        List.of(
            current.sourceEvidence().get(0),
            new RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence(
                MEMBERSHIP_STREAM,
                "1",
                currentMembership.eventId(),
                currentMembership.eventDigest(),
                currentMembership.canonicalJson()),
            tenantSource));
  }

  private static RuntimeMembershipAuthorityEvidenceValidator.Snapshot copyWithMembershipSource(
      RuntimeMembershipAuthorityEvidenceValidator.Snapshot snapshot, MembershipEvent event) {
    List<RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence> sources =
        new ArrayList<>(snapshot.sourceEvidence());
    sources.set(
        1,
        new RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence(
            MEMBERSHIP_STREAM,
            event.outboxSequence(),
            event.eventId(),
            event.eventDigest(),
            event.canonicalJson()));
    return new RuntimeMembershipAuthorityEvidenceValidator.Snapshot(
        snapshot.issuerId(),
        snapshot.accountId(),
        snapshot.tenantId(),
        snapshot.membershipExists(),
        snapshot.membershipLifecycleState(),
        snapshot.gameplayAdmissionAllowed(),
        snapshot.membershipVersion(),
        snapshot.membershipAuthorityGeneration(),
        snapshot.roles(),
        snapshot.authorityTuple(),
        snapshot.issuanceFence(),
        snapshot.checkpoints(),
        sources);
  }

  private static RuntimeMembershipAuthorityEvidenceValidator.Snapshot copyWithTenantTuple(
      RuntimeMembershipAuthorityEvidenceValidator.Snapshot snapshot, AuthorityTuple tuple) {
    return new RuntimeMembershipAuthorityEvidenceValidator.Snapshot(
        snapshot.issuerId(),
        snapshot.accountId(),
        snapshot.tenantId(),
        snapshot.membershipExists(),
        snapshot.membershipLifecycleState(),
        snapshot.gameplayAdmissionAllowed(),
        snapshot.membershipVersion(),
        snapshot.membershipAuthorityGeneration(),
        snapshot.roles(),
        tuple,
        snapshot.issuanceFence(),
        snapshot.checkpoints(),
        snapshot.sourceEvidence());
  }

  private static RuntimeMembershipAuthorityEvidenceValidator.Snapshot copyWithTenantSource(
      RuntimeMembershipAuthorityEvidenceValidator.Snapshot snapshot,
      AuthorityTuple currentTuple,
      String tenantCheckpointSequence,
      RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence tenantSource) {
    return new RuntimeMembershipAuthorityEvidenceValidator.Snapshot(
        snapshot.issuerId(),
        snapshot.accountId(),
        snapshot.tenantId(),
        snapshot.membershipExists(),
        snapshot.membershipLifecycleState(),
        snapshot.gameplayAdmissionAllowed(),
        snapshot.membershipVersion(),
        snapshot.membershipAuthorityGeneration(),
        snapshot.roles(),
        currentTuple,
        snapshot.issuanceFence(),
        List.of(
            snapshot.checkpoints().get(0),
            snapshot.checkpoints().get(1),
            snapshot.checkpoints().get(2),
            new RuntimeMembershipAuthorityEvidenceValidator.Checkpoint(
                TENANT_STREAM, tenantCheckpointSequence)),
        List.of(snapshot.sourceEvidence().get(0), snapshot.sourceEvidence().get(1), tenantSource));
  }

  private static RuntimeMembershipAuthorityEvidenceValidator.Snapshot missingMembershipSnapshot() {
    AuthorityTuple baselineTuple =
        new AuthorityTuple(
            "1",
            "1",
            Map.of(TENANT_ID, "1"),
            Map.of(TENANT_ID, "1"),
            List.of(),
            Optional.empty(),
            Optional.empty());
    return new RuntimeMembershipAuthorityEvidenceValidator.Snapshot(
        ISSUER_ID,
        ACCOUNT_ID,
        TENANT_ID,
        false,
        "MISSING",
        false,
        Map.of(TENANT_ID, "1"),
        "1",
        List.of(),
        baselineTuple,
        "1",
        List.of(
            new RuntimeMembershipAuthorityEvidenceValidator.Checkpoint(ACCOUNT_STREAM, "0"),
            new RuntimeMembershipAuthorityEvidenceValidator.Checkpoint(ISSUER_STREAM, "0"),
            new RuntimeMembershipAuthorityEvidenceValidator.Checkpoint(MEMBERSHIP_STREAM, "0"),
            new RuntimeMembershipAuthorityEvidenceValidator.Checkpoint(TENANT_STREAM, "0")),
        List.of());
  }

  private static Map<String, Object> accountSourcePreimage() {
    return Map.ofEntries(
        Map.entry("schemaVersion", PasswordResetAuthorityEventV1Codec.SCHEMA_VERSION),
        Map.entry("eventType", PasswordResetAuthorityEventV1Codec.EVENT_TYPE),
        Map.entry("eventId", "password-reset-current-source-event"),
        Map.entry("requestId", "password-reset-request"),
        Map.entry("accountId", ACCOUNT_ID),
        Map.entry("sourceScope", "account/" + ACCOUNT_ID),
        Map.entry("outboxStreamKey", ACCOUNT_STREAM),
        Map.entry("outboxSequence", LARGE_COUNTER),
        Map.entry("accountAuthorityGeneration", LARGE_COUNTER),
        Map.entry("sourceVersion", LARGE_COUNTER),
        Map.entry(
            "accountSecurityCutoff",
            Map.of(
                "accountAuthorityGeneration", LARGE_COUNTER,
                "outboxStreamKey", ACCOUNT_STREAM,
                "outboxSequence", LARGE_COUNTER)));
  }

  private static MembershipEvent membershipEvent(String accountId, String tenantId) {
    return membershipEvent(accountId, tenantId, "1");
  }

  private static MembershipEvent membershipEvent(
      String accountId, String tenantId, String tenantAuthorityGeneration) {
    return membershipEvent(accountId, tenantId, tenantAuthorityGeneration, null);
  }

  private static MembershipEvent membershipEvent(
      String accountId,
      String tenantId,
      String tenantAuthorityGeneration,
      TenantBillingCutoff cutoff) {
    String stream = PREFIX + "membership/" + accountId + "/" + tenantId;
    Map<String, Object> event = new java.util.LinkedHashMap<>();
    event.put("schemaVersion", MembershipAuthorityEventV1Codec.SCHEMA_VERSION);
    event.put("eventType", MembershipAuthorityEventV1Codec.EVENT_TYPE);
    event.put("eventId", "membership-event-1");
    event.put("requestId", "membership-request-1");
    event.put("outboxStreamKey", stream);
    event.put("outboxSequence", "1");
    event.put("sourceScope", "membership/" + accountId + "/" + tenantId);
    event.put("accountId", accountId);
    event.put("tenantId", tenantId);
    event.put("membershipExists", true);
    event.put("membershipLifecycleState", "ACTIVE");
    event.put("membershipVersion", Map.of(tenantId, "1"));
    event.put("membershipAuthorityGeneration", "1");
    Map<String, Object> authorityTuple = new java.util.LinkedHashMap<>();
    authorityTuple.put("issuerAuthGeneration", "1");
    authorityTuple.put("accountAuthorityGeneration", "1");
    authorityTuple.put("tenantAuthorityGeneration", Map.of(tenantId, tenantAuthorityGeneration));
    authorityTuple.put("membershipAuthorityGeneration", Map.of(tenantId, "1"));
    authorityTuple.put("privateRealmGrantVersions", List.of());
    if (cutoff != null) {
      authorityTuple.put(
          "tenantBillingCutoff",
          Map.of(
              tenantId,
              Map.of(
                  "tenantAuthorityGeneration", cutoff.tenantAuthorityGeneration(),
                  "tenantBillingSequence", cutoff.tenantBillingSequence(),
                  "outboxStreamKey", cutoff.outboxStreamKey(),
                  "outboxSequence", cutoff.outboxSequence())));
    }
    event.put("authorityTuple", authorityTuple);
    event.put("issuanceFence", "1");
    event.put("roles", List.of("player"));
    event.put("gameplayAdmissionAllowed", true);
    event.put("callerBoundAuthorityInvalidated", false);
    return MembershipAuthorityEventV1Codec.seal(event);
  }

  private static TenantAuthorityEventV1Codec.Event tenantAuthorityEvent(String tenantId) {
    UUID canonicalTenantId = UUID.fromString(tenantId);
    FreshTenantCreationEvidence source = tenantCreationEvidence(canonicalTenantId);
    UUID requestId = UUID.fromString("55555555-5555-4555-8555-555555555555");
    String requestDigest = "sha256:" + "5".repeat(64);
    var request =
        new TenantAuthorityEventV1Codec.AuthorityRequest(
            requestId,
            requestDigest,
            canonicalTenantId,
            source.creationRequestId(),
            source.requestDigest());
    var receipt =
        new TenantAuthorityEventV1Codec.BillingReceipt(
            canonicalTenantId,
            requestId,
            requestDigest,
            TenantAuthorityEventV1Codec.tenantBillingStreamKey(canonicalTenantId),
            1L,
            TenantAuthorityEventV1Codec.tenantBillingEventId(requestId),
            "sha256:" + "6".repeat(64));
    return TenantAuthorityEventV1Codec.seal(request, source, 2L, 2L, 1L, receipt);
  }

  private static TenantBillingCutoff tenantBillingCutoff(TenantAuthorityEventV1Codec.Event event) {
    return new TenantBillingCutoff(
        Long.toString(event.tenantAuthorityGeneration()),
        Long.toString(event.tenantBillingSequence()),
        event.outboxStreamKey(),
        Long.toString(event.outboxSequence()));
  }

  private static FreshTenantCreationEvidence tenantCreationEvidence(UUID tenantId) {
    UUID creationRequestId = UUID.fromString("77777777-7777-4777-8777-777777777777");
    UUID operationId = UUID.fromString("88888888-8888-4888-8888-888888888888");
    String targetNamespace = "world";
    String sourceGameTenantKey = "founder";
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            targetNamespace, creationRequestId, sourceGameTenantKey, "World", null);
    return new FreshTenantCreationEvidence(
        1,
        targetNamespace,
        creationRequestId,
        operationId,
        requestDigest,
        tenantId,
        17L,
        sourceGameTenantKey,
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            targetNamespace,
            creationRequestId,
            operationId,
            requestDigest,
            tenantId,
            17L,
            sourceGameTenantKey,
            "NEW_GAME_ROW"));
  }

  private static RuntimeMembershipAuthorityEvidenceValidator.Snapshot copyWith(
      RuntimeMembershipAuthorityEvidenceValidator.Snapshot snapshot,
      List<RuntimeMembershipAuthorityEvidenceValidator.Checkpoint> checkpoints,
      List<RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence> sources) {
    return new RuntimeMembershipAuthorityEvidenceValidator.Snapshot(
        snapshot.issuerId(),
        snapshot.accountId(),
        snapshot.tenantId(),
        snapshot.membershipExists(),
        snapshot.membershipLifecycleState(),
        snapshot.gameplayAdmissionAllowed(),
        snapshot.membershipVersion(),
        snapshot.membershipAuthorityGeneration(),
        snapshot.roles(),
        snapshot.authorityTuple(),
        snapshot.issuanceFence(),
        checkpoints,
        sources);
  }
}
