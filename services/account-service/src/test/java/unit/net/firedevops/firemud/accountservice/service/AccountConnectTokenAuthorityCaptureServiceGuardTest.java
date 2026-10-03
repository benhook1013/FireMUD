package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceIdentity;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceRepository.ClaimResult;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.AccountConnectTokenAuthorityCaptureService;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountConnectTokenAuthorityCaptureServiceGuardTest {
  private static final UUID ACCOUNT_UUID = UUID.fromString("07891384-1a41-4f17-a135-048db46a705e");
  private static final UUID TENANT_UUID = UUID.fromString("b46c3e99-7c0c-4f25-9bc4-a3e9af5d415f");
  private static final byte[] REQUEST_DIGEST = new byte[32];

  @Test
  void captureRejectsMissingOwnerTransactionBeforeAnyCollaboratorInteraction() {
    Repositories repositories = new Repositories();
    boolean priorActive = TransactionSynchronizationManager.isActualTransactionActive();
    boolean priorReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    Integer priorIsolation =
        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);

    try {
      assertThatThrownBy(
              () -> repositories.service().capture(mock(ClaimResult.class), REQUEST_DIGEST))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("active owner transaction");
      repositories.verifyNoReads();
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(priorActive);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(priorReadOnly);
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(priorIsolation);
    }
  }

  @Test
  void captureRejectsAutocommitConnectionBeforeAnyCollaboratorInteraction() throws Exception {
    Repositories repositories = new Repositories();
    bindConnection(
        repositories.dataSource,
        true,
        Connection.TRANSACTION_READ_COMMITTED,
        false,
        "read committed",
        "off");
    TransactionState prior = captureTransactionState();

    try {
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(
              () -> repositories.service().capture(mock(ClaimResult.class), REQUEST_DIGEST))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("transaction-bound JDBC connection");
      repositories.verifyNoReads();
    } finally {
      unbindConnection(repositories.dataSource, prior);
    }
  }

  @Test
  void readRejectsNonReadCommittedJdbcConnectionBeforeAnyCollaboratorInteraction()
      throws Exception {
    Repositories repositories = new Repositories();
    bindConnection(
        repositories.dataSource,
        false,
        Connection.TRANSACTION_REPEATABLE_READ,
        false,
        "repeatable read",
        "off");
    TransactionState prior = captureTransactionState();
    AccountConnectTokenIssuanceIdentity identity =
        new AccountConnectTokenIssuanceIdentity(
            9L, TENANT_UUID, "opaque-connect-scope", "request-guard");

    try {
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> repositories.service().read(identity, REQUEST_DIGEST))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("READ COMMITTED");
      repositories.verifyNoReads();
    } finally {
      unbindConnection(repositories.dataSource, prior);
    }
  }

  @Test
  void readRejectsPostgresReadOnlyTransactionBeforeAnyCollaboratorInteraction() throws Exception {
    Repositories repositories = new Repositories();
    bindConnection(
        repositories.dataSource,
        false,
        Connection.TRANSACTION_READ_COMMITTED,
        false,
        "read committed",
        "on");
    TransactionState prior = captureTransactionState();
    AccountConnectTokenIssuanceIdentity identity =
        new AccountConnectTokenIssuanceIdentity(
            9L, TENANT_UUID, "opaque-connect-scope", "request-guard");

    try {
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> repositories.service().read(identity, REQUEST_DIGEST))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("writable owner transaction");
      repositories.verifyNoReads();
    } finally {
      unbindConnection(repositories.dataSource, prior);
    }
  }

  @Test
  void readRejectsPostgresIsolationThatDisagreesWithJdbcBeforeAnyCollaboratorInteraction()
      throws Exception {
    Repositories repositories = new Repositories();
    bindConnection(
        repositories.dataSource,
        false,
        Connection.TRANSACTION_READ_COMMITTED,
        false,
        "repeatable read",
        "off");
    TransactionState prior = captureTransactionState();
    AccountConnectTokenIssuanceIdentity identity =
        new AccountConnectTokenIssuanceIdentity(
            9L, TENANT_UUID, "opaque-connect-scope", "request-guard");

    try {
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> repositories.service().read(identity, REQUEST_DIGEST))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("READ COMMITTED");
      repositories.verifyNoReads();
    } finally {
      unbindConnection(repositories.dataSource, prior);
    }
  }

  @Test
  void readRejectsJdbcReadOnlyConnectionBeforeAnyCollaboratorInteraction() throws Exception {
    Repositories repositories = new Repositories();
    bindConnection(
        repositories.dataSource,
        false,
        Connection.TRANSACTION_READ_COMMITTED,
        true,
        "read committed",
        "off");
    TransactionState prior = captureTransactionState();
    AccountConnectTokenIssuanceIdentity identity =
        new AccountConnectTokenIssuanceIdentity(
            9L, TENANT_UUID, "opaque-connect-scope", "request-guard");

    try {
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> repositories.service().read(identity, REQUEST_DIGEST))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("writable owner transaction");
      repositories.verifyNoReads();
    } finally {
      unbindConnection(repositories.dataSource, prior);
    }
  }

  @SuppressFBWarnings(
      value = {"OBL_UNSATISFIED_OBLIGATION", "ODR_OPEN_DATABASE_RESOURCE"},
      justification = "Only Mockito JDBC mocks are created; no driver resources are acquired")
  private static void bindConnection(
      DataSource dataSource,
      boolean autoCommit,
      int isolation,
      boolean readOnly,
      String postgresIsolation,
      String postgresReadOnly)
      throws Exception {
    Connection connection = mock(Connection.class);
    Statement statement = mock(Statement.class);
    ResultSet isolationResult = oneRow(postgresIsolation);
    ResultSet readOnlyResult = oneRow(postgresReadOnly);
    when(connection.getAutoCommit()).thenReturn(autoCommit);
    when(connection.getTransactionIsolation()).thenReturn(isolation);
    when(connection.isReadOnly()).thenReturn(readOnly);
    doReturn(statement).when(connection).createStatement();
    doReturn(isolationResult).when(statement).executeQuery("SHOW transaction_isolation");
    doReturn(readOnlyResult).when(statement).executeQuery("SHOW transaction_read_only");
    TransactionSynchronizationManager.bindResource(dataSource, new ConnectionHolder(connection));
  }

  private static ResultSet oneRow(String value) throws Exception {
    ResultSet result = mock(ResultSet.class);
    when(result.next()).thenReturn(true, false);
    when(result.getString(1)).thenReturn(value);
    return result;
  }

  private static void unbindConnection(DataSource dataSource, TransactionState prior) {
    TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);
    TransactionSynchronizationManager.setActualTransactionActive(prior.active());
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(prior.readOnly());
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(prior.isolationLevel());
  }

  private static TransactionState captureTransactionState() {
    return new TransactionState(
        TransactionSynchronizationManager.isActualTransactionActive(),
        TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
  }

  private record TransactionState(boolean active, boolean readOnly, Integer isolationLevel) {}

  private static final class Repositories {
    private final AccountConnectTokenIssuanceRepository issuanceRepository =
        mock(AccountConnectTokenIssuanceRepository.class);
    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final AccountJoinOperationRepository joinOperationRepository =
        mock(AccountJoinOperationRepository.class);
    private final AccountMembershipAuthorityEventProducer membershipProducer =
        mock(AccountMembershipAuthorityEventProducer.class);
    private final AccountAuthorityGenerationRepository generationRepository =
        mock(AccountAuthorityGenerationRepository.class);
    private final DataSource dataSource = mock(DataSource.class);

    private AccountConnectTokenAuthorityCaptureService service() {
      return new AccountConnectTokenAuthorityCaptureService(
          issuanceRepository,
          accountRepository,
          joinOperationRepository,
          membershipProducer,
          generationRepository,
          dataSource);
    }

    private void verifyNoReads() {
      verifyNoInteractions(
          issuanceRepository,
          accountRepository,
          joinOperationRepository,
          membershipProducer,
          generationRepository,
          dataSource);
    }
  }
}
