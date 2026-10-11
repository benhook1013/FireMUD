package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class AccountPasswordResetOperationRepositoryTest {
  private static final UUID ACCOUNT_UUID = UUID.fromString("c6ca3e35-9ba4-46a4-967b-f83ddb0d693e");
  private final DSLContext dsl = mock(DSLContext.class);
  private final AccountPasswordResetOperationRepository repository =
      new AccountPasswordResetOperationRepository(dsl);

  @Test
  void lookupsAndInsertionRequireAnOwnerTransaction() throws ReflectiveOperationException {
    assertMandatory(
        AccountPasswordResetOperationRepository.class.getMethod("findByTokenHash", String.class));
    assertMandatory(
        AccountPasswordResetOperationRepository.class.getMethod("findByRequestId", String.class));
    assertMandatory(
        AccountPasswordResetOperationRepository.class.getMethod(
            "insert", AccountPasswordResetOperationRepository.PasswordResetReceipt.class));

    assertThatThrownBy(() -> repository.findByTokenHash("0".repeat(64)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active Account transaction");
    verifyNoInteractions(dsl);
  }

  @Test
  void receiptEvidenceRejectsMalformedOrNonAccountBindingsBeforeStorageAccess() {
    assertThatThrownBy(() -> receipt("not-a-hash", "0".repeat(64), "0".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> receipt("0".repeat(64), "bad-digest", "0".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> receipt("0".repeat(64), "0".repeat(64), "bad-verifier"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountPasswordResetOperationRepository.PasswordResetReceipt(
                    9L,
                    ACCOUNT_UUID,
                    "0".repeat(64),
                    "PASSWORD_RESET",
                    "wrong-request-id",
                    1,
                    "1".repeat(64),
                    LocalDateTime.of(2030, 1, 2, 3, 4, 5, 123456789),
                    "2".repeat(64),
                    streamKey(),
                    1L,
                    "account-password-reset-event-v1:" + "0".repeat(64),
                    "sha256:" + "3".repeat(64),
                    2L,
                    2L,
                    3L,
                    3L))
        .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(dsl);
  }

  @Test
  void committedReceiptRetainsExactLocalDeadlineAndAccountFenceEvidence() {
    ScopeState authority =
        new ScopeState(
            AuthorityScope.account(ACCOUNT_UUID), 2L, 2L, new IssuanceFence(ACCOUNT_UUID, 4L, 3L));
    LocalDateTime localDeadline = LocalDateTime.of(2030, 1, 2, 3, 4, 5, 123456789);
    var receipt =
        AccountPasswordResetOperationRepository.PasswordResetReceipt.committed(
            9L,
            ACCOUNT_UUID,
            "0".repeat(64),
            "account-password-reset-request-v1:" + "0".repeat(64),
            "1".repeat(64),
            localDeadline,
            "2".repeat(64),
            streamKey(),
            7L,
            "account-password-reset-event-v1:" + "0".repeat(64),
            "sha256:" + "3".repeat(64),
            authority,
            authority.issuanceFence());

    assertThat(receipt.tokenExpiresAt()).isEqualTo(localDeadline);
    assertThat(receipt.issuanceFenceEvidence()).isEqualTo(new IssuanceFence(ACCOUNT_UUID, 4L, 3L));
    assertThat(receipt.outboxSequence()).isEqualTo(7L);
    verifyNoInteractions(dsl);
  }

  private static AccountPasswordResetOperationRepository.PasswordResetReceipt receipt(
      String tokenHash, String requestDigest, String verifierDigest) {
    return new AccountPasswordResetOperationRepository.PasswordResetReceipt(
        9L,
        ACCOUNT_UUID,
        tokenHash,
        "PASSWORD_RESET",
        "account-password-reset-request-v1:" + tokenHash,
        1,
        requestDigest,
        LocalDateTime.of(2030, 1, 2, 3, 4, 5),
        verifierDigest,
        streamKey(),
        1L,
        "account-password-reset-event-v1:" + tokenHash,
        "sha256:" + "3".repeat(64),
        2L,
        2L,
        3L,
        3L);
  }

  private static String streamKey() {
    return "account:auth-authority:v1:account/" + ACCOUNT_UUID;
  }

  private void assertMandatory(java.lang.reflect.Method method) {
    Transactional annotation = method.getAnnotation(Transactional.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.propagation()).isEqualTo(Propagation.MANDATORY);
  }
}
