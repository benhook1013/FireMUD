package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Account-owned immutable initial token-identity fence capture for the bare first-party LOGIN
 * source. It never issues or signs a token, changes authority, touches a registry, or authorizes
 * admission. Storage captures only the initial positive identity fence; complete monotonic logout
 * and rotation transitions and producer/registry proof remain open. UPDATE, DELETE, and TRUNCATE
 * remain denied until a separate safe-retention proof justifies removal.
 */
@Repository
public class AccountBareLoginTokenIdentityFenceRepository {
  private static final String CAPTURE_TABLE = "account_bare_login_token_identity_fence_captures";
  private static final String OPERATION_TABLE = "account_bare_login_exchange_operations";
  private static final int REQUEST_DIGEST_VERSION = 1;
  private static final long INITIAL_IDENTITY_FENCE = 1L;
  private static final long INITIAL_IDENTITY_SOURCE_VERSION = 1L;
  private static final int DIGEST_LENGTH_BYTES = 32;
  private static final int MAX_TOKEN_IDENTITY_LENGTH = 128;
  private static final Pattern TOKEN_HASH_HEX = Pattern.compile("[0-9a-f]{64}");

  private final DSLContext dsl;
  private final DataSource dataSource;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Spring-injected DSLContext and DataSource must retain the same owner transaction.")
  public AccountBareLoginTokenIdentityFenceRepository(DSLContext dsl, DataSource dataSource) {
    this.dsl = dsl;
    this.dataSource = dataSource;
  }

  /**
   * Captures the exact outgoing private-delegation identity while its owning bare-LOGIN operation
   * remains PENDING. The canonical Account row is locked first, followed by that operation and its
   * current Account issuance-fence row. An exact retry returns the original immutable capture only
   * while every bound value, including current Account issuance-fence evidence, is unchanged.
   */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public AccountBareLoginTokenIdentityFence captureInitial(
      AccountBareLoginExchangeIdentity exchangeIdentity,
      UUID exchangeOperationId,
      UUID accountUuid,
      byte[] requestDigest,
      String tokenIdentity,
      byte[] tokenHash) {
    CaptureInput input =
        validateInput(
            exchangeIdentity,
            exchangeOperationId,
            accountUuid,
            requestDigest,
            tokenIdentity,
            tokenHash);
    requireWritableReadCommittedOwnerTransaction();
    long persistedAccountId = lockAndVerifyCanonicalAccount(input);
    lockAndVerifyPendingOperation(input, persistedAccountId);
    CurrentAccountFence currentFence = readCurrentAccountIssuanceFence(input.accountUuid(), true);
    AccountBareLoginTokenIdentityFence expected = expectedCapture(input, currentFence);

    AccountBareLoginTokenIdentityFence prior = readByOperation(input.exchangeOperationId());
    if (prior != null) {
      requireExactCapture(prior, expected);
      return prior;
    }
    AccountBareLoginTokenIdentityFence conflictingIdentity =
        readByTokenIdentity(input.tokenHash(), input.tokenIdentity());
    if (conflictingIdentity != null) {
      throw new IllegalStateException(
          "Bare LOGIN token identity is already captured by another exact operation");
    }

    Record inserted =
        dsl.fetchOne(
            "INSERT INTO "
                + CAPTURE_TABLE
                + " (token_hash, schema_name, operation_id, source_connect_operation_id, account_id, "
                + "account_uuid, tenant_id, connect_scope_hash, request_id, "
                + "request_digest_version, request_digest, token_identity, profile, "
                + "token_identity_fence, token_identity_fence_source_version, "
                + "account_issuance_fence, account_issuance_fence_source_version) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT DO NOTHING RETURNING operation_id",
            encodeTokenHash(input.tokenHash()),
            AccountBareLoginTokenIdentityFence.SCHEMA_NAME,
            input.exchangeOperationId(),
            input.exchangeIdentity().sourceConnectOperationId(),
            input.exchangeIdentity().accountId(),
            input.accountUuid(),
            input.exchangeIdentity().tenantId(),
            input.exchangeIdentity().connectScopeHash(),
            input.exchangeIdentity().requestId(),
            REQUEST_DIGEST_VERSION,
            input.requestDigest(),
            input.tokenIdentity(),
            AccountBareLoginTokenIdentityFence.PROFILE_NAME,
            INITIAL_IDENTITY_FENCE,
            INITIAL_IDENTITY_SOURCE_VERSION,
            currentFence.issuanceFence(),
            currentFence.sourceVersion());
    if (inserted != null
        && !input.exchangeOperationId().equals(inserted.get("operation_id", UUID.class))) {
      throw new IllegalStateException(
          "Bare LOGIN token identity insert returned another operation");
    }

    AccountBareLoginTokenIdentityFence stored = readByOperation(input.exchangeOperationId());
    if (stored == null) {
      if (readByTokenIdentity(input.tokenHash(), input.tokenIdentity()) != null) {
        throw new IllegalStateException(
            "Bare LOGIN token identity conflicts with another immutable capture");
      }
      throw new IllegalStateException(
          "Bare LOGIN token identity fence capture has no exact durable readback");
    }
    requireExactCapture(stored, expected);
    CurrentAccountFence readbackFence = readCurrentAccountIssuanceFence(input.accountUuid(), true);
    if (!currentFence.equals(readbackFence)) {
      throw new IllegalStateException(
          "Account issuance-fence evidence changed during bare LOGIN identity capture");
    }
    return stored;
  }

