package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Existing-only, owner-fenced readback of the fresh Account explicit-empty global-role source. */
@Repository
public class AccountGlobalRoleSourceRepository {
  private static final UUID NIL_ACCOUNT_UUID = new UUID(0L, 0L);

  private final DSLContext dsl;
  private final DataSource dataSource;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Spring collaborator validation acquires no resources; the repository has no finalizer.")
  public AccountGlobalRoleSourceRepository(DSLContext dsl, DataSource dataSource) {
    this.dsl = Objects.requireNonNull(dsl, "dsl is required");
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource is required");
  }

  /**
   * Locks and reads the exact fresh Account row and its immutable explicit-empty role source.
   * Missing source state is never initialized by a read.
   */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public FreshEmptySource readFreshEmptySourceForUpdate(UUID accountUuid) {
    requireCanonicalAccountUuid(accountUuid);
    requireWritableReadCommittedOwnerTransaction();

    Record account =
        dsl.fetchOne(
            "SELECT id, account_uuid, account_uuid_source_numeric_id, "
                + "account_uuid_provenance, role FROM accounts "
                + "WHERE account_uuid = ? FOR UPDATE",
            accountUuid);
    if (account == null) {
      throw new IllegalStateException("Persisted Account row is missing");
    }

    Long accountRowId = account.get("id", Long.class);
    UUID persistedAccountUuid = account.get("account_uuid", UUID.class);
    Long accountUuidSourceNumericId = account.get("account_uuid_source_numeric_id", Long.class);
    AccountIdentityProvenance provenance =
        AccountIdentityProvenance.fromStorageValue(
            account.get("account_uuid_provenance", String.class));
    String legacyRole = account.get("role", String.class);
    requireFreshAccountIdentity(
        accountUuid,
        accountRowId,
        persistedAccountUuid,
        accountUuidSourceNumericId,
        provenance,
        legacyRole);

    Record source =
        dsl.fetchOne(
            "SELECT account_uuid, account_uuid_source_numeric_id, account_uuid_provenance, "
                + "cardinality(global_roles) AS global_role_count, "
                + "global_role_source_version FROM account_global_role_sources "
                + "WHERE account_uuid = ? FOR UPDATE",
            accountUuid);
    if (source == null) {
      throw new IllegalStateException("Fresh Account global-role source is missing");
    }

    UUID sourceAccountUuid = source.get("account_uuid", UUID.class);
    Long sourceNumericId = source.get("account_uuid_source_numeric_id", Long.class);
    AccountIdentityProvenance sourceProvenance =
        AccountIdentityProvenance.fromStorageValue(
            source.get("account_uuid_provenance", String.class));
    Integer globalRoleCount = source.get("global_role_count", Integer.class);
    Long sourceVersion = source.get("global_role_source_version", Long.class);
    requireExactSourceReadback(
        accountUuid,
        accountRowId,
        accountUuidSourceNumericId,
        provenance,
        sourceAccountUuid,
        sourceNumericId,
        sourceProvenance,
        globalRoleCount,
        sourceVersion);

    return new FreshEmptySource(
        accountUuid,
        accountRowId,
        accountUuidSourceNumericId,
        provenance,
        List.of(),
        sourceVersion);
  }

  private static void requireCanonicalAccountUuid(UUID accountUuid) {
    if (accountUuid == null || NIL_ACCOUNT_UUID.equals(accountUuid)) {
      throw new IllegalArgumentException("A canonical non-nil Account UUID is required");
    }
  }

  private void requireWritableReadCommittedOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Account global-role source readback requires a writable Account owner transaction");
    }
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      if (!DataSourceUtils.isConnectionTransactional(connection, dataSource)
          || connection.getAutoCommit()) {
        throw new IllegalStateException(
            "Account global-role source readback requires the transaction-bound JDBC connection");
      }
      if (connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
        throw new IllegalStateException(
            "Account global-role source readback requires READ COMMITTED isolation");
      }
      if (connection.isReadOnly()
          || !"off".equalsIgnoreCase(readPostgresSetting(connection, "transaction_read_only"))) {
        throw new IllegalStateException(
            "Account global-role source readback requires a writable owner transaction");
      }
      if (!"read committed"
          .equalsIgnoreCase(readPostgresSetting(connection, "transaction_isolation"))) {
        throw new IllegalStateException(
            "Account global-role source readback requires READ COMMITTED isolation");
      }
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Account global-role source readback could not prove its owner transaction", exception);
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

  private static void requireFreshAccountIdentity(
      UUID expectedAccountUuid,
      Long accountRowId,
      UUID persistedAccountUuid,
      Long accountUuidSourceNumericId,
      AccountIdentityProvenance provenance,
      String legacyRole) {
    if (accountRowId == null
        || accountRowId <= 0L
        || !expectedAccountUuid.equals(persistedAccountUuid)
        || accountUuidSourceNumericId == null
        || !accountRowId.equals(accountUuidSourceNumericId)
        || !isFreshInsertProvenance(provenance)) {
      throw new IllegalStateException("Fresh canonical Account identity readback is invalid");
    }
    if (legacyRole != null) {
      throw new IllegalStateException(
          "Legacy Account role contradicts the explicit-empty global-role source");
    }
  }

  private static void requireExactSourceReadback(
      UUID expectedAccountUuid,
      Long accountRowId,
      Long expectedSourceNumericId,
      AccountIdentityProvenance expectedProvenance,
      UUID sourceAccountUuid,
      Long sourceNumericId,
      AccountIdentityProvenance sourceProvenance,
      Integer globalRoleCount,
      Long sourceVersion) {
    if (!expectedAccountUuid.equals(sourceAccountUuid)
        || accountRowId == null
        || sourceNumericId == null
        || !accountRowId.equals(sourceNumericId)
        || !Objects.equals(expectedSourceNumericId, sourceNumericId)
        || sourceProvenance != expectedProvenance
        || !isFreshInsertProvenance(sourceProvenance)
        || globalRoleCount == null
        || globalRoleCount != 0
        || sourceVersion == null
        || sourceVersion != 1L) {
      throw new IllegalStateException(
          "Fresh Account global-role source readback is missing, malformed, or mismatched");
    }
  }

  private static boolean isFreshInsertProvenance(AccountIdentityProvenance provenance) {
    return provenance == AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT
        || provenance == AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT;
  }

  /** Exact fresh identity and source revision returned by the owner-fenced read. */
  public record FreshEmptySource(
      UUID accountUuid,
      long accountRowId,
      long accountUuidSourceNumericId,
      AccountIdentityProvenance accountUuidProvenance,
      List<String> globalRoles,
      long globalRoleSourceVersion) {
    public FreshEmptySource {
      Objects.requireNonNull(accountUuid, "canonical Account UUID is required");
      Objects.requireNonNull(accountUuidProvenance, "Account UUID provenance is required");
      globalRoles = List.copyOf(Objects.requireNonNull(globalRoles, "globalRoles is required"));
      if (NIL_ACCOUNT_UUID.equals(accountUuid)
          || accountRowId <= 0L
          || accountUuidSourceNumericId != accountRowId
          || !isFreshInsertProvenance(accountUuidProvenance)
          || !globalRoles.isEmpty()
          || globalRoleSourceVersion != 1L) {
        throw new IllegalArgumentException("Fresh explicit-empty global-role source is invalid");
      }
    }
  }
}
