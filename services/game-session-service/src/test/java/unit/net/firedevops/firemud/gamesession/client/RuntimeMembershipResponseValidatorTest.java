package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.account.v1.RuntimeAuthorityTuple;
import net.firedevops.firemud.account.v1.RuntimeMembershipBaseline;
import net.firedevops.firemud.account.v1.RuntimeOutboxCheckpoint;
import net.firedevops.firemud.account.v1.RuntimeOutboxSourceEvidence;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;

class RuntimeMembershipResponseValidatorTest {
  private static final String ACCOUNT_ID = "00000000-0000-4000-8000-000000000101";
  private static final long RETAINED_TENANT_KEY = 5L;
  private static final String TENANT_ID = "00000000-0000-0000-0000-000000000005";
  private static final String LARGE_COUNTER = "922337203685477580812345678901234567890";

  @Test
  void acceptsCurrentActiveInactiveAndNonadmittingMissingContent() {
    GetTenantMembershipForRuntimeResponse active =
        RuntimeMembershipTestFixtures.active(ACCOUNT_ID, RETAINED_TENANT_KEY, TENANT_ID, "1");
    GetTenantMembershipForRuntimeResponse inactive =
        RuntimeMembershipTestFixtures.inactive(ACCOUNT_ID, RETAINED_TENANT_KEY, "3");
    GetTenantMembershipForRuntimeResponse missing =
        RuntimeMembershipTestFixtures.missing(ACCOUNT_ID, RETAINED_TENANT_KEY);

    assertThat(RuntimeMembershipResponseValidator.validateContent(active)).isPresent();
    assertThat(RuntimeMembershipResponseValidator.validateContent(inactive)).isPresent();
    assertThat(RuntimeMembershipResponseValidator.validateContent(missing)).isEmpty();
    assertThat(missing.getMembershipExists()).isFalse();
    assertThat(missing.getGameplayAdmissionAllowed()).isFalse();
    assertThat(missing.getMembershipLifecycleState()).isEqualTo("MISSING");
  }

  @Test
  void acceptsAdvancedMembershipEventAndArbitraryPrecisionCounters() {
    GetTenantMembershipForRuntimeResponse advanced =
        RuntimeMembershipTestFixtures.left(ACCOUNT_ID, RETAINED_TENANT_KEY, List.of("player"));
    MembershipEvent historical =
        RuntimeMembershipResponseValidator.validateContent(advanced).orElseThrow();
    assertThat(historical.outboxSequence()).isEqualTo("2");
    assertThat(historical.membershipLifecycleState()).isEqualTo("INACTIVE");

    GetTenantMembershipForRuntimeResponse large = withLargeMembershipCounters(advanced);
    MembershipEvent largeEvent =
        RuntimeMembershipResponseValidator.validateContent(large).orElseThrow();

    assertThat(largeEvent.outboxSequence()).isEqualTo(LARGE_COUNTER);
    assertThat(largeEvent.membershipAuthorityGeneration()).isEqualTo(LARGE_COUNTER);
    assertThat(large.getMembershipVersionMap()).containsEntry(TENANT_ID, LARGE_COUNTER);
  }

  @Test
  void rejectsMissingSourceAndBaselineMismatch() {
    GetTenantMembershipForRuntimeResponse active =
        RuntimeMembershipTestFixtures.active(ACCOUNT_ID, RETAINED_TENANT_KEY, TENANT_ID, "1");

    assertRejected(active.toBuilder().clearOutboxSourceEvidence().build());
    assertRejected(
        active.toBuilder()
            .setMembershipBaseline(
                active.getMembershipBaseline().toBuilder().setMembershipLifecycleState("INACTIVE"))
            .build());
  }

  @Test
  void rejectsUnknownFieldsAtEveryEvidenceMessageLevel() {
    GetTenantMembershipForRuntimeResponse active =
        RuntimeMembershipTestFixtures.active(ACCOUNT_ID, RETAINED_TENANT_KEY, TENANT_ID, "1");
    UnknownFieldSet unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();

    assertRejected(active.toBuilder().setUnknownFields(unknown).build());
    assertRejected(
        active.toBuilder()
            .setMembershipBaseline(
                active.getMembershipBaseline().toBuilder().setUnknownFields(unknown))
            .build());
    assertRejected(
        active.toBuilder()
            .setAuthorityTuple(active.getAuthorityTuple().toBuilder().setUnknownFields(unknown))
            .build());
    assertRejected(
        active.toBuilder()
            .setOutboxCheckpoints(
                0, active.getOutboxCheckpoints(0).toBuilder().setUnknownFields(unknown))
            .build());
    assertRejected(
        active.toBuilder()
            .setOutboxSourceEvidence(
                0, active.getOutboxSourceEvidence(0).toBuilder().setUnknownFields(unknown))
            .build());
    assertRejected(active.toBuilder().setError(ErrorDetail.getDefaultInstance()).build());
  }