  /**
   * Requires existing exact initial evidence and never creates a row. It applies the same account,
   * pending-operation, transaction, and current-fence checks as capture so absent, terminal, or
   * stale evidence cannot be mistaken for proof.
   */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public AccountBareLoginTokenIdentityFence requireInitial(
      AccountBareLoginExchangeIdentity exchangeIdentity,
      UUID exchangeOperationId,
      UUID accountUuid,
      byte[] requestDigest,
      String tokenIdentity,
      byte[] tokenHash) {
    CaptureInput input =
        validateInput(
            exchangeIdentity,
            exchangeOperationId,
            accountUuid,
            requestDigest,
            tokenIdentity,
            tokenHash);
    requireWritableReadCommittedOwnerTransaction();
    long persistedAccountId = lockAndVerifyCanonicalAccount(input);
    lockAndVerifyPendingOperation(input, persistedAccountId);
    CurrentAccountFence currentFence = readCurrentAccountIssuanceFence(input.accountUuid(), true);
    AccountBareLoginTokenIdentityFence stored = readByOperation(input.exchangeOperationId());
    if (stored == null) {
      throw new IllegalStateException(
          "Bare LOGIN token identity fence capture is absent and cannot be inferred");
    }
    requireExactCapture(stored, expectedCapture(input, currentFence));
    return stored;
  }

  private CaptureInput validateInput(
      AccountBareLoginExchangeIdentity exchangeIdentity,
      UUID exchangeOperationId,
      UUID accountUuid,
      byte[] requestDigest,
      String tokenIdentity,
      byte[] tokenHash) {
    Objects.requireNonNull(exchangeIdentity, "exchangeIdentity");
    if (isNil(exchangeOperationId) || isNil(accountUuid)) {
      throw new IllegalArgumentException(
          "Bare LOGIN operation and canonical Account UUID are required");
    }
    byte[] checkedDigest = requireDigest(requestDigest, "request digest");
    byte[] checkedTokenHash = requireDigest(tokenHash, "token hash");
    if (!validTokenIdentity(tokenIdentity)) {
      throw new IllegalArgumentException("Bare LOGIN token identity is malformed");
    }
    return new CaptureInput(
        exchangeIdentity,
        exchangeOperationId,
        accountUuid,
        checkedDigest,
        tokenIdentity,
        checkedTokenHash);
  }

