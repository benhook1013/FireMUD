package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountTenantRoleOperationRepositoryTest {
  private static final UUID ACTOR = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TARGET = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID TENANT = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID REQUEST = UUID.fromString("44444444-4444-4444-8444-444444444444");

  @Test
  void requestsBindEveryIdentityActionAndExpectedVersion() {
    var grant = request(AccountTenantRoleOperationRepository.Action.GRANT_DESIGNER, 7L, 9L);
    var changedAction =
        request(AccountTenantRoleOperationRepository.Action.REVOKE_DESIGNER, 7L, 9L);
    var changedActorVersion =
        request(AccountTenantRoleOperationRepository.Action.GRANT_DESIGNER, 8L, 9L);
    var changedTargetVersion =
        request(AccountTenantRoleOperationRepository.Action.GRANT_DESIGNER, 7L, 10L);

    assertThat(grant.payload()).isNotEqualTo(changedAction.payload());
    assertThat(grant.payload()).isNotEqualTo(changedActorVersion.payload());
    assertThat(grant.payload()).isNotEqualTo(changedTargetVersion.payload());
    assertThat(grant.payload())
        .isEqualTo(
            request(AccountTenantRoleOperationRepository.Action.GRANT_DESIGNER, 7L, 9L).payload());
  }

  @Test
  void transferMustBeDistinctAndExpectedVersionsPositive() {
    assertThatThrownBy(
            () ->
                new AccountTenantRoleOperationRepository.Request(
                    REQUEST,
                    ACTOR,
                    TENANT,
                    ACTOR,
                    AccountTenantRoleOperationRepository.Action.TRANSFER_TENANT_ADMIN,
                    7L,
                    7L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("distinct members");
    assertThatThrownBy(
            () ->
                new AccountTenantRoleOperationRepository.Request(
                    REQUEST,
                    ACTOR,
                    TENANT,
                    TARGET,
                    AccountTenantRoleOperationRepository.Action.GRANT_DESIGNER,
                    0L,
                    9L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }

  @Test
  void repositoryRequiresAnAccountWriteTransactionBeforeAnySql() {
    DSLContext dsl = mock(DSLContext.class);
    var repository = new AccountTenantRoleOperationRepository(dsl);
    var request = request(AccountTenantRoleOperationRepository.Action.GRANT_DESIGNER, 7L, 9L);
    boolean wasActive = TransactionSynchronizationManager.isActualTransactionActive();
    boolean wasReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    try {
      assertThatThrownBy(() -> repository.claim(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("active Account write transaction");
      assertThatThrownBy(() -> repository.findForUpdate(REQUEST))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("active Account write transaction");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(wasActive);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(wasReadOnly);
    }
    verifyNoInteractions(dsl);
  }

  @Test
  void auditEvidenceRequiresTheCanonicalRoleEventAndPayloadDigest() {
    byte[] payload = "{\"schema\":\"account-tenant-role-audit/v1\"}".getBytes();
    String digest =
        net.firedevops.firemud.accountservice.dto.AccountAuditDigest.ofPayload(
            new String(payload, java.nio.charset.StandardCharsets.UTF_8));
    assertThat(
            new AccountTenantRoleOperationRepository.AuditEvidence(
                    UUID.randomUUID(),
                    "ACCOUNT_TENANT_ROLE_CHANGED",
                    Instant.parse("2026-10-05T01:02:03Z"),
                    digest,
                    payload)
                .payload())
        .containsExactly(payload);
    assertThatThrownBy(
            () ->
                new AccountTenantRoleOperationRepository.AuditEvidence(
                    UUID.randomUUID(),
                    "ACCOUNT_JOINED_PUBLIC_PRODUCTION",
                    Instant.parse("2026-10-05T01:02:03Z"),
                    digest,
                    payload))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("digest or type");
  }

  private AccountTenantRoleOperationRepository.Request request(
      AccountTenantRoleOperationRepository.Action action,
      long expectedActorVersion,
      long expectedTargetVersion) {
    return new AccountTenantRoleOperationRepository.Request(
        REQUEST, ACTOR, TENANT, TARGET, action, expectedActorVersion, expectedTargetVersion);
  }
}
