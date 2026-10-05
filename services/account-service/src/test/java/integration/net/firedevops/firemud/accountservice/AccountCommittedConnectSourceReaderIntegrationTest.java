package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountCommittedConnectSource;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceIdentity;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceOperation.Lifecycle;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository;
import net.firedevops.firemud.accountservice.repository.LegacyTenantSourceEvidence;
import net.firedevops.firemud.accountservice.security.AccountEncryptedEnvelope;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeBinding;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCrypto;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCryptoException;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCryptoException.Failure;
import net.firedevops.firemud.accountservice.security.AccountEnvelopePurpose;
import net.firedevops.firemud.accountservice.security.AccountGameplayConnectSourceVerifier;
import net.firedevops.firemud.accountservice.service.AccountCommittedConnectSourceReader;
import net.firedevops.firemud.accountservice.service.AccountCommittedConnectSourceReader.OriginalSourceEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.GatewayConnectContextCodec;
import net.firedevops.firemud.common.security.GatewayConnectContextSignature;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyAccountTenantAssociationResponse;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
// Peer identity is context-scoped here; this proves the component caller guard, not network mTLS.
class AccountCommittedConnectSourceReaderIntegrationTest {
  private static final String WORKLOAD_NAMESPACE = "account-test";
  private static final String ACCOUNT_ISSUER = "https://account.example.test";
  private static final String ACCOUNT_KEY_ID = "account-test-key";
  private static final String GATEWAY_KEY_ID = "gateway-test-key";
  private static final long LEGACY_TENANT_ID = 77L;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
  private static final SecureRandom FIXTURE_RANDOM = new SecureRandom();

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @TempDir Path tempDir;