  private long lockAndVerifyCanonicalAccount(CaptureInput input) {
    Record account =
        dsl.fetchOne(
            "SELECT id, account_uuid, account_uuid_provenance, account_uuid_source_numeric_id "
                + "FROM accounts WHERE account_uuid = ? FOR UPDATE",
            input.accountUuid());
    if (account == null) {
      throw new IllegalStateException("Bare LOGIN canonical Account row is absent");
    }
    Long id = account.get("id", Long.class);
    Long sourceId = account.get("account_uuid_source_numeric_id", Long.class);
    String provenance = account.get("account_uuid_provenance", String.class);
    if (id == null
        || id <= 0L
        || id.longValue() != input.exchangeIdentity().accountId()
        || !input.accountUuid().equals(account.get("account_uuid", UUID.class))
        || sourceId == null
        || sourceId.longValue() != id
        || !validAccountUuidProvenance(provenance)) {
      throw new IllegalStateException(
          "Bare LOGIN canonical Account UUID provenance does not match its private row");
    }
    return id;
  }

  private void lockAndVerifyPendingOperation(CaptureInput input, long persistedAccountId) {
    Record operation =
        dsl.fetchOne(
            "SELECT operation_id, source_connect_operation_id, account_id, tenant_id, "
                + "connect_scope_hash, request_id, request_digest_version, request_digest, "
                + "status, token_identity, token_hash FROM "
                + OPERATION_TABLE
                + " WHERE operation_id = ? FOR UPDATE",
            input.exchangeOperationId());
    if (operation == null
        || !input.exchangeOperationId().equals(operation.get("operation_id", UUID.class))
        || !input
            .exchangeIdentity()
            .sourceConnectOperationId()
            .equals(operation.get("source_connect_operation_id", UUID.class))
        || !Objects.equals(operation.get("account_id", Long.class), persistedAccountId)
        || !input.exchangeIdentity().tenantId().equals(operation.get("tenant_id", UUID.class))
        || !input
            .exchangeIdentity()
            .connectScopeHash()
            .equals(operation.get("connect_scope_hash", String.class))
        || !input.exchangeIdentity().requestId().equals(operation.get("request_id", String.class))
        || !Integer.valueOf(REQUEST_DIGEST_VERSION)
            .equals(operation.get("request_digest_version", Integer.class))
        || !MessageDigest.isEqual(input.requestDigest(), requiredBytes(operation, "request_digest"))
        || !"PENDING".equals(operation.get("status", String.class))
        || !input.tokenIdentity().equals(operation.get("token_identity", String.class))
        || !MessageDigest.isEqual(input.tokenHash(), requiredBytes(operation, "token_hash"))) {
      throw new IllegalStateException(
          "Bare LOGIN token identity does not match its exact pending operation");
    }
  }

  private CurrentAccountFence readCurrentAccountIssuanceFence(UUID accountUuid, boolean lock) {
    Record fence =
        dsl.fetchOne(
            "SELECT issuance_fence, source_version FROM account_authority_issuance_fences "
                + "WHERE account_uuid = ?"
                + (lock ? " FOR UPDATE" : ""),
            accountUuid);
    if (fence == null) {
      throw new IllegalStateException("Current Account issuance fence is absent");
    }
    return new CurrentAccountFence(
        requiredPositive(fence.get("issuance_fence", Long.class), "Account issuance fence"),
        requiredPositive(
            fence.get("source_version", Long.class), "Account issuance-fence source version"));
  }

  private AccountBareLoginTokenIdentityFence expectedCapture(
      CaptureInput input, CurrentAccountFence currentFence) {
    return new AccountBareLoginTokenIdentityFence(
        AccountBareLoginTokenIdentityFence.SCHEMA_NAME,
        input.exchangeOperationId(),
        input.exchangeIdentity().sourceConnectOperationId(),
        input.exchangeIdentity().accountId(),
        input.accountUuid(),
        input.exchangeIdentity().tenantId(),
        input.exchangeIdentity().connectScopeHash(),
        input.exchangeIdentity().requestId(),
        REQUEST_DIGEST_VERSION,
        input.requestDigest(),
        AccountBareLoginTokenIdentityFence.PROFILE_NAME,
        input.tokenIdentity(),
        input.tokenHash(),
        INITIAL_IDENTITY_FENCE,
        INITIAL_IDENTITY_SOURCE_VERSION,
        currentFence.issuanceFence(),
        currentFence.sourceVersion(),
        Instant.EPOCH);
  }