  @Test
  void rejectsUnsupportedAuthorityExtensionsAndMalformedMembershipEventIdentity() {
    GetTenantMembershipForRuntimeResponse active =
        RuntimeMembershipTestFixtures.active(ACCOUNT_ID, RETAINED_TENANT_KEY, TENANT_ID, "1");

    assertRejected(
        active.toBuilder()
            .setAuthorityTuple(
                active.getAuthorityTuple().toBuilder()
                    .addPrivateRealmGrantVersions(
                        net.firedevops.firemud.account.v1.RuntimePrivateRealmGrantVersion
                            .getDefaultInstance()))
            .build());
    assertRejected(
        active.toBuilder()
            .setAuthorityTuple(
                RuntimeAuthorityTuple.newBuilder()
                    .mergeFrom(active.getAuthorityTuple())
                    .setTenantBillingCutoff(
                        net.firedevops.firemud.account.v1.RuntimeTenantBillingCutoffMap
                            .getDefaultInstance()))
            .build());
    assertRejected(
        active.toBuilder()
            .setOutboxSourceEvidence(
                0, active.getOutboxSourceEvidence(0).toBuilder().setEventId("not-a-uuid"))
            .build());
  }

  private static void assertRejected(GetTenantMembershipForRuntimeResponse response) {
    assertThatThrownBy(() -> RuntimeMembershipResponseValidator.validateContent(response))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(RuntimeMembershipResponseValidator.hasCompleteAuthorityCarrier(response)).isFalse();
  }

  private static GetTenantMembershipForRuntimeResponse withLargeMembershipCounters(
      GetTenantMembershipForRuntimeResponse response) {
    String streamKey =
        MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
            + "membership/"
            + response.getAccountId()
            + "/"
            + response.getTenantId();
    MembershipEvent oldEvent =
        MembershipAuthorityEventV1Codec.verify(
            response.getOutboxSourceEvidence(0).getCanonicalEventJson());
    MembershipEvent largeEvent =
        MembershipAuthorityEventV1Codec.seal(
            Map.ofEntries(
                Map.entry("schemaVersion", MembershipAuthorityEventV1Codec.SCHEMA_VERSION),
                Map.entry("eventType", MembershipAuthorityEventV1Codec.EVENT_TYPE),
                Map.entry("eventId", oldEvent.eventId()),
                Map.entry("requestId", oldEvent.requestId()),
                Map.entry("outboxStreamKey", streamKey),
                Map.entry("outboxSequence", LARGE_COUNTER),
                Map.entry(
                    "sourceScope",
                    "membership/" + response.getAccountId() + "/" + response.getTenantId()),
                Map.entry("accountId", response.getAccountId()),
                Map.entry("tenantId", response.getTenantId()),
                Map.entry("membershipExists", true),
                Map.entry("membershipLifecycleState", "INACTIVE"),
                Map.entry("membershipVersion", Map.of(response.getTenantId(), LARGE_COUNTER)),
                Map.entry("membershipAuthorityGeneration", LARGE_COUNTER),
                Map.entry(
                    "authorityTuple",
                    Map.of(
                        "issuerAuthGeneration",
                        "1",
                        "accountAuthorityGeneration",
                        "1",
                        "tenantAuthorityGeneration",
                        Map.of(response.getTenantId(), "1"),
                        "membershipAuthorityGeneration",
                        Map.of(response.getTenantId(), LARGE_COUNTER),
                        "privateRealmGrantVersions",
                        List.of())),
                Map.entry("issuanceFence", LARGE_COUNTER),
                Map.entry("roles", response.getRolesList()),
                Map.entry("gameplayAdmissionAllowed", false),
                Map.entry("callerBoundAuthorityInvalidated", true)));

    RuntimeAuthorityTuple currentTuple =
        response.getAuthorityTuple().toBuilder()
            .putMembershipAuthorityGeneration(response.getTenantId(), LARGE_COUNTER)
            .build();
    RuntimeMembershipBaseline baseline =
        response.getMembershipBaseline().toBuilder()
            .putMembershipVersion(response.getTenantId(), LARGE_COUNTER)
            .setMembershipAuthorityGeneration(LARGE_COUNTER)
            .build();
    GetTenantMembershipForRuntimeResponse.Builder builder =
        response.toBuilder()
            .putMembershipVersion(response.getTenantId(), LARGE_COUNTER)
            .setMembershipAuthorityGeneration(LARGE_COUNTER)
            .setIssuanceFence(LARGE_COUNTER)
            .setAuthorityTuple(currentTuple)
            .setMembershipBaseline(baseline)
            .clearOutboxSourceEvidence()
            .addOutboxSourceEvidence(
                RuntimeOutboxSourceEvidence.newBuilder()
                    .setOutboxStreamKey(streamKey)
                    .setOutboxSequence(LARGE_COUNTER)
                    .setEventId(largeEvent.eventId())
                    .setEventDigest(largeEvent.eventDigest())
                    .setCanonicalEventJson(largeEvent.canonicalJson()));
    for (int index = 0; index < response.getOutboxCheckpointsCount(); index++) {
      RuntimeOutboxCheckpoint checkpoint = response.getOutboxCheckpoints(index);
      if (streamKey.equals(checkpoint.getOutboxStreamKey())) {
        builder.setOutboxCheckpoints(
            index, checkpoint.toBuilder().setOutboxSequence(LARGE_COUNTER));
      }
    }
    return builder.build();
  }
}
