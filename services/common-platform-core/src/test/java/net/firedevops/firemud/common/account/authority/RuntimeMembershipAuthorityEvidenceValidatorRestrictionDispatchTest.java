package net.firedevops.firemud.common.account.authority;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AccountSecurityCutoff;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import org.junit.jupiter.api.Test;

class RuntimeMembershipAuthorityEvidenceValidatorRestrictionDispatchTest {
  private static final String ISSUER = "firemud-account-service";
  private static final String ACCOUNT_ID = "11111111-1111-4111-8111-111111111111";
  private static final String TENANT_ID = "22222222-2222-4222-8222-222222222222";
  private static final String REQUEST_ID = "33333333-3333-4333-8333-333333333333";
  private static final String RESULT_ID = "44444444-4444-4444-8444-444444444444";
  private static final String SOURCE_REQUEST_ID = "55555555-5555-4555-8555-555555555555";
  private static final String STREAM =
      MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "account/" + ACCOUNT_ID;

  @Test
  void restrictionVariantMayBeTheExactAccountSourceEventWithoutBecomingMembershipAuthority() {
    String eventJson =
        AccountSecurityStateAuthorityEventV1Codec.sealRestriction(
                Map.ofEntries(
                    Map.entry(
                        "schemaVersion", AccountSecurityStateAuthorityEventV1Codec.SCHEMA_VERSION),
                    Map.entry(
                        "eventType",
                        AccountSecurityStateAuthorityEventV1Codec.RESTRICTION_EVENT_TYPE),
                    Map.entry(
                        "eventId",
                        AccountSecurityStateAuthorityEventV1Codec.RESTRICTION_EVENT_ID_PREFIX
                            + REQUEST_ID),
                    Map.entry("requestId", REQUEST_ID),
                    Map.entry("accountId", ACCOUNT_ID),
                    Map.entry("sourceScope", "account/" + ACCOUNT_ID),
                    Map.entry("outboxStreamKey", STREAM),
                    Map.entry("outboxSequence", "1"),
                    Map.entry("accountAuthorityGeneration", "2"),
                    Map.entry("sourceVersion", "2"),
                    Map.entry(
                        "accountSecurityCutoff",
                        Map.of(
                            "accountAuthorityGeneration", "2",
                            "outboxStreamKey", STREAM,
                            "outboxSequence", "1")),
                    Map.entry("restrictionCategory", "platform_access_ban"),
                    Map.entry("restrictionRevision", "2"),
                    Map.entry("restrictionEnforcementEpoch", "2"),
                    Map.entry("restrictionState", "RESTRICTED"),
                    Map.entry("restrictionResultId", RESULT_ID),
                    Map.entry("restrictionRequestDigest", "sha256:" + "a".repeat(64)),
                    Map.entry("restrictionSourceKind", "LOGGING_ADMIN_MODERATION"),
                    Map.entry("restrictionSourceRequestId", SOURCE_REQUEST_ID),
                    Map.entry("restrictionSourceDigest", "sha256:" + "b".repeat(64))))
            .canonicalJson();
    AuthorityTuple tuple =
        new AuthorityTuple(
            "1",
            "2",
            Map.of(TENANT_ID, "1"),
            Map.of(TENANT_ID, "1"),
            List.of(),
            Optional.of(new AccountSecurityCutoff("2", STREAM, "1")),
            Optional.empty());
    RuntimeMembershipAuthorityEvidenceValidator.Snapshot snapshot =
        new RuntimeMembershipAuthorityEvidenceValidator.Snapshot(
            ISSUER,
            ACCOUNT_ID,
            TENANT_ID,
            false,
            "MISSING",
            false,
            Map.of(TENANT_ID, "1"),
            "1",
            List.of(),
            tuple,
            "2",
            List.of(
                new RuntimeMembershipAuthorityEvidenceValidator.Checkpoint(
                    MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "account/" + ACCOUNT_ID,
                    "1"),
                new RuntimeMembershipAuthorityEvidenceValidator.Checkpoint(
                    MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "issuer/" + ISSUER, "0"),
                new RuntimeMembershipAuthorityEvidenceValidator.Checkpoint(
                    MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
                        + "membership/"
                        + ACCOUNT_ID
                        + "/"
                        + TENANT_ID,
                    "0"),
                new RuntimeMembershipAuthorityEvidenceValidator.Checkpoint(
                    MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "tenant/" + TENANT_ID,
                    "0")),
            List.of(
                new RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence(
                    STREAM,
                    "1",
                    AccountSecurityStateAuthorityEventV1Codec.RESTRICTION_EVENT_ID_PREFIX
                        + REQUEST_ID,
                    AccountSecurityStateAuthorityEventV1Codec.verifyRestriction(eventJson)
                        .eventDigest(),
                    eventJson)));

    assertThat(RuntimeMembershipAuthorityEvidenceValidator.validate(snapshot)).isEmpty();
  }
}