  @Test
  void readsExactCommittedOriginalSourceAndLostResultWithoutMutation() throws Exception {
    PreparedAccount prepared = newPreparedAccount();
    SourceFixture source = newSource(prepared, SourceState.COMMITTED);
    Snapshot before = sourceSnapshot(prepared, source.identity());

    OriginalSourceEvidence first =
        withPeer(
            peer(WORKLOAD_NAMESPACE, "game-session-service"),
            () ->
                inTransaction(
                    prepared.db().transaction(),
                    () -> prepared.reader().read(source.identity(), source.gatewayEnvelope())));
    OriginalSourceEvidence reread =
        withPeer(
            peer(WORKLOAD_NAMESPACE, "game-session-service"),
            () ->
                inTransaction(
                    prepared.db().transaction(),
                    () -> prepared.reader().read(source.identity(), source.gatewayEnvelope())));

    assertThat(first.operationId()).isEqualTo(reread.operationId());
    assertThat(first.responseEnvelopeKeyId()).isEqualTo(reread.responseEnvelopeKeyId());
    assertThat(first.gatewayKeyId()).isEqualTo(GATEWAY_KEY_ID);
    assertThat(first.sourceTokenHash()).containsExactly(reread.sourceTokenHash());
    assertThat(first.originalSourceClaims()).isEqualTo(source.sourceClaims());
    assertThat(first.gatewayContextClaims()).isEqualTo(source.gatewayClaims());
    assertThat(first.originalSourceClaims()).containsEntry("aud", "gameplay-connect");
    assertThat(first.originalSourceClaims().get("jti")).isEqualTo(source.tokenIdentity());
    assertThat(first.toString()).doesNotContain(source.identity().connectScopeId());
    assertThatThrownBy(() -> first.originalSourceClaims().put("extra", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                ((Map<String, Object>) first.originalSourceClaims().get("authorityTuple"))
                    .put("extra", BigInteger.ONE))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(sourceSnapshot(prepared, source.identity())).isEqualTo(before);
  }

  @Test
  void requiresExactGameSessionCallerAndRegisteredGatewayAndAccountKeys() throws Exception {
    PreparedAccount prepared = newPreparedAccount();
    SourceFixture source = newSource(prepared, SourceState.COMMITTED);

    assertThatThrownBy(
            () ->
                withPeer(
                    peer(WORKLOAD_NAMESPACE, "account-service"),
                    () ->
                        inTransaction(
                            prepared.db().transaction(),
                            () ->
                                prepared
                                    .reader()
                                    .read(source.identity(), source.gatewayEnvelope()))))
        .isInstanceOf(AdminAuthorizationException.class);

    assertThatThrownBy(
            () ->
                withoutPeer(
                    () ->
                        inTransaction(
                            prepared.db().transaction(),
                            () ->
                                prepared
                                    .reader()
                                    .read(source.identity(), source.gatewayEnvelope()))))
        .isInstanceOf(AdminAuthorizationException.class);

    assertThatThrownBy(
            () ->
                withPeer(
                    peer("other-account-test", "game-session-service"),
                    () ->
                        inTransaction(
                            prepared.db().transaction(),
                            () ->
                                prepared
                                    .reader()
                                    .read(source.identity(), source.gatewayEnvelope()))))
        .isInstanceOf(AdminAuthorizationException.class);

    Snapshot beforeOutsideTransaction = sourceSnapshot(prepared, source.identity());
    assertThatThrownBy(
            () ->
                withPeer(
                    peer(WORKLOAD_NAMESPACE, "game-session-service"),
                    () -> prepared.reader().read(source.identity(), source.gatewayEnvelope())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active owner transaction");
    assertThat(sourceSnapshot(prepared, source.identity())).isEqualTo(beforeOutsideTransaction);

    SourceFixture unknownGateway =
        newSource(
            prepared,
            SourceState.COMMITTED,
            new SourceOptions(false, false, false, false, true, false, false));
    assertThatThrownBy(
            () ->
                withPeer(
                    peer(WORKLOAD_NAMESPACE, "game-session-service"),
                    () ->
                        inTransaction(
                            prepared.db().transaction(),
                            () ->
                                prepared
                                    .reader()
                                    .read(
                                        unknownGateway.identity(),
                                        unknownGateway.gatewayEnvelope()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown Gateway verification key");

    SourceFixture unknownSource =
        newSource(
            prepared,
            SourceState.COMMITTED,
            new SourceOptions(true, false, false, false, false, false, false));
    assertThatThrownBy(
            () ->
                withPeer(
                    peer(WORKLOAD_NAMESPACE, "game-session-service"),
                    () ->
                        inTransaction(
                            prepared.db().transaction(),
                            () ->
                                prepared
                                    .reader()
                                    .read(
                                        unknownSource.identity(),
                                        unknownSource.gatewayEnvelope()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("invalid Account gameplay-connect source JWT");
  }

  @Test
  void rejectsWrongStoredTokenHashJtiAndSourceTargetCorrespondence() throws Exception {
    PreparedAccount prepared = newPreparedAccount();
    SourceFixture wrongHash =
        newSource(
            prepared,
            SourceState.COMMITTED,
            new SourceOptions(false, true, false, false, false, false, false));
    SourceFixture wrongJti =
        newSource(
            prepared,
            SourceState.COMMITTED,
            new SourceOptions(false, false, true, false, false, false, false));
    SourceFixture wrongTarget =
        newSource(
            prepared,
            SourceState.COMMITTED,
            new SourceOptions(false, false, false, true, false, false, false));

    assertReadRejected(prepared, wrongHash, IllegalStateException.class, "token hash");
    assertReadRejected(prepared, wrongJti, IllegalStateException.class, "token identity");
    assertReadRejected(
        prepared, wrongTarget, IllegalArgumentException.class, "does not exactly preserve");
  }

  @Test
  void requiresPresentCommittedSuccessfulV35SourceAndResponseEnvelope() throws Exception {
    PreparedAccount prepared = newPreparedAccount();
    SourceFixture absent = newSource(prepared, SourceState.ABSENT);
    SourceFixture pending = newSource(prepared, SourceState.PENDING);
    SourceFixture failed = newSource(prepared, SourceState.FAILED);

    assertReadRejected(prepared, absent, IllegalStateException.class, "source is absent");
    assertReadRejected(
        prepared, pending, IllegalStateException.class, "not a complete committed success");
    assertReadRejected(
        prepared, failed, IllegalStateException.class, "not a complete committed success");
  }

  @Test
  void rejectsUnmappedAndChangedRetainedTenantEvidence() throws Exception {
    PreparedAccount prepared = newPreparedAccount();
    SourceFixture unmappedTenant =
        newSource(
            prepared,
            SourceState.COMMITTED,
            new SourceOptions(false, false, false, false, false, false, true));
    assertReadRejected(
        prepared,
        unmappedTenant,
        IllegalStateException.class,
        "approved Account tenant association is absent");

    SourceFixture changedRetainedEvidence = newSource(prepared, SourceState.COMMITTED);
    inTransaction(
        prepared.db().transaction(),
        () ->
            prepared
                .db()
                .dsl()
                .execute(
                    "UPDATE account_legacy_tenant_sources "
                        + "SET captured_at = captured_at + INTERVAL '1 second' "
                        + "WHERE legacy_tenant_id = ?",
                    prepared.tenantId()));
    assertReadRejected(
        prepared,
        changedRetainedEvidence,
        IllegalStateException.class,
        "approved Account source evidence differs from retained rows");
  }

  @Test
  void rejectsWrongPurposeAndWrongPurposeKeyForStoredEnvelope() throws Exception {
    PreparedAccount prepared = newPreparedAccount();
    SourceFixture source = newSource(prepared, SourceState.COMMITTED);
    AccountCommittedConnectSource stored =
        inTransaction(
            prepared.db().transaction(),
            () ->
                prepared
                    .db()
                    .issuanceRepository()
                    .readCommittedResponseEnvelope(source.identity())
                    .orElseThrow());
    AccountEnvelopeBinding otherPurposeBinding =
        new AccountEnvelopeBinding(
            AccountEnvelopeBinding.OperationKind.BARE_LOGIN_EXCHANGE,
            UUID.randomUUID().toString(),
            "other-request",
            Long.toString(source.identity().accountId()),
            Long.toString(source.identity().tenantId()),
            source.identity().connectScopeId(),
            UUID.randomUUID().toString(),
            digest("other-request"),
            digest("other-context"),
            digest("other-authority"),
            digest("other-fence"),
            digest("other-postcondition"));
    assertThatThrownBy(
            () ->
                prepared
                    .db()
                    .envelopeCrypto()
                    .decrypt(
                        stored.responseEnvelope().envelope(),
                        AccountEnvelopePurpose.BARE_LOGIN_RESPONSE,
                        otherPurposeBinding))
        .isInstanceOf(AccountEnvelopeCryptoException.class)
        .extracting(exception -> ((AccountEnvelopeCryptoException) exception).failure())
        .isEqualTo(Failure.PURPOSE_MISMATCH);

    AccountCommittedConnectSourceReader wrongKeyReader =
        readerWithCrypto(prepared, differentConnectPurposeKey());
    assertReadRejected(
        wrongKeyReader,
        prepared,
        source,
        AccountEnvelopeCryptoException.class,
        "AUTHENTICATION_FAILED");
  }

  @Test
  void doesNotReturnWhenAccountFenceWaitOutlivesTheUnchangedGatewayDeadline() throws Exception {
    PreparedAccount prepared = newPreparedAccount();
    SourceFixture source =
        newSource(
            prepared,
            SourceState.COMMITTED,
            new SourceOptions(false, false, false, false, false, true, false));
    CountDownLatch accountRowLocked = new CountDownLatch(1);
    CountDownLatch releaseAccountRow = new CountDownLatch(1);
    CountDownLatch readerBackendIdentified = new CountDownLatch(1);
    AtomicInteger blockerBackendPid = new AtomicInteger();
    AtomicInteger readerBackendPid = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Future<?> blocker =
        executor.submit(
            () ->
                inTransaction(
                    prepared.db().transaction(),
                    () -> {
                      blockerBackendPid.set(requirePositiveBackendPid(prepared.db().dsl()));
                      prepared
                          .db()
                          .dsl()
                          .resultQuery(
                              "SELECT id FROM accounts WHERE id = ? FOR UPDATE",
                              prepared.accountId())
                          .fetchOne();
                      accountRowLocked.countDown();
                      if (!await(releaseAccountRow, 20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Account fence holder was not released");
                      }
                      return null;
                    }));
    try {
      assertThat(accountRowLocked.await(5, TimeUnit.SECONDS)).isTrue();
      Future<OriginalSourceEvidence> reader =
          executor.submit(
              () ->
                  withPeer(
                      peer(WORKLOAD_NAMESPACE, "game-session-service"),
                      () ->
                          inTransaction(
                              prepared.db().transaction(),
                              () -> {
                                readerBackendPid.set(
                                    requirePositiveBackendPid(prepared.db().dsl()));
                                readerBackendIdentified.countDown();
                                return prepared
                                    .reader()
                                    .read(source.identity(), source.gatewayEnvelope());
                              })));
      if (!readerBackendIdentified.await(5, TimeUnit.SECONDS)) {
        try {
          reader.get(0, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException failure) {
          throw new AssertionError(
              "Reader failed before identifying its backend", failure.getCause());
        } catch (java.util.concurrent.TimeoutException stillRunning) {
          // The bounded identity wait elapsed while the worker was still running.
        }
        throw new AssertionError(
            "Reader backend PID was not identified before the bounded deadline");
      }
      assertThat(
              awaitAccountFenceBlock(
                  prepared.db().dsl(),
                  readerBackendPid.get(),
                  blockerBackendPid.get(),
                  Duration.ofSeconds(5)))
          .as("the exact reader backend is blocked by the exact Account row fence backend")
          .isTrue();

      // The source token remains valid; only the Gateway's signed, shortened deadline expires.
      prepared.clock().advance(Duration.ofSeconds(3));
      releaseAccountRow.countDown();
      assertThatThrownBy(() -> reader.get(10, TimeUnit.SECONDS))
          .satisfies(
              thrown -> {
                Throwable cause = thrown;
                while (cause.getCause() != null) {
                  cause = cause.getCause();
                }
                assertThat(cause)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Gateway context is expired");
              });
    } finally {
      releaseAccountRow.countDown();
      blocker.get(10, TimeUnit.SECONDS);
      executor.shutdownNow();
    }
  }

  private PreparedAccount newPreparedAccount() throws Exception {
    String schema =
        "account_source_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
    dataSource.setUrl(postgres.getJdbcUrl() + separator + "currentSchema=" + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .target("25")
        .load()
        .migrate();

    DSLContext seedDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    long accountId =
        insertRetainedRows(seedDsl, LEGACY_TENANT_ID, "source-reader-" + UUID.randomUUID());
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();

    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    var sourceEvidence = new LegacyTenantSourceEvidence(dsl);
    var generations = new AccountAuthorityGenerationRepository(dsl);
    var associations =
        new ApprovedLegacyTenantAssociationRepository(
            dsl, sourceEvidence, WORKLOAD_NAMESPACE, generations);
    UUID accountUuid =
        Objects.requireNonNull(
            dsl.resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", accountId)
                .fetchOne(0, UUID.class));
    UUID tenantUuid = UUID.randomUUID();
    String retainedEvidenceDigest = sourceEvidence.digest(LEGACY_TENANT_ID);
    inTransaction(
        transaction,
        () ->
            associations.importApproved(
                LEGACY_TENANT_ID, approvedAssociation(retainedEvidenceDigest, tenantUuid)));

    byte[] bareLoginKey = new byte[32];
    byte[] connectTokenKey = new byte[32];
    FIXTURE_RANDOM.nextBytes(bareLoginKey);
    do {
      FIXTURE_RANDOM.nextBytes(connectTokenKey);
    } while (MessageDigest.isEqual(bareLoginKey, connectTokenKey));
    Path manifest = tempDir.resolve("account-response-ring-" + UUID.randomUUID() + ".v1");
    String ringManifest =
        "version=1\nactiveKeyId=test-key\nkey:test-key:bare-login="
            + BASE64_URL.encodeToString(bareLoginKey)
            + "\nkey:test-key:connect-token="
            + BASE64_URL.encodeToString(connectTokenKey)
            + "\n";
    Files.writeString(manifest, ringManifest, StandardCharsets.US_ASCII);
    AccountEnvelopeCrypto envelopeCrypto = new AccountEnvelopeCrypto(manifest);

    KeyPair accountKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    KeyPair unknownAccountKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    KeyPair gatewayKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    KeyPair unknownGatewayKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    TestClock clock = new TestClock(Instant.parse("2026-10-01T00:00:00Z"));
    AccountGameplayConnectSourceVerifier verifier =
        new AccountGameplayConnectSourceVerifier(
            ACCOUNT_ISSUER, Map.of(ACCOUNT_KEY_ID, accountKeyPair.getPublic()), 16 * 1024, clock);
    Map<String, java.security.PublicKey> gatewayKeys =
        Map.of(GATEWAY_KEY_ID, gatewayKeyPair.getPublic());
    AccountConnectTokenIssuanceRepository issuanceRepository =
        new AccountConnectTokenIssuanceRepository(dsl);
    AccountRepository accountRepository = new AccountRepository(dsl);
    AccountTenantIdentityResolver tenantResolver =
        new AccountTenantIdentityResolver(associations, sourceEvidence, WORKLOAD_NAMESPACE);
    AccountJoinOperationRepository joinRepository = new AccountJoinOperationRepository(dsl);
    AccountCommittedConnectSourceReader reader =
        new AccountCommittedConnectSourceReader(
            issuanceRepository,
            accountRepository,
            tenantResolver,
            joinRepository,
            envelopeCrypto,
            verifier,
            gatewayKeys,
            clock,
            WORKLOAD_NAMESPACE);
    TestContext db = new TestContext(dsl, transaction, issuanceRepository, envelopeCrypto);
    return new PreparedAccount(
        db,
        accountId,
        LEGACY_TENANT_ID,
        accountUuid,
        tenantUuid,
        accountKeyPair,
        unknownAccountKeyPair,
        gatewayKeyPair,
        unknownGatewayKeyPair,
        verifier,
        gatewayKeys,
        accountRepository,
        tenantResolver,
        joinRepository,
        reader,
        clock);
  }

  private SourceFixture newSource(PreparedAccount prepared, SourceState state) throws Exception {
    return newSource(prepared, state, SourceOptions.NORMAL);
  }

  private SourceFixture newSource(
      PreparedAccount prepared, SourceState state, SourceOptions options) throws Exception {
    String requestId = "source-request-" + UUID.randomUUID();
    String connectScopeId = "source-scope-" + UUID.randomUUID();
    long sourceTenantId =
        options.unmappedTenant() ? prepared.tenantId() + 1000L : prepared.tenantId();
    UUID sourceTenantUuid = options.unmappedTenant() ? UUID.randomUUID() : prepared.tenantUuid();
    AccountConnectTokenIssuanceIdentity identity =
        new AccountConnectTokenIssuanceIdentity(
            prepared.accountId(), sourceTenantId, connectScopeId, requestId);
    String tokenIdentity = "source-jti-" + UUID.randomUUID();
    Map<String, Object> sourceClaims =
        sourceClaims(
            prepared.accountUuid(), sourceTenantUuid, identity, tokenIdentity, prepared.clock());
    KeyPair tokenSigner =
        options.unknownSourceKey() ? prepared.unknownAccountKeyPair() : prepared.accountKeyPair();
    String tokenKeyId = options.unknownSourceKey() ? "unregistered-account-key" : ACCOUNT_KEY_ID;
    byte[] compactJwt = signAccountJwt(sourceClaims, tokenKeyId, tokenSigner);
    byte[] storedHash = sha256(compactJwt);
    if (options.wrongHash()) {
      storedHash[0] ^= 0x01;
    }
    String storedTokenIdentity = options.wrongJti() ? "different-source-jti" : tokenIdentity;

    Map<String, Object> gatewaySourceClaims = new LinkedHashMap<>(sourceClaims);
    if (options.wrongTarget()) {
      gatewaySourceClaims.put("worldSlug", "different-world");
    }
    Map<String, Object> projectedGatewayClaims =
        GatewayConnectContextCodec.projectVerifiedAccountGameplayConnectClaims(
            gatewaySourceClaims,
            prepared.clock().instant().getEpochSecond(),
            "gateway-request-" + UUID.randomUUID());
    Map<String, Object> gatewayClaims = new LinkedHashMap<>(projectedGatewayClaims);
    if (options.shortGatewayDeadline()) {
      gatewayClaims.put(
          "expiresAt", BigInteger.valueOf(prepared.clock().instant().getEpochSecond() + 2L));
    }
    byte[] gatewayPayload = JSON.writeValueAsBytes(gatewayClaims);
    KeyPair gatewaySigner =
        options.unknownGatewayKey() ? prepared.unknownGatewayKeyPair() : prepared.gatewayKeyPair();
    String gatewayKid = options.unknownGatewayKey() ? "unregistered-gateway-key" : GATEWAY_KEY_ID;
    String gatewayEnvelope =
        GatewayConnectContextSignature.sign(gatewayPayload, gatewayKid, gatewaySigner.getPrivate());

    if (state != SourceState.ABSENT) {
      byte[] requestDigest =
          sha256(("source-request-digest:" + requestId).getBytes(StandardCharsets.UTF_8));
      inTransaction(
          prepared.db().transaction(),
          () -> {
            var claim = prepared.db().issuanceRepository().claim(identity, requestDigest);
            if (state == SourceState.PENDING) {
              return null;
            }
            AccountEnvelopeBinding binding =
                new AccountEnvelopeBinding(
                    AccountEnvelopeBinding.OperationKind.CONNECT_TOKEN_ISSUANCE,
                    claim.operation().operationId().toString(),
                    identity.requestId(),
                    Long.toString(identity.accountId()),
                    Long.toString(identity.tenantId()),
                    identity.connectScopeId(),
                    null,
                    requestDigest,
                    digest("context:" + requestId),
                    digest("authority:" + requestId),
                    digest("fence:" + requestId),
                    digest("postcondition:" + requestId));
            boolean success = state == SourceState.COMMITTED;
            byte[] plaintext =
                success
                    ? compactJwt
                    : "deterministic issuance failure".getBytes(StandardCharsets.UTF_8);
            AccountEncryptedEnvelope encrypted =
                prepared
                    .db()
                    .envelopeCrypto()
                    .encrypt(AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE, binding, plaintext);
            prepared
                .db()
                .issuanceRepository()
                .completeWithEnvelope(
                    claim,
                    requestDigest,
                    success ? Lifecycle.COMMITTED : Lifecycle.FAILED,
                    success ? "SUCCESS" : "SOURCE_UNAVAILABLE",
                    success ? storedTokenIdentity : null,
                    success ? storedHash : null,
                    binding,
                    encrypted);
            return null;
          });
    }
    return new SourceFixture(identity, gatewayEnvelope, sourceClaims, gatewayClaims, tokenIdentity);
  }

  private AccountEnvelopeCrypto differentConnectPurposeKey() throws Exception {
    byte[] bareLoginKey = new byte[32];
    byte[] connectTokenKey = new byte[32];
    FIXTURE_RANDOM.nextBytes(bareLoginKey);
    do {
      FIXTURE_RANDOM.nextBytes(connectTokenKey);
    } while (MessageDigest.isEqual(bareLoginKey, connectTokenKey));
    Path manifest = tempDir.resolve("wrong-account-response-ring-" + UUID.randomUUID() + ".v1");
    String ringManifest =
        "version=1\nactiveKeyId=test-key\nkey:test-key:bare-login="
            + BASE64_URL.encodeToString(bareLoginKey)
            + "\nkey:test-key:connect-token="
            + BASE64_URL.encodeToString(connectTokenKey)
            + "\n";
    Files.writeString(manifest, ringManifest, StandardCharsets.US_ASCII);
    return new AccountEnvelopeCrypto(manifest);
  }

  private static AccountCommittedConnectSourceReader readerWithCrypto(
      PreparedAccount prepared, AccountEnvelopeCrypto crypto) {
    return new AccountCommittedConnectSourceReader(
        prepared.db().issuanceRepository(),
        prepared.accountRepository(),
        prepared.tenantResolver(),
        prepared.joinRepository(),
        crypto,
        prepared.verifier(),
        prepared.gatewayKeys(),
        prepared.clock(),
        WORKLOAD_NAMESPACE);
  }

  private static Map<String, Object> sourceClaims(
      UUID accountUuid,
      UUID tenantUuid,
      AccountConnectTokenIssuanceIdentity identity,
      String tokenIdentity,
      Clock clock) {
    BigInteger one = BigInteger.ONE;
    BigInteger now = BigInteger.valueOf(clock.instant().getEpochSecond());
    Map<String, Object> tenantGeneration = Map.of(tenantUuid.toString(), one);
    Map<String, Object> membershipVersion = Map.of(tenantUuid.toString(), one.toString());
    Map<String, Object> accountSecurityCutoff =
        Map.of(
            "accountAuthorityGeneration", one,
            "outboxStreamKey", "account:auth-authority:v1:account/" + accountUuid,
            "outboxSequence", one);
    Map<String, Object> tenantBillingCutoffEntry =
        Map.of(
            "tenantAuthorityGeneration",
            one,
            "tenantBillingSequence",
            BigInteger.ZERO,
            "outboxStreamKey",
            "account:auth-authority:v1:tenant/" + tenantUuid,
            "outboxSequence",
            one);
    Map<String, Object> authorityTuple =
        Map.of(
            "issuerAuthGeneration", one,
            "accountAuthorityGeneration", one,
            "tenantAuthorityGeneration", tenantGeneration,
            "membershipAuthorityGeneration", tenantGeneration,
            "privateRealmGrantVersions", List.of(),
            "accountSecurityCutoff", accountSecurityCutoff,
            "tenantBillingCutoff", Map.of(tenantUuid.toString(), tenantBillingCutoffEntry));
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", ACCOUNT_ISSUER);
    claims.put("aud", "gameplay-connect");
    claims.put("iat", now.subtract(one));
    claims.put("exp", now.add(BigInteger.valueOf(20)));
    claims.put("jti", tokenIdentity);
    claims.put("accountId", accountUuid.toString());
    claims.put("tenantId", tenantUuid.toString());
    claims.put("realmId", UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb").toString());
    claims.put("worldSlug", "world");
    claims.put("realmSlug", "realm");
    claims.put(
        "playableStateNamespaceId",
        UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc").toString());
    claims.put("playableStateScope", "PLAYABLE_STATE_SCOPE_SHARED");
    claims.put(
        "gameInstanceId", UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd").toString());
    claims.put("pointerVersion", one);
    claims.put("catalogRevision", one);
    claims.put("connectScopeId", identity.connectScopeId());
    claims.put("requestId", identity.requestId());
    claims.put("authorityTuple", authorityTuple);
    claims.put("membershipVersion", membershipVersion);
    claims.put("replayAdmissionFence", one);
    return claims;
  }

  private static byte[] signAccountJwt(Map<String, Object> claims, String keyId, KeyPair signer)
      throws Exception {
    String header = "{\"alg\":\"EdDSA\",\"kid\":\"" + keyId + "\",\"typ\":\"JWT\"}";
    String encodedHeader = BASE64_URL.encodeToString(header.getBytes(StandardCharsets.US_ASCII));
    String encodedPayload = BASE64_URL.encodeToString(JSON.writeValueAsBytes(claims));
    String signingInput = encodedHeader + "." + encodedPayload;
    Signature signature = Signature.getInstance("Ed25519");
    signature.initSign(signer.getPrivate());
    signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
    return (signingInput + "." + BASE64_URL.encodeToString(signature.sign()))
        .getBytes(StandardCharsets.US_ASCII);
  }

  private static ResolveLegacyAccountTenantAssociationResponse approvedAssociation(
      String sourceEvidenceDigest, UUID tenantUuid) {
    return ResolveLegacyAccountTenantAssociationResponse.newBuilder()
        .setLegacyAccountTenantId(LEGACY_TENANT_ID)
        .setCanonicalTenantId(tenantUuid.toString())
        .setSourceLegacyGameTenantId("legacy-game-source-reader")
        .setSourceGameRowId(7L)
        .setAccountEvidenceDigest(sourceEvidenceDigest)
        .setOperationId(UUID.randomUUID().toString())
        .setManifestDigest("sha256:" + "b".repeat(64))
        .setManifestSignature(Base64.getEncoder().encodeToString(new byte[64]))
        .setTargetNamespace(WORKLOAD_NAMESPACE)
        .setSignerKeyId("game-design-owner-test")
        .setApprovedBy("owner@example.test")
        .setApprovalReference("committed-connect-source-test")
        .setSignedAt("2026-10-01T00:00:00Z")
        .setOperationEntryCount(1)
        .setManifestSchemaVersion(1)
        .build();
  }

  private static long insertRetainedRows(DSLContext dsl, long tenantId, String username) {
    Number inserted =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO accounts (username, email, password_hash, tenant_id) "
                        + "VALUES (?, ?, 'hash', ?) RETURNING id",
                    username,
                    username + "@example.test",
                    tenantId)
                .fetchOne(0, Number.class),
            "Account insertion did not return an identifier");
    long accountId = inserted.longValue();
    if (accountId <= 0L) {
      throw new IllegalStateException("Account insertion returned a non-positive identifier");
    }
    dsl.execute(
        "INSERT INTO account_tenant_membership "
            + "(account_id, tenant_id, gameplay_admission_allowed) VALUES (?, ?, TRUE)",
        accountId,
        tenantId);
    dsl.execute("INSERT INTO profiles (account_id, tenant_id) VALUES (?, ?)", accountId, tenantId);
    return accountId;
  }

  private static int requirePositiveBackendPid(DSLContext dsl) {
    Integer backendPid =
        Objects.requireNonNull(
            dsl.resultQuery("SELECT pg_backend_pid()").fetchOne(0, Integer.class),
            "PostgreSQL did not return a backend PID");
    if (backendPid <= 0) {
      throw new IllegalStateException("PostgreSQL returned a non-positive backend PID");
    }
    return backendPid;
  }

  private static Snapshot sourceSnapshot(
      PreparedAccount prepared, AccountConnectTokenIssuanceIdentity identity) {
    String scopeHash = AccountJoinDigest.tokenHash(identity.connectScopeId());
    Record row =
        prepared
            .db()
            .dsl()
            .resultQuery(
                "SELECT operation_id, status, updated_at::text, "
                    + "(SELECT count(*) FROM account_connect_token_response_envelopes e "
                    + "WHERE e.operation_id = o.operation_id) "
                    + "FROM account_connect_token_issuance_operations o "
                    + "WHERE account_id = ? AND tenant_id = ? AND connect_scope_hash = ? AND request_id = ?",
                identity.accountId(),
                identity.tenantId(),
                scopeHash,
                identity.requestId())
            .fetchOne();
    return row == null
        ? new Snapshot(null, null, null, 0L)
        : new Snapshot(
            row.get(0, UUID.class),
            row.get(1, String.class),
            row.get(2, String.class),
            row.get(3, Long.class));
  }

  private static boolean awaitAccountFenceBlock(
      DSLContext dsl, int readerBackendPid, int blockerBackendPid, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      Boolean blocked =
          dsl.resultQuery(
                  "SELECT EXISTS (SELECT 1 FROM pg_stat_activity waiting "
                      + "WHERE waiting.pid = ? "
                      + "AND waiting.wait_event_type = 'Lock' "
                      + "AND waiting.query ILIKE '%for update%' "
                      + "AND ? = ANY(pg_blocking_pids(waiting.pid)))",
                  readerBackendPid, blockerBackendPid)
              .fetchOne(0, Boolean.class);
      if (Boolean.TRUE.equals(blocked)) {
        return true;
      }
      Thread.sleep(10L);
    }
    return false;
  }

  private static boolean await(CountDownLatch latch, long timeout, TimeUnit unit) {
    try {
      return latch.await(timeout, unit);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static void assertReadRejected(
      PreparedAccount prepared,
      SourceFixture source,
      Class<? extends Throwable> exceptionType,
      String messageFragment) {
    assertReadRejected(prepared.reader(), prepared, source, exceptionType, messageFragment);
  }

  private static void assertReadRejected(
      AccountCommittedConnectSourceReader reader,
      PreparedAccount prepared,
      SourceFixture source,
      Class<? extends Throwable> exceptionType,
      String messageFragment) {
    assertThatThrownBy(
            () ->
                withPeer(
                    peer(WORKLOAD_NAMESPACE, "game-session-service"),
                    () ->
                        inTransaction(
                            prepared.db().transaction(),
                            () -> reader.read(source.identity(), source.gatewayEnvelope()))))
        .isInstanceOf(exceptionType)
        .hasMessageContaining(messageFragment);
  }

  private static <T> T withPeer(GrpcPeerIdentity peer, Supplier<T> action) {
    Context scoped = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = scoped.attach();
    try {
      return action.get();
    } finally {
      scoped.detach(previous);
    }
  }

  private static <T> T withoutPeer(Supplier<T> action) {
    Context previous = Context.ROOT.attach();
    try {
      return action.get();
    } finally {
      Context.ROOT.detach(previous);
    }
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + service)
        .orElseThrow();
  }

  private static byte[] digest(String value) {
    return sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (Exception exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static <T> T inTransaction(TransactionTemplate transaction, Supplier<T> work) {
    return transaction.execute(status -> work.get());
  }

  private enum SourceState {
    ABSENT,
    PENDING,
    COMMITTED,
    FAILED
  }

  private record SourceOptions(
      boolean unknownSourceKey,
      boolean wrongHash,
      boolean wrongJti,
      boolean wrongTarget,
      boolean unknownGatewayKey,
      boolean shortGatewayDeadline,
      boolean unmappedTenant) {
    private static final SourceOptions NORMAL =
        new SourceOptions(false, false, false, false, false, false, false);
  }

  private record TestContext(
      DSLContext dsl,
      TransactionTemplate transaction,
      AccountConnectTokenIssuanceRepository issuanceRepository,
      AccountEnvelopeCrypto envelopeCrypto) {}

  private record PreparedAccount(
      TestContext db,
      long accountId,
      long tenantId,
      UUID accountUuid,
      UUID tenantUuid,
      KeyPair accountKeyPair,
      KeyPair unknownAccountKeyPair,
      KeyPair gatewayKeyPair,
      KeyPair unknownGatewayKeyPair,
      AccountGameplayConnectSourceVerifier verifier,
      Map<String, java.security.PublicKey> gatewayKeys,
      AccountRepository accountRepository,
      AccountTenantIdentityResolver tenantResolver,
      AccountJoinOperationRepository joinRepository,
      AccountCommittedConnectSourceReader reader,
      TestClock clock) {}

  private record SourceFixture(
      AccountConnectTokenIssuanceIdentity identity,
      String gatewayEnvelope,
      Map<String, Object> sourceClaims,
      Map<String, Object> gatewayClaims,
      String tokenIdentity) {}

  private record Snapshot(UUID operationId, String status, String updatedAt, long envelopeCount) {}

  private static final class TestClock extends Clock {
    private volatile Instant now;
    private final ZoneId zone;

    private TestClock(Instant initial) {
      this(initial, ZoneOffset.UTC);
    }

    private TestClock(Instant initial, ZoneId zone) {
      now = initial;
      this.zone = zone;
    }

    private void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return new TestClock(now, zone);
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
