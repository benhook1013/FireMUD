package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapDigest;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.NeverJoinedMembershipSnapshot;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorDigest;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.junit.jupiter.api.Test;

/** Digest proof covers original creator qualification plus the complete fenced Account snapshot. */
class AccountTenantCreationBootstrapDigestTest {
  @Test
  void resultPayloadCarriesCompleteTenantBoundMembershipVersionMap() {
    UUID tenantUuid = UUID.randomUUID();

    byte[] result = resultPayload(tenantUuid, Map.of(tenantUuid.toString(), "2"));

    assertThat(resultJson(result)).contains("\"membershipVersion\":{\"" + tenantUuid + "\":\"2\"}");
  }

  @Test
  void resultPayloadRejectsMissingExtraAndWrongTenantMembershipVersionEntries() {
    UUID tenantUuid = UUID.randomUUID();
    UUID otherTenantUuid = UUID.randomUUID();

    assertThatThrownBy(() -> resultPayload(tenantUuid, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                resultPayload(
                    tenantUuid,
                    Map.of(tenantUuid.toString(), "2", otherTenantUuid.toString(), "3")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> resultPayload(tenantUuid, Map.of(otherTenantUuid.toString(), "2")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requestIdentityChangesWithAccountSnapshotAndRetainsCompleteAuthorityMaps() {
    UUID accountUuid = UUID.randomUUID();
    UUID tenantUuid = UUID.randomUUID();
    FreshTenantCreatorEvidence evidence = evidence(accountUuid, tenantUuid);
    NeverJoinedMembershipSnapshot first = snapshot(accountUuid, tenantUuid, "3");
    NeverJoinedMembershipSnapshot laterFence = snapshot(accountUuid, tenantUuid, "4");

    byte[] creatorPayload = AccountTenantCreationBootstrapDigest.creatorEvidencePayload(evidence);
    byte[] firstSource = AccountTenantCreationBootstrapDigest.sourceSnapshotPayload(first);
    byte[] laterSource = AccountTenantCreationBootstrapDigest.sourceSnapshotPayload(laterFence);
    Map<String, Object> tuple =
        AccountTenantCreationBootstrapDigest.authorityTupleMap(first.authorityTuple());

    assertThat(new String(creatorPayload, StandardCharsets.UTF_8))
        .contains(evidence.creationEvidence().sourceGameTenantKey())
        .contains(evidence.accountAuthorizationOperationId().toString())
        .contains(evidence.accountAuthorizationDigest());
    assertThat(firstSource).isNotEqualTo(laterSource);
    assertThat(tuple)
        .containsEntry("tenantAuthorityGeneration", Map.of(tenantUuid.toString(), "1"))
        .containsEntry("membershipAuthorityGeneration", Map.of(tenantUuid.toString(), "1"));
    assertThat(AccountTenantCreationBootstrapDigest.requestPayload(creatorPayload, firstSource))
        .isNotEqualTo(
            AccountTenantCreationBootstrapDigest.requestPayload(creatorPayload, laterSource));
  }

  private static NeverJoinedMembershipSnapshot snapshot(
      UUID accountUuid, UUID tenantUuid, String issuanceFence) {
    String account = accountUuid.toString();
    String tenant = tenantUuid.toString();
    String stream =
        MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
            + "membership/"
            + account
            + "/"
            + tenant;
    AuthorityTuple tuple =
        new AuthorityTuple(
            "1",
            "1",
            Map.of(tenant, "1"),
            Map.of(tenant, "1"),
            List.of(),
            Optional.empty(),
            Optional.empty());
    return new NeverJoinedMembershipSnapshot(
        account,
        tenant,
        Map.of(tenant, "1"),
        "1",
        tuple,
        issuanceFence,
        Instant.parse("2026-10-01T00:00:00Z"),
        stream,
        List.of(
            new OutboxCheckpointEntry("account:auth-authority:v1:account/" + account, "0"),
            new OutboxCheckpointEntry(
                "account:auth-authority:v1:issuer/" + AccountServiceImpl.ACCOUNT_JWT_ISSUER, "0"),
            new OutboxCheckpointEntry(stream, "0"),
            new OutboxCheckpointEntry("account:auth-authority:v1:tenant/" + tenant, "0")),
        List.of());
  }

  private static FreshTenantCreatorEvidence evidence(UUID accountUuid, UUID tenantUuid) {
    UUID requestId = UUID.randomUUID();
    UUID creationOperationId = UUID.randomUUID();
    UUID authorizationOperationId = UUID.randomUUID();
    String namespace = "firemud-test";
    String tenantKey = "world-source-17";
    String requestDigest =
        GameTenantCreationDigest.requestDigest(namespace, requestId, tenantKey, "Example", null);
    FreshTenantCreationEvidence creation =
        new FreshTenantCreationEvidence(
            1,
            namespace,
            requestId,
            creationOperationId,
            requestDigest,
            tenantUuid,
            17L,
            tenantKey,
            "NEW_GAME_ROW",
            GameTenantCreationDigest.evidenceDigest(
                namespace,
                requestId,
                creationOperationId,
                requestDigest,
                tenantUuid,
                17L,
                tenantKey,
                "NEW_GAME_ROW"));
    String authorizationDigest = "sha256:" + "6".repeat(64);
    return new FreshTenantCreatorEvidence(
        1,
        creation,
        accountUuid,
        authorizationOperationId,
        authorizationDigest,
        FreshTenantCreatorDigest.evidenceDigest(
            1, creation, accountUuid, authorizationOperationId, authorizationDigest));
  }

  private static byte[] resultPayload(UUID tenantUuid, Map<String, String> membershipVersion) {
    return AccountTenantCreationBootstrapDigest.resultPayload(
        "request-id",
        "sha256:" + "1".repeat(64),
        "sha256:" + "2".repeat(64),
        tenantUuid,
        membershipVersion,
        "1",
        "membership-stream",
        "event-request-id",
        1L,
        "event-id",
        "sha256:" + "3".repeat(64),
        new byte[] {1},
        UUID.randomUUID(),
        "ACCOUNT_TENANT_CREATOR_BOOTSTRAPPED",
        "2026-10-01T00:00:00Z",
        "sha256:" + "4".repeat(64),
        new byte[] {2},
        new byte[] {3},
        new byte[] {4});
  }

  private static String resultJson(byte[] payload) {
    ByteBuffer buffer = ByteBuffer.wrap(payload);
    int domainLength = buffer.getInt();
    buffer.position(buffer.position() + domainLength);
    int resultLength = buffer.getInt();
    byte[] result = new byte[resultLength];
    buffer.get(result);
    return new String(result, StandardCharsets.UTF_8);
  }
}
