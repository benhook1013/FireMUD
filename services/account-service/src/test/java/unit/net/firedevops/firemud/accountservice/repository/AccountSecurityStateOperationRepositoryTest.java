package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository;
import net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec.AccountState;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

class AccountSecurityStateOperationRepositoryTest {
  @Test
  void storageDeniesOutsideWritableOwnerTransactionBeforeDataAccess() {
    DSLContext dsl = mock(DSLContext.class);
    var repository = new AccountSecurityStateOperationRepository(dsl);
    assertThatThrownBy(() -> repository.findByRequestId(UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable Account owner transaction");
    verifyNoInteractions(dsl);
    assertThat(
            repository
                .getClass()
                .isAnnotationPresent(org.springframework.stereotype.Repository.class))
        .isTrue();
    assertThat(
            repository.getClass().isAnnotationPresent(org.springframework.stereotype.Service.class))
        .isFalse();
  }

  @Test
  void detectorExactlyCoversIndependentCredentialFreeChangedFamilies() {
    var before = new AccountState(false, List.of("PASSWORD"), List.of(), "ACTIVE");
    var after =
        new AccountState(
            true, List.of("EMAIL_OTP", "PASSWORD"), List.of("support"), "SECURITY_LOCKED");
    assertThat(AccountSecurityStateOperationRepository.detectedKinds(before, after))
        .containsExactly(
            "EMAIL_LOGIN_ELIGIBILITY_CHANGED",
            "GLOBAL_ROLE_CHANGED",
            "LIFECYCLE_STATE_CHANGED",
            "LOGIN_AUTH_MODES_CHANGED");
    assertThat(AccountSecurityStateOperationRepository.detectedKinds(before, before)).isEmpty();
  }
}
