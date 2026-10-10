package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import net.firedevops.firemud.accountservice.service.AccountGenerationProjection;
import net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec;
import org.junit.jupiter.api.Test;

class AccountGenerationProjectionRestrictionDispatchTest {
  private static final String ACCOUNT_ID = "11111111-1111-4111-8111-111111111111";
  private static final String REQUEST_ID = "22222222-2222-4222-8222-222222222222";
  private static final String STREAM = "account:auth-authority:v1:account/" + ACCOUNT_ID;

  @Test
  void acceptsOnlyTheCanonicalRestrictionVariantAsTheAccountGenerationSourceEvent() {
    String event =
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
                    Map.entry("restrictionCategory", "account_security_lock"),
                    Map.entry("restrictionRevision", "2"),
                    Map.entry("restrictionEnforcementEpoch", "2"),
                    Map.entry("restrictionState", "RESTRICTED"),
                    Map.entry("restrictionResultId", "33333333-3333-4333-8333-333333333333"),
                    Map.entry("restrictionRequestDigest", "sha256:" + "a".repeat(64)),
                    Map.entry("restrictionSourceKind", "ACCOUNT_SECURITY_POLICY"),
                    Map.entry("restrictionSourceRequestId", "44444444-4444-4444-8444-444444444444"),
                    Map.entry("restrictionSourceDigest", "sha256:" + "b".repeat(64))))
            .canonicalJson();
    AccountGenerationProjection projection =
        new AccountGenerationProjection(ACCOUNT_ID, "2", "2", STREAM, "1", Optional.of(event));

    assertThat(AccountGenerationProjection.parse(projection.toJson())).isEqualTo(projection);
    assertThat(projection.sourceEvent()).contains(event);
  }
}
