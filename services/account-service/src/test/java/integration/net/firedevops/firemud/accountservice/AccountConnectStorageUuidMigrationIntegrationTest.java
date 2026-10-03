package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.security.AccountEncryptedEnvelope;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeBinding;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCrypto;
import net.firedevops.firemud.accountservice.security.AccountEnvelopePurpose;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AccountConnectStorageUuidMigrationIntegrationTest {
  private static final String HISTORY_TABLE = "flyway_schema_history_account_connect_storage";
  private static final String CONNECT_SCOPE_ID = "connect-scope-migration-proof";
  private static final String SCOPE_HASH = AccountJoinDigest.tokenHash(CONNECT_SCOPE_ID);
  private static final String DIGEST_SQL = "decode(repeat('11', 32), 'hex')";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void freshMigrationsAndEmptyV39UpgradeUseCanonicalUuidColumns() throws Exception {
    Database fresh = newDatabase();
    flyway(fresh, null).migrate();
    assertTenantTypes(fresh, "uuid");

    Database upgraded = newDatabase();
    flyway(upgraded, "39").migrate();
    assertTenantTypes(upgraded, "bigint");
    flyway(upgraded, null).migrate();
    assertTenantTypes(upgraded, "uuid");
  }

  @Test
  void retainedConnectOperationBlocksMigrationWithoutChangingItsRow() throws Exception {
    Database database = newDatabase();
    flyway(database, "39").migrate();
    try (Connection connection = database.dataSource().getConnection()) {
      AccountRow account = insertAccount(connection, database);
      insertPendingConnectOperation(connection, database, account, 81L, UUID.randomUUID());
    }
    assertMigrationRefusesWithoutChangingEvidence(database);
  }

  @Test
  void retainedConnectEnvelopeBlocksMigrationWithoutChangingCiphertext(@TempDir Path directory)
      throws Exception {
    Database database = newDatabase();
    AccountEnvelopeCrypto crypto = keyRing(directory);
    flyway(database, "39").migrate();
    try (Connection connection = database.dataSource().getConnection()) {
      connection.setAutoCommit(false);
      AccountRow account = insertAccount(connection, database);
      insertCommittedConnectChain(connection, database, account, 82L, UUID.randomUUID(), crypto);
      connection.commit();
    }
    assertMigrationRefusesWithoutChangingEvidence(database, crypto);
  }

  @Test
  void retainedBareLoginOperationAndEnvelopeBlockMigrationWithoutChangingEvidence(
      @TempDir Path directory) throws Exception {
    Database database = newDatabase();
    AccountEnvelopeCrypto crypto = keyRing(directory);
    flyway(database, "39").migrate();
    try (Connection connection = database.dataSource().getConnection()) {
      connection.setAutoCommit(false);
      AccountRow account = insertAccount(connection, database);
      UUID sourceOperationId = UUID.randomUUID();
      insertCommittedConnectChain(connection, database, account, 83L, sourceOperationId, crypto);
      insertCommittedBareLoginChain(
          connection, database, account, 83L, sourceOperationId, UUID.randomUUID(), crypto);
      connection.commit();
    }
    assertMigrationRefusesWithoutChangingEvidence(database, crypto);
  }

  @Test
  void migrationWaitsForConcurrentWriterThenRejectsItsCommittedEvidence() throws Exception {
    Database database = newDatabase();
    flyway(database, "39").migrate();
    UUID operationId = UUID.randomUUID();
    Connection writer = database.dataSource().getConnection();
    writer.setAutoCommit(false);
    AccountRow account = insertAccount(writer, database);
    insertPendingConnectOperation(writer, database, account, 84L, operationId);

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<?> migration = executor.submit(() -> flyway(database, null).migrate());
      assertThat(awaitMigrationLockWait(database.dataSource(), Duration.ofSeconds(10))).isTrue();
      assertThat(migration.isDone()).isFalse();

      writer.commit();
      assertThatThrownBy(() -> migration.get(20, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .satisfies(AccountConnectStorageUuidMigrationIntegrationTest::assertSqlMigrationRefusal);
    } finally {
      try {
        writer.rollback();
      } finally {
        writer.close();
        executor.shutdownNow();
      }
    }

    assertThat(columnType(database, "account_connect_token_issuance_operations"))
        .isEqualTo("bigint");
    assertThat(rowCount(database, "account_connect_token_issuance_operations")).isEqualTo(1);
  }

  @Test
  void uuidUpgradePreservesOperationEnvelopeAndDeferredSourceGuards(@TempDir Path directory)
      throws Exception {
    Database database = newDatabase();
    AccountEnvelopeCrypto crypto = keyRing(directory);
    flyway(database, "39").migrate();
    flyway(database, null).migrate();

    UUID tenantId = UUID.randomUUID();
    UUID sourceOperationId = UUID.randomUUID();
    try (Connection connection = database.dataSource().getConnection()) {
      connection.setAutoCommit(false);
      AccountRow account = insertAccount(connection, database);
      insertCommittedConnectChain(
          connection, database, account, tenantId, sourceOperationId, crypto);
      insertCommittedBareLoginChain(
          connection, database, account, tenantId, sourceOperationId, UUID.randomUUID(), crypto);
      connection.commit();
    }

    assertThat(rowCount(database, "account_connect_token_issuance_operations")).isEqualTo(1);
    assertThat(rowCount(database, "account_connect_token_response_envelopes")).isEqualTo(1);
    assertThat(rowCount(database, "account_bare_login_exchange_operations")).isEqualTo(1);
    assertThat(rowCount(database, "account_bare_login_response_envelopes")).isEqualTo(1);
    assertTenantTypes(database, "uuid");
    assertStoredEnvelopesDecrypt(database, crypto, 1, 1);

    try (Connection connection = database.dataSource().getConnection()) {
      assertThatThrownBy(
              () ->
                  execute(
                      connection,
                      "UPDATE "
                          + table(database, "account_connect_token_issuance_operations")
                          + " SET tenant_id = ? WHERE operation_id = ?",
                      UUID.randomUUID(),
                      sourceOperationId))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("Connect-token issuance identity and request digest are immutable");
    }
  }

  private static void assertMigrationRefusesWithoutChangingEvidence(Database database)
      throws Exception {
    assertMigrationRefusesWithoutChangingEvidence(database, null);
  }

  private static void assertMigrationRefusesWithoutChangingEvidence(
      Database database, AccountEnvelopeCrypto crypto) throws Exception {
    EvidenceSnapshot before = snapshot(database);

    assertThatThrownBy(() -> flyway(database, null).migrate())
        .satisfies(AccountConnectStorageUuidMigrationIntegrationTest::assertSqlMigrationRefusal);

    assertThat(snapshot(database)).isEqualTo(before);
    assertTenantTypes(database, "bigint");
    if (crypto != null) {
      assertStoredEnvelopesDecrypt(
          database,
          crypto,
          before.connectCiphertexts().size(),
          before.bareLoginCiphertexts().size());
    }
  }

  private static void assertSqlMigrationRefusal(Throwable exception) {
    SQLException sqlException = findSqlException(exception);
    assertThat(sqlException.getSQLState()).isEqualTo("23514");
    assertThat(sqlException.getMessage())
        .contains(
            "Account connect storage UUID migration requires all four operation and envelope tables to be empty");
  }

  private static SQLException findSqlException(Throwable exception) {
    Throwable current = exception;
    while (current != null) {
      if (current instanceof SQLException sqlException) {
        return sqlException;
      }
      current = current.getCause();
    }
    throw new AssertionError("Migration refusal did not contain a JDBC SQL exception", exception);
  }

  private static EvidenceSnapshot snapshot(Database database) throws SQLException {
    return new EvidenceSnapshot(
        rows(database, "account_connect_token_issuance_operations"),
        rows(database, "account_connect_token_response_envelopes"),
        ciphertexts(database, "account_connect_token_response_envelopes"),
        rows(database, "account_bare_login_exchange_operations"),
        rows(database, "account_bare_login_response_envelopes"),
        ciphertexts(database, "account_bare_login_response_envelopes"));
  }

  private static List<String> rows(Database database, String table) throws SQLException {
    try (Connection connection = database.dataSource().getConnection();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT to_jsonb(t)::text FROM "
                    + table(database, table)
                    + " t ORDER BY t.operation_id")) {
      List<String> rows = new ArrayList<>();
      while (result.next()) {
        rows.add(result.getString(1));
      }
      return rows;
    }
  }

  private static List<String> ciphertexts(Database database, String table) throws SQLException {
    try (Connection connection = database.dataSource().getConnection();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT encode(ciphertext, 'hex') FROM "
                    + table(database, table)
                    + " ORDER BY operation_id")) {
      List<String> values = new ArrayList<>();
      while (result.next()) {
        values.add(result.getString(1));
      }
      return values;
    }
  }

  private static boolean awaitMigrationLockWait(DataSource dataSource, Duration timeout)
      throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    String query =
        "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid <> pg_backend_pid() "
            + "AND wait_event_type = 'Lock' "
            + "AND query ILIKE '%LOCK TABLE account_connect_token_issuance_operations%')";
    do {
      try (Connection connection = dataSource.getConnection();
          Statement statement = connection.createStatement();
          ResultSet result = statement.executeQuery(query)) {
        if (result.next() && result.getBoolean(1)) {
          return true;
        }
      }
      TimeUnit.MILLISECONDS.sleep(25);
    } while (System.nanoTime() < deadline);
    return false;
  }

  private static void assertTenantTypes(Database database, String expected) throws SQLException {
    for (String table :
        List.of(
            "account_connect_token_issuance_operations",
            "account_connect_token_response_envelopes",
            "account_bare_login_exchange_operations",
            "account_bare_login_response_envelopes")) {
      assertThat(columnType(database, table)).as(table + ".tenant_id type").isEqualTo(expected);
    }
  }

  private static String columnType(Database database, String table) throws SQLException {
    try (Connection connection = database.dataSource().getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT data_type FROM information_schema.columns "
                    + "WHERE table_schema = ? AND table_name = ? AND column_name = 'tenant_id'")) {
      statement.setString(1, database.schema());
      statement.setString(2, table);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        return result.getString(1);
      }
    }
  }

  private static long rowCount(Database database, String table) throws SQLException {
    try (Connection connection = database.dataSource().getConnection();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery("SELECT COUNT(*) FROM " + table(database, table))) {
      assertThat(result.next()).isTrue();
      return result.getLong(1);
    }
  }

  private static AccountRow insertAccount(Connection connection, Database database)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO "
                + table(database, "accounts")
                + " (username, email, password_hash) VALUES (?, ?, ?) RETURNING id")) {
      statement.setString(1, "migration-user-" + UUID.randomUUID());
      statement.setString(2, UUID.randomUUID() + "@example.test");
      statement.setString(3, "test-hash");
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        return new AccountRow(result.getLong(1));
      }
    }
  }

  private static void insertPendingConnectOperation(
      Connection connection,
      Database database,
      AccountRow account,
      Object tenantId,
      UUID operationId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO "
                + table(database, "account_connect_token_issuance_operations")
                + " (operation_id, account_id, tenant_id, connect_scope_hash, request_id, "
                + "request_digest_version, request_digest, status) "
                + "VALUES (?, ?, ?, ?, ?, 1, "
                + DIGEST_SQL
                + ", 'PENDING')")) {
      statement.setObject(1, operationId);
      statement.setLong(2, account.id());
      setTenant(statement, 3, tenantId);
      statement.setString(4, SCOPE_HASH);
      statement.setString(5, "connect-request-" + operationId);
      statement.executeUpdate();
    }
  }

  private static void insertCommittedConnectChain(
      Connection connection,
      Database database,
      AccountRow account,
      Object tenantId,
      UUID operationId,
      AccountEnvelopeCrypto crypto)
      throws SQLException {
    String requestId = "connect-request-" + operationId;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO "
                + table(database, "account_connect_token_issuance_operations")
                + " (operation_id, account_id, tenant_id, connect_scope_hash, request_id, "
                + "request_digest_version, request_digest, status, outcome_code, token_identity, "
                + "token_hash, context_evidence_digest, authority_tuple_digest, "
                + "issuance_fence_digest, postcondition_digest) "
                + "VALUES (?, ?, ?, ?, ?, 1, "
                + DIGEST_SQL
                + ", 'COMMITTED', 'SUCCESS', ?, "
                + DIGEST_SQL
                + ", "
                + DIGEST_SQL
                + ", "
                + DIGEST_SQL
                + ", "
                + DIGEST_SQL
                + ", "
                + DIGEST_SQL
                + ")")) {
      statement.setObject(1, operationId);
      statement.setLong(2, account.id());
      setTenant(statement, 3, tenantId);
      statement.setString(4, SCOPE_HASH);
      statement.setString(5, requestId);
      statement.setString(6, "connect-token-" + operationId);
      statement.executeUpdate();
    }
    insertConnectEnvelope(connection, database, account, tenantId, operationId, requestId, crypto);
  }

  private static void insertConnectEnvelope(
      Connection connection,
      Database database,
      AccountRow account,
      Object tenantId,
      UUID operationId,
      String requestId,
      AccountEnvelopeCrypto crypto)
      throws SQLException {
    AccountEnvelopeBinding binding = connectBinding(operationId, requestId, account.id(), tenantId);
    AccountEncryptedEnvelope envelope =
        crypto.encrypt(
            AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE, binding, connectPlaintext(operationId));
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO "
                + table(database, "account_connect_token_response_envelopes")
                + " (operation_id, operation_kind, account_id, tenant_id, connect_scope_hash, "
                + "request_id, request_digest_version, request_digest, format_version, key_id, "
                + "purpose, nonce, ciphertext, context_evidence_digest, authority_tuple_digest, "
                + "issuance_fence_digest, postcondition_digest) "
                + "VALUES (?, 'CONNECT_TOKEN_ISSUANCE', ?, ?, ?, ?, 1, "
                + DIGEST_SQL
                + ", ?, ?, 'CONNECT_TOKEN_RESPONSE', ?, ?, "
                + DIGEST_SQL
                + ", "
                + DIGEST_SQL
                + ", "
                + DIGEST_SQL
                + ", "
                + DIGEST_SQL
                + ")")) {
      statement.setObject(1, operationId);
      statement.setLong(2, account.id());
      setTenant(statement, 3, tenantId);
      statement.setString(4, SCOPE_HASH);
      statement.setString(5, requestId);
      statement.setInt(6, envelope.formatVersion());
      statement.setString(7, envelope.keyId());
      statement.setBytes(8, envelope.nonce());
      statement.setBytes(9, envelope.ciphertext());
      statement.executeUpdate();
    }
  }

  private static void insertCommittedBareLoginChain(
      Connection connection,
      Database database,
      AccountRow account,
      Object tenantId,
      UUID sourceOperationId,
      UUID exchangeOperationId,
      AccountEnvelopeCrypto crypto)
      throws SQLException {
    String requestId = "bare-login-request-" + exchangeOperationId;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO "
                + table(database, "account_bare_login_exchange_operations")
                + " (operation_id, source_connect_operation_id, account_id, tenant_id, "
                + "connect_scope_hash, request_id, request_digest_version, request_digest, "
                + "status, outcome_code, token_identity, token_hash, context_evidence_digest, "
                + "authority_tuple_digest, issuance_fence_digest, postcondition_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?, 1, "
                + DIGEST_SQL
                + ", 'COMMITTED', 'SUCCESS', ?, "
                + DIGEST_SQL
                + ", "
                + DIGEST_SQL
                + ", "
                + DIGEST_SQL
                + ", "
                + DIGEST_SQL
                + ", "
                + DIGEST_SQL
                + ")")) {
      statement.setObject(1, exchangeOperationId);
      statement.setObject(2, sourceOperationId);
      statement.setLong(3, account.id());
      setTenant(statement, 4, tenantId);
      statement.setString(5, SCOPE_HASH);
      statement.setString(6, requestId);
      statement.setString(7, "bare-login-token-" + exchangeOperationId);
      statement.executeUpdate();
    }
    insertBareLoginEnvelope(
        connection,
        database,
        account,
        tenantId,
        sourceOperationId,
        exchangeOperationId,
        requestId,
        crypto);
  }

  private static void insertBareLoginEnvelope(
      Connection connection,
      Database database,
      AccountRow account,
      Object tenantId,
      UUID sourceOperationId,
      UUID exchangeOperationId,
      String requestId,
      AccountEnvelopeCrypto crypto)
      throws SQLException {
    AccountEnvelopeBinding binding =
        bareLoginBinding(exchangeOperationId, requestId, account.id(), tenantId, sourceOperationId);
    AccountEncryptedEnvelope envelope =
        crypto.encrypt(
            AccountEnvelopePurpose.BARE_LOGIN_RESPONSE,
            binding,
            bareLoginPlaintext(exchangeOperationId));
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO "
                + table(database, "account_bare_login_response_envelopes")
                + " (operation_id, operation_kind, source_connect_operation_id, account_id, "
                + "tenant_id, connect_scope_hash, request_id, request_digest_version, "
                + "request_digest, format_version, key_id, purpose, nonce, ciphertext, "
                + "context_evidence_digest, authority_tuple_digest, issuance_fence_digest, "
                + "postcondition_digest) VALUES (?, 'BARE_LOGIN_EXCHANGE', ?, ?, ?, ?, ?, 1, "
                + DIGEST_SQL
                + ", ?, ?, 'BARE_LOGIN_RESPONSE', ?, ?, "
                + DIGEST_SQL
                + ", "
                + DIGEST_SQL
                + ", "
                + DIGEST_SQL
                + ", "
                + DIGEST_SQL
                + ")")) {
      statement.setObject(1, exchangeOperationId);
      statement.setObject(2, sourceOperationId);
      statement.setLong(3, account.id());
      setTenant(statement, 4, tenantId);
      statement.setString(5, SCOPE_HASH);
      statement.setString(6, requestId);
      statement.setInt(7, envelope.formatVersion());
      statement.setString(8, envelope.keyId());
      statement.setBytes(9, envelope.nonce());
      statement.setBytes(10, envelope.ciphertext());
      statement.executeUpdate();
    }
  }

  private static AccountEnvelopeCrypto keyRing(Path directory) throws IOException {
    byte[] connectKey = new byte[32];
    byte[] bareLoginKey = new byte[32];
    Arrays.fill(connectKey, (byte) 1);
    Arrays.fill(bareLoginKey, (byte) 2);
    Path manifest = directory.resolve("account-envelope-test-ring.v1");
    Files.writeString(
        manifest,
        "version=1\nactiveKeyId=k1\n"
            + "key:k1:bare-login="
            + Base64.getUrlEncoder().withoutPadding().encodeToString(bareLoginKey)
            + "\nkey:k1:connect-token="
            + Base64.getUrlEncoder().withoutPadding().encodeToString(connectKey)
            + "\n",
        StandardCharsets.US_ASCII);
    Arrays.fill(connectKey, (byte) 0);
    Arrays.fill(bareLoginKey, (byte) 0);
    return new AccountEnvelopeCrypto(manifest);
  }

  private static void assertStoredEnvelopesDecrypt(
      Database database,
      AccountEnvelopeCrypto crypto,
      int expectedConnectEnvelopes,
      int expectedBareLoginEnvelopes)
      throws SQLException {
    assertThat(assertConnectEnvelopesDecrypt(database, crypto)).isEqualTo(expectedConnectEnvelopes);
    assertThat(assertBareLoginEnvelopesDecrypt(database, crypto))
        .isEqualTo(expectedBareLoginEnvelopes);
  }

  private static int assertConnectEnvelopesDecrypt(Database database, AccountEnvelopeCrypto crypto)
      throws SQLException {
    String sql =
        "SELECT op.operation_id, op.request_id, op.account_id, op.tenant_id::text, "
            + "op.request_digest, op.context_evidence_digest, op.authority_tuple_digest, "
            + "op.issuance_fence_digest, op.postcondition_digest, env.format_version, "
            + "env.key_id, env.purpose, env.nonce, env.ciphertext "
            + "FROM "
            + table(database, "account_connect_token_issuance_operations")
            + " op JOIN "
            + table(database, "account_connect_token_response_envelopes")
            + " env USING (operation_id) ORDER BY op.operation_id";
    try (Connection connection = database.dataSource().getConnection();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      int rows = 0;
      while (result.next()) {
        UUID operationId = result.getObject(1, UUID.class);
        AccountEnvelopeBinding binding =
            new AccountEnvelopeBinding(
                AccountEnvelopeBinding.OperationKind.CONNECT_TOKEN_ISSUANCE,
                operationId.toString(),
                result.getString(2),
                Long.toString(result.getLong(3)),
                result.getString(4),
                CONNECT_SCOPE_ID,
                null,
                result.getBytes(5),
                result.getBytes(6),
                result.getBytes(7),
                result.getBytes(8),
                result.getBytes(9));
        AccountEncryptedEnvelope envelope =
            new AccountEncryptedEnvelope(
                result.getInt(10),
                result.getString(11),
                AccountEnvelopePurpose.valueOf(result.getString(12)),
                result.getBytes(13),
                result.getBytes(14));
        assertThat(crypto.decrypt(envelope, AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE, binding))
            .containsExactly(connectPlaintext(operationId));
        rows++;
      }
      return rows;
    }
  }

  private static int assertBareLoginEnvelopesDecrypt(
      Database database, AccountEnvelopeCrypto crypto) throws SQLException {
    String sql =
        "SELECT op.operation_id, op.source_connect_operation_id, op.request_id, op.account_id, "
            + "op.tenant_id::text, op.request_digest, op.context_evidence_digest, "
            + "op.authority_tuple_digest, op.issuance_fence_digest, op.postcondition_digest, "
            + "env.format_version, env.key_id, env.purpose, env.nonce, env.ciphertext "
            + "FROM "
            + table(database, "account_bare_login_exchange_operations")
            + " op JOIN "
            + table(database, "account_bare_login_response_envelopes")
            + " env USING (operation_id) ORDER BY op.operation_id";
    try (Connection connection = database.dataSource().getConnection();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      int rows = 0;
      while (result.next()) {
        UUID operationId = result.getObject(1, UUID.class);
        UUID sourceOperationId = result.getObject(2, UUID.class);
        AccountEnvelopeBinding binding =
            new AccountEnvelopeBinding(
                AccountEnvelopeBinding.OperationKind.BARE_LOGIN_EXCHANGE,
                operationId.toString(),
                result.getString(3),
                Long.toString(result.getLong(4)),
                result.getString(5),
                CONNECT_SCOPE_ID,
                sourceOperationId.toString(),
                result.getBytes(6),
                result.getBytes(7),
                result.getBytes(8),
                result.getBytes(9),
                result.getBytes(10));
        AccountEncryptedEnvelope envelope =
            new AccountEncryptedEnvelope(
                result.getInt(11),
                result.getString(12),
                AccountEnvelopePurpose.valueOf(result.getString(13)),
                result.getBytes(14),
                result.getBytes(15));
        assertThat(crypto.decrypt(envelope, AccountEnvelopePurpose.BARE_LOGIN_RESPONSE, binding))
            .containsExactly(bareLoginPlaintext(operationId));
        rows++;
      }
      return rows;
    }
  }

  private static AccountEnvelopeBinding connectBinding(
      UUID operationId, String requestId, long accountId, Object tenantId) {
    return new AccountEnvelopeBinding(
        AccountEnvelopeBinding.OperationKind.CONNECT_TOKEN_ISSUANCE,
        operationId.toString(),
        requestId,
        Long.toString(accountId),
        tenantText(tenantId),
        CONNECT_SCOPE_ID,
        null,
        digest(),
        digest(),
        digest(),
        digest(),
        digest());
  }

  private static AccountEnvelopeBinding bareLoginBinding(
      UUID operationId, String requestId, long accountId, Object tenantId, UUID sourceOperationId) {
    return new AccountEnvelopeBinding(
        AccountEnvelopeBinding.OperationKind.BARE_LOGIN_EXCHANGE,
        operationId.toString(),
        requestId,
        Long.toString(accountId),
        tenantText(tenantId),
        CONNECT_SCOPE_ID,
        sourceOperationId.toString(),
        digest(),
        digest(),
        digest(),
        digest(),
        digest());
  }

  private static String tenantText(Object tenantId) {
    return tenantId instanceof Long numericTenantId
        ? Long.toString(numericTenantId)
        : tenantId.toString();
  }

  private static byte[] digest() {
    byte[] value = new byte[32];
    Arrays.fill(value, (byte) 0x11);
    return value;
  }

  private static byte[] connectPlaintext(UUID operationId) {
    return ("original-connect-token-response:" + operationId).getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] bareLoginPlaintext(UUID operationId) {
    return ("original-bare-login-response:" + operationId).getBytes(StandardCharsets.UTF_8);
  }

  private static void setTenant(PreparedStatement statement, int index, Object tenantId)
      throws SQLException {
    if (tenantId instanceof Long numericTenantId) {
      statement.setLong(index, numericTenantId);
    } else {
      statement.setObject(index, tenantId);
    }
  }

  private static void execute(Connection connection, String sql, Object... values)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < values.length; index++) {
        statement.setObject(index + 1, values[index]);
      }
      statement.executeUpdate();
    }
  }

  private static Flyway flyway(Database database, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(database.dataSource())
            .schemas(database.schema())
            .defaultSchema(database.schema())
            .table(HISTORY_TABLE)
            .locations("classpath:db/migration");
    if (target != null) {
      configuration.target(MigrationVersion.fromVersion(target));
    }
    return configuration.load();
  }

  private static Database newDatabase() throws SQLException {
    String schema = "account_connect_uuid_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dataSource.setDriverClassName("org.postgresql.Driver");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA " + schema);
    }
    return new Database(schema, dataSource);
  }

  private static String table(Database database, String table) {
    return database.schema() + "." + table;
  }

  private record Database(String schema, DataSource dataSource) {}

  private record AccountRow(long id) {}

  private record EvidenceSnapshot(
      List<String> connectOperations,
      List<String> connectEnvelopes,
      List<String> connectCiphertexts,
      List<String> bareLoginOperations,
      List<String> bareLoginEnvelopes,
      List<String> bareLoginCiphertexts) {}
}