  private AccountBareLoginTokenIdentityFence readByOperation(UUID operationId) {
    Record row =
        dsl.fetchOne(
            "SELECT token_hash, schema_name, operation_id, source_connect_operation_id, account_id, "
                + "account_uuid, tenant_id, connect_scope_hash, request_id, "
                + "request_digest_version, request_digest, "
                + "token_identity, profile, token_identity_fence, "
                + "token_identity_fence_source_version, account_issuance_fence, "
                + "account_issuance_fence_source_version, captured_at FROM "
                + CAPTURE_TABLE
                + " WHERE operation_id = ?",
            operationId);
    return row == null ? null : decode(row);
  }

  private AccountBareLoginTokenIdentityFence readByTokenIdentity(
      byte[] tokenHash, String tokenIdentity) {
    Record row =
        dsl.fetchOne(
            "SELECT token_hash, schema_name, operation_id, source_connect_operation_id, account_id, "
                + "account_uuid, tenant_id, connect_scope_hash, request_id, "
                + "request_digest_version, request_digest, "
                + "token_identity, profile, token_identity_fence, "
                + "token_identity_fence_source_version, account_issuance_fence, "
                + "account_issuance_fence_source_version, captured_at FROM "
                + CAPTURE_TABLE
                + " WHERE token_hash = ? OR (profile = ? AND token_identity = ?) LIMIT 1",
            encodeTokenHash(tokenHash),
            AccountBareLoginTokenIdentityFence.PROFILE_NAME,
            tokenIdentity);
    return row == null ? null : decode(row);
  }

  private AccountBareLoginTokenIdentityFence decode(Record row) {
    OffsetDateTime capturedAt = row.get("captured_at", OffsetDateTime.class);
    if (capturedAt == null) {
      throw new IllegalStateException("Stored bare LOGIN identity fence capture time is absent");
    }
    return new AccountBareLoginTokenIdentityFence(
        row.get("schema_name", String.class),
        requiredUuid(row.get("operation_id", UUID.class), "operation ID"),
        requiredUuid(row.get("source_connect_operation_id", UUID.class), "source operation ID"),
        requiredPositive(row.get("account_id", Long.class), "Account ID"),
        requiredUuid(row.get("account_uuid", UUID.class), "Account UUID"),
        requiredUuid(row.get("tenant_id", UUID.class), "tenant UUID"),
        row.get("connect_scope_hash", String.class),
        row.get("request_id", String.class),
        requiredVersion(row.get("request_digest_version", Integer.class)),
        requiredBytes(row, "request_digest"),
        row.get("profile", String.class),
        row.get("token_identity", String.class),
        decodeTokenHash(row.get("token_hash", String.class)),
        requiredPositive(row.get("token_identity_fence", Long.class), "token identity fence"),
        requiredPositive(
            row.get("token_identity_fence_source_version", Long.class),
            "token identity-fence source version"),
        requiredPositive(row.get("account_issuance_fence", Long.class), "Account issuance fence"),
        requiredPositive(
            row.get("account_issuance_fence_source_version", Long.class),
            "Account issuance-fence source version"),
        JooqPersistenceSupport.toInstant(capturedAt));
  }

  private static void requireExactCapture(
      AccountBareLoginTokenIdentityFence stored, AccountBareLoginTokenIdentityFence expected) {
    if (!stored.sameInitialCapture(expected)) {
      throw new IllegalStateException(
          "Stored bare LOGIN token identity capture differs from its current exact evidence");
    }
  }

