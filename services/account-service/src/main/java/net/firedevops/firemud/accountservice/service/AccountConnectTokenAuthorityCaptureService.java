package net.firedevops.firemud.accountservice.service;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipSnapshotDto;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountConnectIssuanceFenceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceIdentity;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceOperation;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceRepository.ClaimDisposition;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceRepository.ClaimResult;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unwired preparation of the Account-owned authority fence for one gameplay-connect issuance. The
 * Account fence is distinct from Gateway's replayAdmissionFence. This source capture creates no
 * token, membership, terminal operation, or runtime authorization.
 */
public final class AccountConnectTokenAuthorityCaptureService {
  private final AccountConnectTokenIssuanceRepository issuanceRepository;
  private final AccountRepository accountRepository;
  private final AccountJoinOperationRepository joinOperationRepository;
  private final AccountMembershipAuthorityEventProducer membershipAuthorityEventProducer;
  private final AccountAuthorityGenerationRepository authorityGenerationRepository;
  private final DataSource dataSource;

  public AccountConnectTokenAuthorityCaptureService(
      AccountConnectTokenIssuanceRepository issuanceRepository,
      AccountRepository accountRepository,
      AccountJoinOperationRepository joinOperationRepository,
      AccountMembershipAuthorityEventProducer membershipAuthorityEventProducer,
      AccountAuthorityGenerationRepository authorityGenerationRepository,
      DataSource dataSource) {
    this.issuanceRepository = Objects.requireNonNull(issuanceRepository, "issuanceRepository");
    this.accountRepository = Objects.requireNonNull(accountRepository, "accountRepository");
    this.joinOperationRepository =
        Objects.requireNonNull(joinOperationRepository, "joinOperationRepository");
    this.membershipAuthorityEventProducer =
        Objects.requireNonNull(
            membershipAuthorityEventProducer, "membershipAuthorityEventProducer");
    this.authorityGenerationRepository =
        Objects.requireNonNull(authorityGenerationRepository, "authorityGenerationRepository");
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  /**
   * Captures the current positive Account fence for an exact PENDING first-writer claim. The
   * selected gameplay-connect issuance path requires the exact existing ACTIVE membership snapshot;
   * generic credential LOGIN, explicit JOIN, and admission claims do not call this gate. An exact
   * replay returns the original durable capture without replacing it.
   */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public AccountConnectIssuanceFenceEvidence capture(ClaimResult claim, byte[] requestDigest) {
    Objects.requireNonNull(claim, "claim");
    byte[] checkedRequestDigest = requireRequestDigest(requestDigest);
    requireWritableReadCommittedOwnerTransaction();

    AccountConnectTokenIssuanceIdentity identity = claim.identity();
    Optional<AccountConnectIssuanceFenceEvidence> priorCapture =
        issuanceRepository.readIssuanceFenceCapture(identity, checkedRequestDigest);
    if (priorCapture.isPresent()) {
      AccountConnectIssuanceFenceEvidence captured = priorCapture.orElseThrow();
      if (!captured.operationId().equals(claim.operation().operationId())) {
        throw new IllegalStateException(
            "Connect-token replay capture belongs to another operation");
      }
      return captured;
    }
    if (claim.disposition() != ClaimDisposition.CLAIMED) {
      throw new IllegalStateException(
          "Connect-token replay has no proved original Account issuance-fence capture");
    }
    if (claim.operation().lifecycle() != AccountConnectTokenIssuanceOperation.Lifecycle.PENDING) {
      throw new IllegalStateException(
          "Only a pending connect-token operation can capture its Account issuance fence");
    }
    if (!MessageDigest.isEqual(claim.operation().requestDigest(), checkedRequestDigest)) {
      throw new AccountConnectTokenIssuanceRepository.IdempotencyConflictException(
          "Connect-token request digest differs from its first-writer claim");
    }

    long accountId = identity.accountId();
    joinOperationRepository.lockAccount(accountId);
    Account account =
        accountRepository
            .findById(accountId)
            .orElseThrow(() -> new IllegalStateException("Connect-token Account row is absent"));
    UUID accountUuid = requirePersistedAccountIdentity(account, accountId);

    RuntimeMembershipSnapshotDto membershipSnapshot =
        membershipAuthorityEventProducer.readExistingRuntimeMembershipSnapshot(
            accountUuid, identity.tenantId());
    requireExactActiveMembership(membershipSnapshot, accountUuid, identity.tenantId());
    long snapshotFence =
        parsePositiveCanonicalDecimal(
            membershipSnapshot.issuanceFence(), "snapshot issuance fence");

    ScopeState accountAuthority =
        authorityGenerationRepository.read(AuthorityScope.account(accountUuid));
    IssuanceFence durableFence = accountAuthority.issuanceFence();
    if (durableFence == null
        || !accountUuid.equals(durableFence.accountId())
        || durableFence.value() != snapshotFence) {
      throw new IllegalStateException(
          "Account issuance fence differs from the exact runtime membership snapshot");
    }

    AccountConnectIssuanceFenceEvidence candidate =
        AccountConnectIssuanceFenceEvidence.capture(
            claim.operation().operationId(),
            accountUuid,
            identity.tenantId(),
            AccountJoinDigest.tokenHash(identity.connectScopeId()),
            identity.requestId(),
            checkedRequestDigest,
            durableFence.value(),
            durableFence.sourceVersion());
    return issuanceRepository.captureIssuanceFence(claim, checkedRequestDigest, candidate);
  }

  /**
   * Reads back only a complete positive capture bound to this exact operation and request digest.
   */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public Optional<AccountConnectIssuanceFenceEvidence> read(
      AccountConnectTokenIssuanceIdentity identity, byte[] requestDigest) {
    Objects.requireNonNull(identity, "identity");
    byte[] checkedRequestDigest = requireRequestDigest(requestDigest);
    requireWritableReadCommittedOwnerTransaction();
    return issuanceRepository.readIssuanceFenceCapture(identity, checkedRequestDigest);
  }

  private void requireWritableReadCommittedOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Account issuance-fence capture requires an active owner transaction");
    }
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      if (!DataSourceUtils.isConnectionTransactional(connection, dataSource)
          || connection.getAutoCommit()) {
        throw new IllegalStateException(
            "Account issuance-fence capture requires the transaction-bound JDBC connection");
      }
      if (connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
        throw new IllegalStateException(
            "Account issuance-fence capture requires READ COMMITTED isolation");
      }
      if (connection.isReadOnly()
          || !"off".equalsIgnoreCase(readPostgresSetting(connection, "transaction_read_only"))) {
        throw new IllegalStateException(
            "Account issuance-fence capture requires a writable owner transaction");
      }
      if (!"read committed"
          .equalsIgnoreCase(readPostgresSetting(connection, "transaction_isolation"))) {
        throw new IllegalStateException(
            "Account issuance-fence capture requires READ COMMITTED isolation");
      }
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Account issuance-fence capture could not prove its owner transaction", exception);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  private static String readPostgresSetting(Connection connection, String setting)
      throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SHOW " + setting)) {
      if (!result.next()) {
        throw new IllegalStateException(
            "PostgreSQL transaction setting is unavailable: " + setting);
      }
      String value = result.getString(1);
      if (value == null || result.next()) {
        throw new IllegalStateException("PostgreSQL transaction setting is malformed: " + setting);
      }
      return value.trim();
    }
  }

  private static UUID requirePersistedAccountIdentity(Account account, long accountId) {
    if (account.getId() == null
        || account.getId() != accountId
        || account.getAccountUuid() == null
        || account.getAccountUuid().equals(new UUID(0L, 0L))
        || account.getAccountUuidProvenance() == null
        || account.getAccountUuidSourceNumericId() == null
        || account.getAccountUuidSourceNumericId() != accountId) {
      throw new IllegalStateException(
          "Account canonical UUID does not match its exact private row identity");
    }
    return account.getAccountUuid();
  }

  private static void requireExactActiveMembership(
      RuntimeMembershipSnapshotDto snapshot, UUID accountUuid, UUID tenantUuid) {
    if (!accountUuid.toString().equals(snapshot.requestAccountUuid())
        || !accountUuid.toString().equals(snapshot.accountUuid())
        || !tenantUuid.toString().equals(snapshot.requestTenantUuid())
        || !tenantUuid.toString().equals(snapshot.tenantUuid())
        || !snapshot.membershipExists()
        || !snapshot.gameplayAdmissionAllowed()
        || !"ACTIVE".equals(snapshot.membershipBaseline().membershipLifecycleState())
        || !snapshot.roles().contains("player")) {
      throw new IllegalStateException(
          "Gameplay-connect issuance requires the exact existing ACTIVE Account membership");
    }
  }

  private static long parsePositiveCanonicalDecimal(String value, String fieldName) {
    if (value == null || !value.matches("[1-9][0-9]*")) {
      throw new IllegalStateException(fieldName + " is not a positive canonical decimal");
    }
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0L || !Long.toString(parsed).equals(value)) {
        throw new IllegalStateException(fieldName + " is outside its positive integer range");
      }
      return parsed;
    } catch (NumberFormatException exception) {
      throw new IllegalStateException(
          fieldName + " is outside its positive integer range", exception);
    }
  }

  private static byte[] requireRequestDigest(byte[] requestDigest) {
    Objects.requireNonNull(requestDigest, "requestDigest");
    if (requestDigest.length != 32) {
      throw new IllegalArgumentException("Connect-token request digest must be 32 bytes");
    }
    return requestDigest.clone();
  }
}
