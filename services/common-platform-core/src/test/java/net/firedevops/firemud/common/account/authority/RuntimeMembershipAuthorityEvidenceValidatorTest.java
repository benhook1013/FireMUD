package net.firedevops.firemud.common.account.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AccountSecurityCutoff;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
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
    event.put(
        "authorityTuple",
        Map.of(
            "issuerAuthGeneration", "1",
            "accountAuthorityGeneration", "1",
            "tenantAuthorityGeneration", Map.of(tenantId, "1"),
            "membershipAuthorityGeneration", Map.of(tenantId, "1"),
            "privateRealmGrantVersions", List.of()));
    event.put("issuanceFence", "1");
    event.put("roles", List.of("player"));
    event.put("gameplayAdmissionAllowed", true);
    event.put("callerBoundAuthorityInvalidated", false);
    return MembershipAuthorityEventV1Codec.seal(event);
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