  private void requireWritableReadCommittedOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Bare LOGIN token identity capture requires a writable Account owner transaction");
    }
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      if (!DataSourceUtils.isConnectionTransactional(connection, dataSource)
          || connection.getAutoCommit()) {
        throw new IllegalStateException(
            "Bare LOGIN token identity capture requires the transaction-bound JDBC connection");
      }
      if (connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
        throw new IllegalStateException(
            "Bare LOGIN token identity capture requires READ COMMITTED isolation");
      }
      if (connection.isReadOnly()
          || !"off".equalsIgnoreCase(readPostgresSetting(connection, "transaction_read_only"))) {
        throw new IllegalStateException(
            "Bare LOGIN token identity capture requires a writable owner transaction");
      }
      if (!"read committed"
          .equalsIgnoreCase(readPostgresSetting(connection, "transaction_isolation"))) {
        throw new IllegalStateException(
            "Bare LOGIN token identity capture requires READ COMMITTED isolation");
      }
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Bare LOGIN token identity capture could not prove its owner transaction", exception);
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

  private static boolean validAccountUuidProvenance(String provenance) {
    return "ACCOUNT_V29_MIGRATION".equals(provenance)
        || "ACCOUNT_REPOSITORY_INSERT".equals(provenance)
        || "ACCOUNT_DATABASE_INSERT".equals(provenance);
  }

  private static boolean validTokenIdentity(String tokenIdentity) {
    return tokenIdentity != null
        && !tokenIdentity.isBlank()
        && tokenIdentity.length() <= MAX_TOKEN_IDENTITY_LENGTH
        && tokenIdentity.indexOf('\0') < 0
        && !hasUnpairedSurrogate(tokenIdentity);
  }

  private static boolean hasUnpairedSurrogate(String value) {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
          return true;
        }
        index++;
      } else if (Character.isLowSurrogate(current)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isNil(UUID value) {
    return value == null || value.equals(new UUID(0L, 0L));
  }

  private static byte[] requireDigest(byte[] value, String label) {
    if (value == null || value.length != DIGEST_LENGTH_BYTES) {
      throw new IllegalArgumentException(label + " must be 32 bytes");
    }
    return value.clone();
  }

  private static byte[] requiredBytes(Record record, String column) {
    byte[] value = record.get(column, byte[].class);
    if (value == null) {
      throw new IllegalStateException("Stored bare LOGIN evidence is missing " + column);
    }
    return value;
  }

  private static String encodeTokenHash(byte[] tokenHash) {
    if (tokenHash == null || tokenHash.length != DIGEST_LENGTH_BYTES) {
      throw new IllegalArgumentException("Token hash must be 32 bytes");
    }
    return HexFormat.of().formatHex(tokenHash);
  }

  private static byte[] decodeTokenHash(String tokenHash) {
    if (tokenHash == null || !TOKEN_HASH_HEX.matcher(tokenHash).matches()) {
      throw new IllegalStateException(
          "Stored bare LOGIN token hash is not canonical lowercase SHA-256 hex");
    }
    try {
      byte[] decoded = HexFormat.of().parseHex(tokenHash);
      if (decoded.length != DIGEST_LENGTH_BYTES) {
        throw new IllegalStateException("Stored bare LOGIN token hash has the wrong length");
      }
      return decoded;
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "Stored bare LOGIN token hash is not canonical lowercase SHA-256 hex", exception);
    }
  }

  private static long requiredPositive(Long value, String label) {
    if (value == null || value <= 0L) {
      throw new IllegalStateException("Stored " + label + " is not positive");
    }
    return value;
  }

  private static int requiredVersion(Integer value) {
    if (value == null || value != REQUEST_DIGEST_VERSION) {
      throw new IllegalStateException("Stored bare LOGIN request digest version is unsupported");
    }
    return value;
  }

  private static UUID requiredUuid(UUID value, String label) {
    if (isNil(value)) {
      throw new IllegalStateException("Stored " + label + " is absent or nil");
    }
    return value;
  }

  private record CaptureInput(
      AccountBareLoginExchangeIdentity exchangeIdentity,
      UUID exchangeOperationId,
      UUID accountUuid,
      byte[] requestDigest,
      String tokenIdentity,
      byte[] tokenHash) {
    private CaptureInput {
      requestDigest = requestDigest.clone();
      tokenHash = tokenHash.clone();
    }

    @Override
    public byte[] requestDigest() {
      return requestDigest.clone();
    }

    @Override
    public byte[] tokenHash() {
      return tokenHash.clone();
    }
  }

  private record CurrentAccountFence(long issuanceFence, long sourceVersion) {}
}
