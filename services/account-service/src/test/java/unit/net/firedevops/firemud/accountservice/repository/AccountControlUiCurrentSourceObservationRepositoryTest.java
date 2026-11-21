package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.service.AccountGenerationProjection;
import net.firedevops.firemud.accountservice.service.IssuerGenerationProjection;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.jooq.ConnectionRunnable;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountControlUiCurrentSourceObservationRepositoryTest {
  private static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final String ISSUER = GameSessionAccountDelegationProfile.ISSUER;
  private final DSLContext dsl = mock(DSLContext.class);
  private final AccountRepository accounts = mock(AccountRepository.class);
  private final AccountAuthoritySourceEvidenceRepository sources =
      mock(AccountAuthoritySourceEvidenceRepository.class);
  private final AccountControlUiCurrentSourceObservationRepository observer =
      new AccountControlUiCurrentSourceObservationRepository(dsl, accounts, sources);

  @AfterEach
  void clearContext() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void absentAndReadOnlyOwnerTransactionsDenyBeforeJdbcOrOwnerLookup() {
    assertThatThrownBy(() -> observer.observeExisting(ID))
        .isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertThatThrownBy(() -> observer.observeExisting(ID))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(dsl, accounts, sources);
  }

  @ParameterizedTest
  @ValueSource(strings = {"autocommit", "readOnly", "isolation"})
  void invalidJdbcContextDeniesBeforeAnyOwnerLookup(String condition) throws Exception {
    var connection = writableConnection();
    when(connection.getAutoCommit()).thenReturn(condition.equals("autocommit"));
    when(connection.isReadOnly()).thenReturn(condition.equals("readOnly"));
    when(connection.getTransactionIsolation())
        .thenReturn(
            condition.equals("isolation")
                ? Connection.TRANSACTION_REPEATABLE_READ
                : Connection.TRANSACTION_READ_COMMITTED);
    assertThatThrownBy(() -> observer.observeExisting(ID))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(accounts, sources);
  }

  @Test
  void absentExistingAccountNeverEnrollsOrReadsUpstream() throws Exception {
    writableConnection();
    when(accounts.findByAccountUuidForUpdate(ID)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> observer.observeExisting(ID))
        .isInstanceOf(IllegalStateException.class);
    verify(accounts).findByAccountUuidForUpdate(ID);
    verifyNoMoreInteractions(accounts);
    verifyNoInteractions(sources);
  }

  @ParameterizedTest
  @ValueSource(strings = {"identity", "numericProvenance", "lifecycle"})
  void mismatchedAccountProvenanceDeniesBeforeSourceRead(String condition) throws Exception {
    writableConnection();
    Account account = account();
    if (condition.equals("identity")) account.setAccountUuid(UUID.randomUUID());
    if (condition.equals("numericProvenance")) account.setAccountUuidSourceNumericId(43L);
    if (condition.equals("lifecycle"))
      account.setLifecycleState(AccountLifecycleState.SECURITY_LOCKED);
    when(accounts.findByAccountUuidForUpdate(ID)).thenReturn(Optional.of(account));
    assertThatThrownBy(() -> observer.observeExisting(ID))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(sources);
  }

  @Test
  void exactExistingCorrelatedSourcesReturnOnlyInformationalObservation() throws Exception {
    writableConnection();
    var snapshot = correlatedSources();
    var roles = roles();
    when(dsl.fetchOne(anyString(), eq(ID))).thenReturn(roles);
    var observation = observer.observeExisting(ID);
    assertThat(observation.accountId()).isEqualTo(ID);
    assertThat(observation.sources()).isSameAs(snapshot);
    assertThat(observation.globalRoleSourceVersion()).isEqualTo(3L);
    assertThat(observation.toString()).contains("non-authorizing");
    verify(accounts).findByAccountUuidForUpdate(ID);
    verify(sources).readCurrentIssuerAccountSources(ISSUER, ID);
    verifyNoMoreInteractions(accounts, sources);
  }

  @Test
  void mismatchedSourceNumericIdentityDeniesBeforeRoleRead() throws Exception {
    writableConnection();
    var snapshot = correlatedSources();
    when(snapshot.account().accountSourceNumericId()).thenReturn(43L);
    assertThatThrownBy(() -> observer.observeExisting(ID))
        .isInstanceOf(IllegalStateException.class);
    verify(dsl).connection(any(ConnectionRunnable.class));
    verifyNoMoreInteractions(dsl);
  }

  @ParameterizedTest
  @ValueSource(strings = {"identity", "numericProvenance", "provenance", "roles", "version"})
  void contradictoryRoleOwnerCannotBecomeObservation(String condition) throws Exception {
    writableConnection();
    correlatedSources();
    var roleRow = roles();
    if (condition.equals("identity"))
      when(roleRow.get("account_uuid", UUID.class)).thenReturn(UUID.randomUUID());
    if (condition.equals("numericProvenance"))
      when(roleRow.get("account_uuid_source_numeric_id", Long.class)).thenReturn(43L);
    if (condition.equals("provenance"))
      when(roleRow.get("account_uuid_provenance", String.class)).thenReturn("OTHER");
    if (condition.equals("roles"))
      when(roleRow.get("global_roles", String[].class)).thenReturn(new String[] {"ADMIN"});
    if (condition.equals("version"))
      when(roleRow.get("global_role_source_version", Long.class)).thenReturn(0L);
    when(dsl.fetchOne(anyString(), eq(ID))).thenReturn(roleRow);
    assertThatThrownBy(() -> observer.observeExisting(ID))
        .isInstanceOf(IllegalStateException.class);
  }

  private Connection writableConnection() throws Exception {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    Connection connection = mock(Connection.class);
    when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
    doAnswer(
            invocation -> {
              invocation.getArgument(0, ConnectionRunnable.class).run(connection);
              return null;
            })
        .when(dsl)
        .connection(any(ConnectionRunnable.class));
    return connection;
  }

  private static Account account() {
    Account account = new Account();
    account.setId(42L);
    account.setAccountUuid(ID);
    account.setAccountUuidSourceNumericId(42L);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    account.setLifecycleState(AccountLifecycleState.ACTIVE);
    return account;
  }

  private IssuerAccountSourceSnapshot correlatedSources() {
    when(accounts.findByAccountUuidForUpdate(ID)).thenReturn(Optional.of(account()));
    var snapshot = mock(IssuerAccountSourceSnapshot.class);
    var issuer = mock(CurrentSourceEvidence.class);
    var source = mock(CurrentSourceEvidence.class);
    var fence = new IssuanceFence(ID, 1L, 1L);
    when(snapshot.issuer()).thenReturn(issuer);
    when(issuer.scope()).thenReturn(AuthorityScope.issuer(ISSUER));
    when(snapshot.account()).thenReturn(source);
    when(source.scope()).thenReturn(AuthorityScope.account(ID));
    when(source.accountSourceNumericId()).thenReturn(42L);
    when(source.accountUuidProvenance()).thenReturn("ACCOUNT_REPOSITORY_INSERT");
    when(source.issuanceFence()).thenReturn(fence);
    when(snapshot.issuanceFence()).thenReturn(fence);
    when(snapshot.canonicalIssuerProjection()).thenReturn(mock(IssuerGenerationProjection.class));
    when(snapshot.canonicalAccountProjection()).thenReturn(mock(AccountGenerationProjection.class));
    when(sources.readCurrentIssuerAccountSources(ISSUER, ID)).thenReturn(snapshot);
    return snapshot;
  }

  private static Record roles() {
    var roles = mock(Record.class);
    when(roles.get("account_uuid", UUID.class)).thenReturn(ID);
    when(roles.get("account_uuid_source_numeric_id", Long.class)).thenReturn(42L);
    when(roles.get("account_uuid_provenance", String.class))
        .thenReturn("ACCOUNT_REPOSITORY_INSERT");
    when(roles.get("global_roles", String[].class)).thenReturn(new String[0]);
    when(roles.get("global_role_source_version", Long.class)).thenReturn(3L);
    return roles;
  }
}
