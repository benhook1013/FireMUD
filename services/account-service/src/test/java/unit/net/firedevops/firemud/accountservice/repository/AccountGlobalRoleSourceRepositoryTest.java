package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class AccountGlobalRoleSourceRepositoryTest {
  private static final UUID ACCOUNT_UUID = UUID.fromString("11111111-1111-4111-8111-111111111111");

  private final DSLContext dsl = mock(DSLContext.class);
  private final DataSource dataSource = mock(DataSource.class);
  private final AccountGlobalRoleSourceRepository repository =
      new AccountGlobalRoleSourceRepository(dsl, dataSource);

  @Test
  void sourceReadRequiresTheCallersAccountTransaction() throws ReflectiveOperationException {
    Transactional annotation =
        AccountGlobalRoleSourceRepository.class
            .getMethod("readFreshEmptySourceForUpdate", UUID.class)
            .getAnnotation(Transactional.class);

    assertThat(annotation).isNotNull();
    assertThat(annotation.propagation()).isEqualTo(Propagation.MANDATORY);
    assertThat(annotation.isolation()).isEqualTo(Isolation.READ_COMMITTED);
  }

  @Test
  void missingOrNilAccountUuidFailsBeforeDatabaseAccess() {
    assertThatThrownBy(() -> repository.readFreshEmptySourceForUpdate(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.readFreshEmptySourceForUpdate(new UUID(0L, 0L)))
        .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(dsl, dataSource);
  }

  @Test
  void directInvocationWithoutAnActiveOwnerTransactionFailsBeforeDatabaseAccess() {
    assertThatThrownBy(() -> repository.readFreshEmptySourceForUpdate(ACCOUNT_UUID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable Account owner transaction");

    verifyNoInteractions(dsl, dataSource);
  }

  @Test
  void freshEmptySourceRetainsExactIdentityAndIndependentPositiveSourceVersion() {
    var source =
        new AccountGlobalRoleSourceRepository.FreshEmptySource(
            ACCOUNT_UUID,
            7L,
            7L,
            AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT,
            List.of(),
            1L);

    assertThat(source.accountUuid()).isEqualTo(ACCOUNT_UUID);
    assertThat(source.accountRowId()).isEqualTo(7L);
    assertThat(source.accountUuidSourceNumericId()).isEqualTo(7L);
    assertThat(source.accountUuidProvenance())
        .isEqualTo(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    assertThat(source.globalRoles()).isEmpty();
    assertThat(source.globalRoleSourceVersion()).isEqualTo(1L);
  }

  @Test
  void malformedOrNonFreshSourceValuesAreRejected() {
    assertThatThrownBy(
            () ->
                new AccountGlobalRoleSourceRepository.FreshEmptySource(
                    ACCOUNT_UUID,
                    7L,
                    8L,
                    AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT,
                    List.of(),
                    1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountGlobalRoleSourceRepository.FreshEmptySource(
                    ACCOUNT_UUID,
                    7L,
                    7L,
                    AccountIdentityProvenance.ACCOUNT_V29_MIGRATION,
                    List.of(),
                    1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountGlobalRoleSourceRepository.FreshEmptySource(
                    ACCOUNT_UUID,
                    7L,
                    7L,
                    AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT,
                    List.of("platformAdmin"),
                    1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountGlobalRoleSourceRepository.FreshEmptySource(
                    ACCOUNT_UUID,
                    7L,
                    7L,
                    AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT,
                    List.of(),
                    0L))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
