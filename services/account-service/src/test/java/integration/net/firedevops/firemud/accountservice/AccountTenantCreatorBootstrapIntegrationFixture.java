package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapDigest;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository.Completion;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository.StoredOperation;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorDigest;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;

public final class AccountTenantCreatorBootstrapIntegrationFixture {
  private AccountTenantCreatorBootstrapIntegrationFixture() {}

  public static StoredOperation claimBootstrap(
      AccountTenantCreationBootstrapOperationRepository operations,
      Account creator,
      FreshTenantCreationEvidence creation) {
    UUID accountId = creator.getAccountUuid();
    UUID authorizationId = UUID.randomUUID();
    String authorizationDigest =
        AccountTenantCreationBootstrapDigest.sha256(
            "fixture authorization".getBytes(StandardCharsets.UTF_8));
    var evidence =
        new FreshTenantCreatorEvidence(
            1,
            creation,
            accountId,
            authorizationId,
            authorizationDigest,
            FreshTenantCreatorDigest.evidenceDigest(
                1, creation, accountId, authorizationId, authorizationDigest));
    byte[] creatorPayload = AccountTenantCreationBootstrapDigest.creatorEvidencePayload(evidence);
    byte[] sourcePayload = "fixture source snapshot".getBytes(StandardCharsets.UTF_8);
    byte[] requestPayload =
        AccountTenantCreationBootstrapDigest.requestPayload(creatorPayload, sourcePayload);
    var claim =
        operations.claim(
            evidence,
            creatorPayload,
            sourcePayload,
            AccountTenantCreationBootstrapDigest.sha256(sourcePayload),
            requestPayload,
            AccountTenantCreationBootstrapDigest.sha256(requestPayload),
            1,
            1);
    assertThat(claim.claimed()).isTrue();
    return claim.operation();
  }

  public static Completion completion(StoredOperation operation) {
    // Controlled completion receipts exercise the real storage owner's guarded transition.
    byte[] event = "fixture membership event".getBytes(StandardCharsets.UTF_8);
    byte[] audit = "fixture bootstrap audit".getBytes(StandardCharsets.UTF_8);
    byte[] result = "fixture bootstrap result".getBytes(StandardCharsets.UTF_8);
    return new Completion(
        1,
        operation.baselineMembershipVersion() + 1,
        operation.baselineMembershipAuthorityGeneration(),
        "ACTIVE",
        false,
        "[\"tenantAdmin\"]".getBytes(StandardCharsets.UTF_8),
        MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
            + "membership/"
            + operation.initiatingAccountUuid()
            + "/"
            + operation.tenantUuid(),
        operation.requestId().toString(),
        1,
        "fixture-membership-event",
        AccountTenantCreationBootstrapDigest.sha256(event),
        false,
        event,
        UUID.randomUUID(),
        "ACCOUNT_TENANT_CREATOR_BOOTSTRAPPED",
        Instant.now().truncatedTo(ChronoUnit.MICROS),
        AccountTenantCreationBootstrapDigest.sha256(audit),
        audit,
        result,
        AccountTenantCreationBootstrapDigest.sha256(result));
  }
}
