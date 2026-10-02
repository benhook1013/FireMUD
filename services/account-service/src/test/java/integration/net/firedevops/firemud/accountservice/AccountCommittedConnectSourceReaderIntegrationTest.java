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
import net.firedevops.firemud.accountservice.repository.AccountBareLoginExchangeIdentity;
import net.firedevops.firemud.accountservice.repository.AccountBareLoginExchangeOperation;
import net.firedevops.firemud.accountservice.repository.AccountBareLoginExchangeRepository;
import net.firedevops.firemud.accountservice.repository.AccountBareLoginResponseEnvelope;
import net.firedevops.firemud.accountservice.repository.AccountCommittedConnectSource;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceIdentity;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceOperation.Lifecycle;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.repository.LegacyTenantSourceEvidence;
import net.firedevops.firemud.accountservice.security.AccountEncryptedEnvelope;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeBinding;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCrypto;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCryptoException;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCryptoException.Failure;
import net.firedevops.firemud.accountservice.security.AccountEnvelopePurpose;
import net.firedevops.firemud.accountservice.security.AccountGameplayConnectSourceVerifier;
import net.firedevops.firemud.accountservice.security.BareLoginRecoveryPayload;
import net.firedevops.firemud.accountservice.service.AccountCommittedConnectSourceReader;
import net.firedevops.firemud.accountservice.service.AccountCommittedConnectSourceReader.HistoricalCommittedSourceEvidence;
import net.firedevops.firemud.accountservice.service.AccountCommittedConnectSourceReader.OriginalSourceEvidence;
import net.firedevops.firemud.accountservice.service.AccountStoredBareLoginRecoveryReader;
import net.firedevops.firemud.accountservice.service.AccountStoredBareLoginRecoveryReader.HistoricalStoredBareLoginCorrelationEvidence;
import net.firedevops.firemud.accountservice.service.AccountStoredBareLoginRecoveryReader.HistoricalStoredBareLoginEvidence;
import net.firedevops.firemud.accountservice.service.AccountStoredBareLoginRecoveryReader.SelectedTargetEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.GatewayConnectContextCodec;
import net.firedevops.firemud.common.security.GatewayConnectContextSignature;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
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
  void readsFreshUuidCommittedSourceAndStoredHistoricalFrameWithoutMutation() throws Exception {
    PreparedAccount prepared = newPreparedAccount();
    SourceFixture source = newFreshSource(prepared, SourceTarget.NORMAL);
    assertThat(source.identity().tenantId()).isEqualTo(prepared.freshTenantUuid());
    FreshTenantCreationEvidence imported =
        inTransaction(
            prepared.db().transaction(),
            () ->
                prepared
                    .freshTenantIdentityRepository()
                    .read(prepared.freshTenantUuid())
                    .orElseThrow());
    assertThat(imported.canonicalTenantId()).isEqualTo(source.identity().tenantId());
    assertThat(imported.targetNamespace()).isEqualTo(WORKLOAD_NAMESPACE);
    assertThat(
            prepared
                .db()
                .dsl()
                .resultQuery(
                    "SELECT source_account_legacy_tenant_id "
                        + "FROM account_canonical_tenant_identity_claims "
                        + "WHERE canonical_tenant_id = ? AND identity_kind = 'FRESH_GAME_DESIGN'",
                    imported.canonicalTenantId())
                .fetchOne(0, Long.class))
        .isNull();

    Snapshot sourceBefore = sourceSnapshot(prepared, source.identity());
    OriginalSourceEvidence current =
        withPeer(
            peer(WORKLOAD_NAMESPACE, "game-session-service"),
            () ->
                inTransaction(
                    prepared.db().transaction(),
                    () -> prepared.reader().read(source.identity(), source.gatewayEnvelope())));
    assertThat(current.originalSourceClaims().get("tenantId"))
        .isEqualTo(prepared.freshTenantUuid().toString());

    byte[] originalResult = "fresh-tenant-opaque-result".getBytes(StandardCharsets.UTF_8);
    StoredBareLoginFixture stored =
        storeBareLoginFrame(prepared, source, source.gatewayEnvelope(), originalResult, null);
    BareLoginSnapshot exchangeBefore = bareLoginSnapshot(prepared, stored.operationId());
    prepared.clock().advance(Duration.ofSeconds(21));
    HistoricalStoredBareLoginEvidence historical =
        withPeer(
            peer(WORKLOAD_NAMESPACE, "game-session-service"),
            () ->
                inTransaction(
                    prepared.db().transaction(),
                    () ->
                        storedReader(prepared)
                            .readHistorical(stored.identity(), stored.requestDigest())));

    assertThat(historical.tenantId()).isEqualTo(prepared.freshTenantUuid());
    assertThat(historical.sourceConnectOperationId())
        .isEqualTo(stored.identity().sourceConnectOperationId());
    assertThat(historical.originalResultHash()).containsExactly(sha256(originalResult));
    assertThat(sourceSnapshot(prepared, source.identity())).isEqualTo(sourceBefore);
    assertThat(bareLoginSnapshot(prepared, stored.operationId())).isEqualTo(exchangeBefore);
  }

  @Test
  void readsExpiredCommittedSourceOnlyThroughHistoricalEvidenceWithoutMutation() throws Exception {
    PreparedAccount prepared = newPreparedAccount();
    SourceFixture source = newSource(prepared, SourceState.COMMITTED);
    AccountCommittedConnectSource committed =
        inTransaction(
            prepared.db().transaction(),
            () ->
                prepared
                    .db()
                    .issuanceRepository()
                    .readCommittedResponseEnvelope(source.identity())
                    .orElseThrow());
    Snapshot before = sourceSnapshot(prepared, source.identity());

    // Both the Account token and its projected Gateway context expire after twenty seconds.
    prepared.clock().advance(Duration.ofSeconds(21));
    HistoricalCommittedSourceEvidence historical =
        withPeer(
            peer(WORKLOAD_NAMESPACE, "game-session-service"),
            () ->
                inTransaction(
                    prepared.db().transaction(),
                    () ->
                        prepared
                            .reader()
                            .readHistorical(source.identity(), source.gatewayEnvelope())));

    assertThat(historical.operationId()).isEqualTo(committed.operation().operationId());
    assertThat(historical.responseEnvelopeKeyId())
        .isEqualTo(committed.responseEnvelope().envelope().keyId());
    assertThat(historical.sourceTokenHash()).containsExactly(committed.operation().tokenHash());
    assertThat(historical.accountSourceKeyId()).isEqualTo(ACCOUNT_KEY_ID);
    assertThat(historical.gatewayKeyId()).isEqualTo(GATEWAY_KEY_ID);
    assertThat(historical.originalSourceClaims()).isEqualTo(source.sourceClaims());
    assertThat(historical.historicalGatewayClaims()).isEqualTo(source.gatewayClaims());
    assertThat(historical.originalSourceClaims().get("jti")).isEqualTo(source.tokenIdentity());
    assertThat(historical.toString())
        .doesNotContain(source.identity().connectScopeId())
        .doesNotContain(source.tokenIdentity())
        .doesNotContain(source.gatewayEnvelope());
    byte[] modifiedHash = historical.sourceTokenHash();
    modifiedHash[0] ^= 0x01;
    assertThat(historical.sourceTokenHash()).containsExactly(committed.operation().tokenHash());
    assertThatThrownBy(() -> historical.originalSourceClaims().put("extra", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                ((Map<String, Object>) historical.originalSourceClaims().get("authorityTuple"))
                    .put("extra", BigInteger.ONE))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(sourceSnapshot(prepared, source.identity())).isEqualTo(before);

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
                                    .read(source.identity(), source.gatewayEnvelope()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Gateway context is expired");
    assertThat(sourceSnapshot(prepared, source.identity())).isEqualTo(before);
  }

  @Test
  void readsCommittedStoredFrameAndOriginalSourceAfterGatewayContextExpiryWithoutMutation()
      throws Exception {
    PreparedAccount prepared = newPreparedAccount();
    SourceFixture source = newSource(prepared, SourceState.COMMITTED);
    byte[] originalResult = "opaque-original-bare-login-result".getBytes(StandardCharsets.UTF_8);
    StoredBareLoginFixture stored =
        storeBareLoginFrame(prepared, source, source.gatewayEnvelope(), originalResult, null);
    AccountCommittedConnectSource committedSource =
        inTransaction(
            prepared.db().transaction(),
            () ->
                prepared
                    .db()
                    .issuanceRepository()
                    .readCommittedResponseEnvelope(source.identity())
                    .orElseThrow());
    Snapshot sourceBefore = sourceSnapshot(prepared, source.identity());
    BareLoginSnapshot exchangeBefore = bareLoginSnapshot(prepared, stored.operationId());
    AccountStoredBareLoginRecoveryReader reader = storedReader(prepared);

    prepared.clock().advance(Duration.ofSeconds(21));
    HistoricalStoredBareLoginEvidence first =
        withPeer(
            peer(WORKLOAD_NAMESPACE, "game-session-service"),
            () ->
                inTransaction(
                    prepared.db().transaction(),
                    () -> reader.readHistorical(stored.identity(), stored.requestDigest())));
    HistoricalStoredBareLoginEvidence reread =
        withPeer(
            peer(WORKLOAD_NAMESPACE, "game-session-service"),
            () ->
                inTransaction(
                    prepared.db().transaction(),
                    () -> reader.readHistorical(stored.identity(), stored.requestDigest())));

    assertThat(first.exchangeOperationId()).isEqualTo(stored.operationId());
    assertThat(first.sourceConnectOperationId())
        .isEqualTo(stored.identity().sourceConnectOperationId());
    assertThat(first.accountId()).isEqualTo(source.identity().accountId());
    assertThat(first.tenantId()).isEqualTo(source.identity().tenantId());
    assertThat(first.requestId()).isEqualTo(stored.identity().requestId());
    assertThat(first.requestDigestVersion()).isEqualTo(1);
    assertThat(first.connectScopeHash())
        .isEqualTo(AccountJoinDigest.tokenHash(source.identity().connectScopeId()));
    assertThat(first.requestDigest()).containsExactly(stored.requestDigest());
    assertThat(first.originalResultHash()).containsExactly(sha256(originalResult));
    assertThat(first.sourceConnectTokenHash())
        .containsExactly(committedSource.operation().tokenHash());
    assertThat(first.responseEnvelopeKeyId()).isEqualTo("test-key");
    assertThat(first.sourceResponseEnvelopeKeyId()).isEqualTo("test-key");
    assertThat(first.accountSourceKeyId()).isEqualTo(ACCOUNT_KEY_ID);
    assertThat(first.gatewayKeyId()).isEqualTo(GATEWAY_KEY_ID);
    assertThat(first.signedGatewayVerifiedAt()).isEqualTo(source.gatewayClaims().get("verifiedAt"));
    assertThat(first.signedGatewayExpiresAt()).isEqualTo(source.gatewayClaims().get("expiresAt"));
    assertThat(first.exchangeOperationId()).isEqualTo(reread.exchangeOperationId());
    assertThat(first.sourceConnectOperationId()).isEqualTo(reread.sourceConnectOperationId());
    assertThat(first.originalResultHash()).containsExactly(reread.originalResultHash());
    byte[] changedRequestDigest = first.requestDigest();
    changedRequestDigest[0] ^= 0x01;
    assertThat(first.requestDigest()).containsExactly(stored.requestDigest());
    byte[] changedSourceHash = first.sourceConnectTokenHash();
    changedSourceHash[0] ^= 0x01;
    assertThat(first.sourceConnectTokenHash())
        .containsExactly(committedSource.operation().tokenHash());
    assertThat(first.toString())
        .doesNotContain(new String(originalResult, StandardCharsets.UTF_8))
        .doesNotContain(source.gatewayEnvelope())
        .doesNotContain(source.identity().connectScopeId());
    byte[] changedHash = first.originalResultHash();
    changedHash[0] ^= 0x01;
    assertThat(first.originalResultHash()).containsExactly(sha256(originalResult));
    assertThat(sourceSnapshot(prepared, source.identity())).isEqualTo(sourceBefore);
    assertThat(bareLoginSnapshot(prepared, stored.operationId())).isEqualTo(exchangeBefore);
  }

  @Test
  void correlatesExpiredOriginalFrameWithSeparateFreshCommittedSourceWithoutMutation()
      throws Exception {
    PreparedAccount prepared = newPreparedAccount();
    SourceTarget target =
        new SourceTarget(
            "world",
            "realm",
            UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            BigInteger.valueOf(3));
    SourceFixture original = newSourceWithTarget(prepared, target);
    byte[] originalResult = "opaque-original-result".getBytes(StandardCharsets.UTF_8);
    StoredBareLoginFixture stored =
        storeBareLoginFrame(prepared, original, original.gatewayEnvelope(), originalResult, null);
    Snapshot originalSourceBefore = sourceSnapshot(prepared, original.identity());
    BareLoginSnapshot exchangeBefore = bareLoginSnapshot(prepared, stored.operationId());

    prepared.clock().advance(Duration.ofSeconds(21));
    long now = prepared.clock().instant().getEpochSecond();
    assertThat(((BigInteger) original.gatewayClaims().get("expiresAt")).longValue())
        .isLessThan(now);
    SourceFixture fresh = newSourceWithTarget(prepared, target);
    Snapshot freshSourceBefore = sourceSnapshot(prepared, fresh.identity());

    AccountStoredBareLoginRecoveryReader reader = storedReader(prepared);
    HistoricalStoredBareLoginCorrelationEvidence first =
        readCorrelation(reader, prepared, stored, fresh.identity(), fresh.gatewayEnvelope());
    HistoricalStoredBareLoginCorrelationEvidence reread =
        readCorrelation(reader, prepared, stored, fresh.identity(), fresh.gatewayEnvelope());

    assertThat(first.originalExchange().exchangeOperationId()).isEqualTo(stored.operationId());
    assertThat(first.originalExchange().requestId()).isEqualTo(stored.identity().requestId());
    assertThat(first.originalExchange().requestDigest()).containsExactly(stored.requestDigest());
    assertThat(first.originalExchange().connectScopeHash())
        .isEqualTo(AccountJoinDigest.tokenHash(stored.identity().connectScopeId()));
    assertThat(first.originalExchange().originalResultHash())
        .containsExactly(sha256(originalResult));
    assertThat(first.originalConnectSource().operationId())
        .isEqualTo(stored.identity().sourceConnectOperationId());
    assertThat(first.freshConnectSource().operationId()).isEqualTo(freshSourceBefore.operationId());
    assertThat(first.freshConnectSource().operationId())
        .isNotEqualTo(first.originalConnectSource().operationId());
    assertThat(first.originalConnectSource().requestId())
        .isEqualTo(original.identity().requestId());
    assertThat(first.freshConnectSource().requestId()).isEqualTo(fresh.identity().requestId());
    assertThat(first.originalConnectSource().requestId())
        .isNotEqualTo(first.freshConnectSource().requestId());
    assertThat(first.originalConnectSource().connectScopeHash())
        .isEqualTo(AccountJoinDigest.tokenHash(original.identity().connectScopeId()));
    assertThat(first.freshConnectSource().connectScopeHash())
        .isEqualTo(AccountJoinDigest.tokenHash(fresh.identity().connectScopeId()));
    assertThat(first.originalConnectSource().connectScopeHash())
        .isNotEqualTo(first.freshConnectSource().connectScopeHash());
    assertThat(first.originalConnectSource().tokenIdentity()).isEqualTo(original.tokenIdentity());
    assertThat(first.freshConnectSource().tokenIdentity()).isEqualTo(fresh.tokenIdentity());
    assertThat(first.originalConnectSource().sourceExpiresAt()).isLessThan(BigInteger.valueOf(now));
    assertThat(first.freshConnectSource().gatewayExpiresAt())
        .isGreaterThan(BigInteger.valueOf(now));
    assertThat(first.selectedTarget())
        .isEqualTo(
            new SelectedTargetEvidence(
                (String) original.sourceClaims().get("accountId"),
                (String) original.sourceClaims().get("tenantId"),
                (String) original.sourceClaims().get("realmId"),
                (String) original.sourceClaims().get("worldSlug"),
                (String) original.sourceClaims().get("realmSlug"),
                (String) original.sourceClaims().get("playableStateNamespaceId"),
                (String) original.sourceClaims().get("playableStateScope"),
                (String) original.sourceClaims().get("gameInstanceId"),
                (BigInteger) original.sourceClaims().get("catalogRevision"),
                (BigInteger) original.sourceClaims().get("pointerVersion"),
                (String) original.sourceClaims().get("playtestLifecycleId"),
                (BigInteger) original.sourceClaims().get("playtestStateGeneration")));
    assertThat(first.selectedTarget()).isEqualTo(reread.selectedTarget());
    assertThat(first.originalConnectSource().sourceTokenHash())
        .containsExactly(reread.originalConnectSource().sourceTokenHash());
    byte[] mutatedHash = first.freshConnectSource().sourceTokenHash();
    mutatedHash[0] ^= 0x01;
    assertThat(first.freshConnectSource().sourceTokenHash())
        .containsExactly(reread.freshConnectSource().sourceTokenHash());
    assertThat(first.toString())
        .doesNotContain(new String(originalResult, StandardCharsets.UTF_8))
        .doesNotContain(original.gatewayEnvelope())
        .doesNotContain(fresh.gatewayEnvelope());
    assertThat(sourceSnapshot(prepared, original.identity())).isEqualTo(originalSourceBefore);
    assertThat(sourceSnapshot(prepared, fresh.identity())).isEqualTo(freshSourceBefore);
    assertThat(bareLoginSnapshot(prepared, stored.operationId())).isEqualTo(exchangeBefore);

    assertReadRejected(
        prepared.reader(),
        prepared,
        original,
        IllegalArgumentException.class,
        "Gateway context is expired");
  }

  @Test
  void correlationRejectsExpiredFreshContextAndReissuedContextForOriginalSource() throws Exception {
    PreparedAccount expiredPrepared = newPreparedAccount();
    SourceFixture expiredOriginal = newSource(expiredPrepared, SourceState.COMMITTED);
    StoredBareLoginFixture expiredStored =
        storeBareLoginFrame(
            expiredPrepared,
            expiredOriginal,
            expiredOriginal.gatewayEnvelope(),
            "opaque-result".getBytes(StandardCharsets.UTF_8),
            null);
    SourceFixture expiredFresh = newSource(expiredPrepared, SourceState.COMMITTED);
    expiredPrepared.clock().advance(Duration.ofSeconds(21));
    assertCorrelationReadRejected(
        expiredPrepared,
        storedReader(expiredPrepared),
        expiredStored,
        expiredFresh.identity(),
        expiredFresh.gatewayEnvelope(),
        IllegalArgumentException.class,
        "Gateway context is expired");

    PreparedAccount samePrepared = newPreparedAccount();
    SourceFixture sameSource =
        newSource(
            samePrepared,
            SourceState.COMMITTED,
            new SourceOptions(false, false, false, false, false, true, false));
    StoredBareLoginFixture sameStored =
        storeBareLoginFrame(
            samePrepared,
            sameSource,
            sameSource.gatewayEnvelope(),
            "opaque-result".getBytes(StandardCharsets.UTF_8),
            null);
    samePrepared.clock().advance(Duration.ofSeconds(3));
    String currentContextForSameSource = resignCurrentGatewayContext(samePrepared, sameSource);
    assertCorrelationReadRejected(
        samePrepared,
        storedReader(samePrepared),
        sameStored,
        sameSource.identity(),
        currentContextForSameSource,
        IllegalStateException.class,
        "separate committed Account source operation");
  }

  @Test
  void correlationRequiresExactAccountTenantAndCompleteSelectedTarget() throws Exception {
    PreparedAccount prepared = newPreparedAccount();
    SourceTarget originalTarget =
        new SourceTarget(
            "world",
            "realm",
            UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            BigInteger.valueOf(3));
    SourceFixture original = newSourceWithTarget(prepared, originalTarget);
    StoredBareLoginFixture stored =
        storeBareLoginFrame(
            prepared,
            original,
            original.gatewayEnvelope(),
            "opaque-result".getBytes(StandardCharsets.UTF_8),
            null);
    AccountStoredBareLoginRecoveryReader reader = storedReader(prepared);

    SourceFixture freshMatchingTarget = newSourceWithTarget(prepared, originalTarget);
    byte[] changedDigest = stored.requestDigest();
    changedDigest[0] ^= 0x01;
    assertCorrelationReadRejected(
        prepared,
        reader,
        stored,
        changedDigest,
        freshMatchingTarget.identity(),
        freshMatchingTarget.gatewayEnvelope(),
        AccountBareLoginExchangeRepository.IdempotencyConflictException.class,
        "different digest");

    AccountBareLoginExchangeIdentity changedRequest =
        new AccountBareLoginExchangeIdentity(
            stored.identity().sourceConnectOperationId(),
            stored.identity().accountId(),
            stored.identity().tenantId(),
            stored.identity().connectScopeId(),
            stored.identity().requestId() + "-different");
    assertCorrelationReadRejected(
        prepared,
        reader,
        stored,
        changedRequest,
        stored.requestDigest(),
        freshMatchingTarget.identity(),
        freshMatchingTarget.gatewayEnvelope(),
        IllegalStateException.class,
        "exchange identity was reused with different source or binding");

    SourceFixture changedWorld =
        newSourceWithTarget(prepared, new SourceTarget("other-world", "realm", null, null));
    assertCorrelationReadRejected(
        prepared,
        reader,
        stored,
        stored.requestDigest(),
        changedWorld.identity(),
        changedWorld.gatewayEnvelope(),
        IllegalStateException.class,
        "exact original Account target");

    AccountConnectTokenIssuanceIdentity wrongPlayer =
        new AccountConnectTokenIssuanceIdentity(
            original.identity().accountId() + 1,
            original.identity().tenantId(),
            "fresh-scope-player",
            "fresh-request-player");
    assertCorrelationIdentityRejected(prepared, reader, stored, wrongPlayer);
    AccountConnectTokenIssuanceIdentity wrongTenant =
        new AccountConnectTokenIssuanceIdentity(
            original.identity().accountId(),
            UUID.randomUUID(),
            "fresh-scope-tenant",
            "fresh-request-tenant");
    assertCorrelationIdentityRejected(prepared, reader, stored, wrongTenant);

    SourceFixture changedLifecycle =
        newSourceWithTarget(
            prepared,
            new SourceTarget(
                "world",
                "realm",
                UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff"),
                BigInteger.valueOf(3)));
    assertCorrelationReadRejected(
        prepared,
        reader,
        stored,
        changedLifecycle.identity(),
        changedLifecycle.gatewayEnvelope(),
        IllegalStateException.class,
        "exact original Account target");

    SourceFixture changedGeneration =
        newSourceWithTarget(
            prepared,
            new SourceTarget(
                "world", "realm", originalTarget.playtestLifecycleId(), BigInteger.valueOf(4)));
    assertCorrelationReadRejected(
        prepared,
        reader,
        stored,
        changedGeneration.identity(),
        changedGeneration.gatewayEnvelope(),
        IllegalStateException.class,
        "exact original Account target");
  }

  @Test
  void storedHistoryRejectsChangedDigestSourceContextResultHashAndKey() throws Exception {
    PreparedAccount prepared = newPreparedAccount();
    SourceFixture source = newSource(prepared, SourceState.COMMITTED);
    byte[] result = "opaque-original-result".getBytes(StandardCharsets.UTF_8);
    StoredBareLoginFixture stored =
        storeBareLoginFrame(prepared, source, source.gatewayEnvelope(), result, null);
    byte[] wrongRequestDigest = stored.requestDigest();
    wrongRequestDigest[0] ^= 0x01;
    assertStoredReadRejected(
        storedReader(prepared),
        prepared,
        stored,
        wrongRequestDigest,
        AccountBareLoginExchangeRepository.IdempotencyConflictException.class,
        "different digest");

    SourceFixture linkedSource = newSource(prepared, SourceState.COMMITTED);
    SourceFixture sameScopeOtherSource =
        newSource(
            prepared,
            SourceState.COMMITTED,
            SourceOptions.NORMAL,
            linkedSource.identity().connectScopeId());
    Snapshot contextSourceBefore = sourceSnapshot(prepared, sameScopeOtherSource.identity());
    StoredBareLoginFixture linkedToWrongSource =
        storeBareLoginFrame(
            prepared, linkedSource, sameScopeOtherSource.gatewayEnvelope(), result, null);
    assertStoredReadRejected(
        storedReader(prepared),
        prepared,
        linkedToWrongSource,
        linkedToWrongSource.requestDigest(),
        IllegalStateException.class,
        "does not match its original Gateway context");
    assertThat(sourceSnapshot(prepared, sameScopeOtherSource.identity()))
        .isEqualTo(contextSourceBefore);

    SourceFixture changedContextSource =
        newSource(
            prepared,
            SourceState.COMMITTED,
            new SourceOptions(false, false, false, true, false, false, false));
    StoredBareLoginFixture changedContext =
        storeBareLoginFrame(
            prepared, changedContextSource, changedContextSource.gatewayEnvelope(), result, null);
    assertStoredReadRejected(
        storedReader(prepared),
        prepared,
        changedContext,
        changedContext.requestDigest(),
        IllegalArgumentException.class,
        "invalid Account gameplay-connect source JWT");

    byte[] differentStoredHash = sha256("different-result".getBytes(StandardCharsets.UTF_8));
    SourceFixture resultHashSource = newSource(prepared, SourceState.COMMITTED);
    StoredBareLoginFixture changedResultHash =
        storeBareLoginFrame(
            prepared,
            resultHashSource,
            resultHashSource.gatewayEnvelope(),
            result,
            differentStoredHash);
    assertStoredReadRejected(
        storedReader(prepared),
        prepared,
        changedResultHash,
        changedResultHash.requestDigest(),
        IllegalStateException.class,
        "result hash does not match");

    SourceFixture oldPayloadSource = newSource(prepared, SourceState.COMMITTED);
    StoredBareLoginFixture oldOpaquePayload =
        storeOpaqueBareLoginPayload(
            prepared,
            oldPayloadSource,
            "legacy unframed response payload".getBytes(StandardCharsets.UTF_8));
    assertStoredReadRejected(
        storedReader(prepared),
        prepared,
        oldOpaquePayload,
        oldOpaquePayload.requestDigest(),
        IllegalArgumentException.class,
        "payload header is invalid");

    assertStoredReadRejected(
        storedReader(prepared, differentConnectPurposeKey()),
        prepared,
        stored,
        stored.requestDigest(),
        AccountEnvelopeCryptoException.class,
        "AUTHENTICATION_FAILED");
  }

  @Test
  void historicalReadRejectsRequestTargetHashAndTokenIdentityMismatches() throws Exception {
    PreparedAccount prepared = newPreparedAccount();
    SourceFixture source = newSource(prepared, SourceState.COMMITTED);
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

    assertHistoricalReadRejected(prepared, wrongHash, IllegalStateException.class, "token hash");
    assertHistoricalReadRejected(prepared, wrongJti, IllegalStateException.class, "token identity");
    // Source verification deliberately sanitizes signature/profile/projection failure details.
    assertHistoricalReadRejected(
        prepared,
        wrongTarget,
        IllegalArgumentException.class,
        "invalid Account gameplay-connect source JWT");

    Snapshot before = sourceSnapshot(prepared, source.identity());
    AccountConnectTokenIssuanceIdentity wrongRequestIdentity =
        new AccountConnectTokenIssuanceIdentity(
            source.identity().accountId(),
            source.identity().tenantId(),
            source.identity().connectScopeId() + "-different",
            source.identity().requestId());
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
                                    .readHistorical(
                                        wrongRequestIdentity, source.gatewayEnvelope()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("request identity");
    assertThat(sourceSnapshot(prepared, source.identity())).isEqualTo(before);
  }

  @Test
  void historicalReadRequiresCommittedSuccess() throws Exception {
    PreparedAccount prepared = newPreparedAccount();
    SourceFixture absent = newSource(prepared, SourceState.ABSENT);
    SourceFixture pending = newSource(prepared, SourceState.PENDING);
    SourceFixture failed = newSource(prepared, SourceState.FAILED);

    assertHistoricalReadRejected(
        prepared, absent, IllegalStateException.class, "Committed Account source is absent");
    assertHistoricalReadRejected(
        prepared, pending, IllegalStateException.class, "not a complete committed success");
    assertHistoricalReadRejected(
        prepared, failed, IllegalStateException.class, "not a complete committed success");
  }

  @Test
  void historicalReadRequiresRegisteredGatewayAndAccountSigningKeys() throws Exception {
    PreparedAccount prepared = newPreparedAccount();
    SourceFixture unknownGateway =
        newSource(
            prepared,
            SourceState.COMMITTED,
            new SourceOptions(false, false, false, false, true, false, false));
    SourceFixture unknownAccount =
        newSource(
            prepared,
            SourceState.COMMITTED,
            new SourceOptions(true, false, false, false, false, false, false));

    assertHistoricalReadRejected(
        prepared,
        unknownGateway,
        IllegalArgumentException.class,
        "unknown Gateway verification key");
    assertHistoricalReadRejected(
        prepared,
        unknownAccount,
        IllegalArgumentException.class,
        "invalid Account gameplay-connect source JWT");
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
    assertHistoricalReadRejected(
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
    assertHistoricalReadRejected(
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
    assertThat(stored.responseEnvelope().binding().tenantId())
        .isEqualTo(source.identity().tenantId().toString());
    AccountEnvelopeBinding wrongConnectTenantBinding =
        withTenantId(stored.responseEnvelope().binding(), UUID.randomUUID().toString());
    assertThatThrownBy(
            () ->
                prepared
                    .db()
                    .envelopeCrypto()
                    .decrypt(
                        stored.responseEnvelope().envelope(),
                        AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE,
                        wrongConnectTenantBinding))
        .isInstanceOf(AccountEnvelopeCryptoException.class)
        .extracting(exception -> ((AccountEnvelopeCryptoException) exception).failure())
        .isEqualTo(Failure.AUTHENTICATION_FAILED);

    StoredBareLoginFixture storedBareLogin =
        storeBareLoginFrame(
            prepared,
            source,
            source.gatewayEnvelope(),
            "uuid-bound-bare-login-frame".getBytes(StandardCharsets.UTF_8),
            null);
    AccountBareLoginResponseEnvelope bareEnvelope =
        inTransaction(
            prepared.db().transaction(),
            () -> {
              AccountBareLoginExchangeOperation operation =
                  prepared
                      .db()
                      .bareLoginExchangeRepository()
                      .find(storedBareLogin.identity(), storedBareLogin.requestDigest())
                      .orElseThrow();
              AccountEnvelopeBinding binding =
                  bareLoginBinding(operation, storedBareLogin.identity());
              return prepared
                  .db()
                  .bareLoginExchangeRepository()
                  .readResponseEnvelope(
                      storedBareLogin.identity(), storedBareLogin.requestDigest(), binding)
                  .orElseThrow();
            });
    assertThat(bareEnvelope.binding().tenantId())
        .isEqualTo(source.identity().tenantId().toString());
    AccountEnvelopeBinding wrongBareLoginTenantBinding =
        withTenantId(bareEnvelope.binding(), UUID.randomUUID().toString());
    assertThatThrownBy(
            () ->
                prepared
                    .db()
                    .envelopeCrypto()
                    .decrypt(
                        bareEnvelope.envelope(),
                        AccountEnvelopePurpose.BARE_LOGIN_RESPONSE,
                        wrongBareLoginTenantBinding))
        .isInstanceOf(AccountEnvelopeCryptoException.class)
        .extracting(exception -> ((AccountEnvelopeCryptoException) exception).failure())
        .isEqualTo(Failure.AUTHENTICATION_FAILED);

    AccountEnvelopeBinding otherPurposeBinding =
        new AccountEnvelopeBinding(
            AccountEnvelopeBinding.OperationKind.BARE_LOGIN_EXCHANGE,
            UUID.randomUUID().toString(),
            "other-request",
            Long.toString(source.identity().accountId()),
            source.identity().tenantId().toString(),
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
    assertHistoricalReadRejected(
        wrongKeyReader,
        prepared,
        source,
        AccountEnvelopeCryptoException.class,
        "AUTHENTICATION_FAILED");

    AccountCommittedConnectSourceReader missingRingReader =
        readerWithCrypto(prepared, missingKeyRingCrypto());
    assertHistoricalReadRejected(
        missingRingReader,
        prepared,
        source,
        AccountEnvelopeCryptoException.class,
        "KEY_RING_UNAVAILABLE");
  }

  @Test
  void doesNotReturnWhenAccountFenceWaitOutlivesTheUnchangedGatewayDeadline() throws Exception {
    PreparedAccount prepared = newPreparedAccount();
    SourceFixture original = newSource(prepared, SourceState.COMMITTED);
    StoredBareLoginFixture stored =
        storeBareLoginFrame(
            prepared,
            original,
            original.gatewayEnvelope(),
            "opaque-result".getBytes(StandardCharsets.UTF_8),
            null);
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
      Future<HistoricalStoredBareLoginCorrelationEvidence> reader =
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
                                return storedReader(prepared)
                                    .readCorrelation(
                                        stored.identity(),
                                        stored.requestDigest(),
                                        source.identity(),
                                        source.gatewayEnvelope());
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

  @Test
  void correlationRechecksFreshDeadlineAfterStoredFrameReadWait() throws Exception {
    PreparedAccount prepared = newPreparedAccount();
    SourceFixture original = newSource(prepared, SourceState.COMMITTED);
    StoredBareLoginFixture stored =
        storeBareLoginFrame(
            prepared,
            original,
            original.gatewayEnvelope(),
            "opaque-result".getBytes(StandardCharsets.UTF_8),
            null);
    SourceFixture fresh =
        newSource(
            prepared,
            SourceState.COMMITTED,
            new SourceOptions(false, false, false, false, false, true, false));
    Snapshot originalBefore = sourceSnapshot(prepared, original.identity());
    Snapshot freshBefore = sourceSnapshot(prepared, fresh.identity());
    BareLoginSnapshot exchangeBefore = bareLoginSnapshot(prepared, stored.operationId());

    CountDownLatch envelopeTableLocked = new CountDownLatch(1);
    CountDownLatch releaseEnvelopeTable = new CountDownLatch(1);
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
                          .execute(
                              "LOCK TABLE account_bare_login_response_envelopes "
                                  + "IN ACCESS EXCLUSIVE MODE");
                      envelopeTableLocked.countDown();
                      if (!await(releaseEnvelopeTable, 20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                            "V37 envelope-table blocker was not released");
                      }
                      return null;
                    }));
    try {
      assertThat(envelopeTableLocked.await(5, TimeUnit.SECONDS)).isTrue();
      Future<HistoricalStoredBareLoginCorrelationEvidence> reader =
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
                                return storedReader(prepared)
                                    .readCorrelation(
                                        stored.identity(),
                                        stored.requestDigest(),
                                        fresh.identity(),
                                        fresh.gatewayEnvelope());
                              })));
      assertThat(readerBackendIdentified.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(
              awaitRelationLockBlock(
                  prepared.db().dsl(),
                  readerBackendPid.get(),
                  blockerBackendPid.get(),
                  Duration.ofSeconds(5)))
          .as(
              "correlation passed its first strict source read and reached stored-envelope readback")
          .isTrue();

      prepared.clock().advance(Duration.ofSeconds(3));
      releaseEnvelopeTable.countDown();
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
      assertThat(sourceSnapshot(prepared, original.identity())).isEqualTo(originalBefore);
      assertThat(sourceSnapshot(prepared, fresh.identity())).isEqualTo(freshBefore);
      assertThat(bareLoginSnapshot(prepared, stored.operationId())).isEqualTo(exchangeBefore);
    } finally {
      releaseEnvelopeTable.countDown();
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
    UUID freshTenantUuid = UUID.randomUUID();
    FreshTenantIdentityAssociationRepository freshTenantIdentityRepository =
        new FreshTenantIdentityAssociationRepository(dsl, WORKLOAD_NAMESPACE);
    FreshTenantCreationEvidence freshTenantEvidence = freshTenantEvidence(freshTenantUuid);
    inTransaction(
        transaction, () -> freshTenantIdentityRepository.importVerified(freshTenantEvidence));

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
    AccountBareLoginExchangeRepository bareLoginExchangeRepository =
        new AccountBareLoginExchangeRepository(dsl);
    AccountRepository accountRepository = new AccountRepository(dsl);
    AccountTenantIdentityResolver tenantResolver =
        new AccountTenantIdentityResolver(associations, sourceEvidence, WORKLOAD_NAMESPACE);
    AccountConnectScopeRepository connectScopeRepository =
        new AccountConnectScopeRepository(
            dsl, accountRepository, tenantResolver, freshTenantIdentityRepository);
    AccountJoinOperationRepository joinRepository =
        new AccountJoinOperationRepository(dsl, connectScopeRepository);
    AccountCommittedConnectSourceReader reader =
        new AccountCommittedConnectSourceReader(
            issuanceRepository,
            accountRepository,
            tenantResolver,
            freshTenantIdentityRepository,
            joinRepository,
            envelopeCrypto,
            verifier,
            gatewayKeys,
            clock,
            WORKLOAD_NAMESPACE);
    TestContext db =
        new TestContext(
            dsl, transaction, issuanceRepository, bareLoginExchangeRepository, envelopeCrypto);
    return new PreparedAccount(
        db,
        accountId,
        LEGACY_TENANT_ID,
        accountUuid,
        tenantUuid,
        freshTenantUuid,
        accountKeyPair,
        unknownAccountKeyPair,
        gatewayKeyPair,
        unknownGatewayKeyPair,
        verifier,
        gatewayKeys,
        accountRepository,
        tenantResolver,
        freshTenantIdentityRepository,
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
    return newSource(prepared, state, options, requestId, connectScopeId);
  }

  private SourceFixture newSource(
      PreparedAccount prepared, SourceState state, SourceOptions options, String connectScopeId)
      throws Exception {
    String requestId = "source-request-" + UUID.randomUUID();
    return newSource(prepared, state, options, requestId, connectScopeId);
  }

  private SourceFixture newSource(
      PreparedAccount prepared,
      SourceState state,
      SourceOptions options,
      String requestId,
      String connectScopeId)
      throws Exception {
    return newSource(prepared, state, options, requestId, connectScopeId, SourceTarget.NORMAL);
  }

  private SourceFixture newSourceWithTarget(PreparedAccount prepared, SourceTarget target)
      throws Exception {
    return newSource(
        prepared,
        SourceState.COMMITTED,
        SourceOptions.NORMAL,
        "source-request-" + UUID.randomUUID(),
        "source-scope-" + UUID.randomUUID(),
        target);
  }

  private SourceFixture newFreshSource(PreparedAccount prepared, SourceTarget target)
      throws Exception {
    return newSource(
        prepared,
        SourceState.COMMITTED,
        SourceOptions.NORMAL,
        "source-request-" + UUID.randomUUID(),
        "source-scope-" + UUID.randomUUID(),
        target,
        prepared.freshTenantUuid());
  }

  private SourceFixture newSource(
      PreparedAccount prepared,
      SourceState state,
      SourceOptions options,
      String requestId,
      String connectScopeId,
      SourceTarget target)
      throws Exception {
    UUID sourceTenantUuid = options.unmappedTenant() ? UUID.randomUUID() : prepared.tenantUuid();
    return newSource(prepared, state, options, requestId, connectScopeId, target, sourceTenantUuid);
  }

  private SourceFixture newSource(
      PreparedAccount prepared,
      SourceState state,
      SourceOptions options,
      String requestId,
      String connectScopeId,
      SourceTarget target,
      UUID sourceTenantUuid)
      throws Exception {
    AccountConnectTokenIssuanceIdentity identity =
        new AccountConnectTokenIssuanceIdentity(
            prepared.accountId(), sourceTenantUuid, connectScopeId, requestId);
    String tokenIdentity = "source-jti-" + UUID.randomUUID();
    Map<String, Object> sourceClaims =
        sourceClaims(
            prepared.accountUuid(),
            sourceTenantUuid,
            identity,
            tokenIdentity,
            prepared.clock(),
            target);
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
                    identity.tenantId().toString(),
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

  private StoredBareLoginFixture storeBareLoginFrame(
      PreparedAccount prepared,
      SourceFixture source,
      String originalGatewayContext,
      byte[] originalResult,
      byte[] operationResultHash)
      throws Exception {
    byte[] encodedFrame =
        BareLoginRecoveryPayload.of(originalGatewayContext, originalResult).encode();
    byte[] storedResultHash =
        operationResultHash == null ? sha256(originalResult) : operationResultHash.clone();
    try {
      return storeBareLoginResponse(prepared, source, encodedFrame, storedResultHash);
    } finally {
      java.util.Arrays.fill(encodedFrame, (byte) 0);
      java.util.Arrays.fill(storedResultHash, (byte) 0);
    }
  }

  private StoredBareLoginFixture storeOpaqueBareLoginPayload(
      PreparedAccount prepared, SourceFixture source, byte[] opaquePayload) throws Exception {
    byte[] resultHash = sha256(opaquePayload);
    try {
      return storeBareLoginResponse(prepared, source, opaquePayload, resultHash);
    } finally {
      java.util.Arrays.fill(resultHash, (byte) 0);
    }
  }

  private StoredBareLoginFixture storeBareLoginResponse(
      PreparedAccount prepared,
      SourceFixture source,
      byte[] encryptedPlaintext,
      byte[] storedResultHash)
      throws Exception {
    AccountCommittedConnectSource committedSource =
        inTransaction(
            prepared.db().transaction(),
            () ->
                prepared
                    .db()
                    .issuanceRepository()
                    .readCommittedResponseEnvelope(source.identity())
                    .orElseThrow());
    AccountBareLoginExchangeIdentity identity =
        new AccountBareLoginExchangeIdentity(
            committedSource.operation().operationId(),
            source.identity().accountId(),
            source.identity().tenantId(),
            source.identity().connectScopeId(),
            "exchange-request-" + UUID.randomUUID());
    byte[] requestDigest = randomDigest();
    byte[] contextEvidenceDigest = randomDigest();
    byte[] authorityTupleDigest = randomDigest();
    byte[] issuanceFenceDigest = randomDigest();
    byte[] postconditionDigest = randomDigest();
    String resultTokenIdentity = "stored-result-jti-" + UUID.randomUUID();
    try {
      AccountBareLoginResponseEnvelope stored =
          inTransaction(
              prepared.db().transaction(),
              () -> {
                var claim =
                    prepared.db().bareLoginExchangeRepository().claim(identity, requestDigest);
                prepared
                    .db()
                    .bareLoginExchangeRepository()
                    .recordPendingEvidence(
                        claim,
                        requestDigest,
                        resultTokenIdentity,
                        storedResultHash,
                        contextEvidenceDigest,
                        authorityTupleDigest,
                        issuanceFenceDigest,
                        postconditionDigest);
                AccountEnvelopeBinding binding =
                    new AccountEnvelopeBinding(
                        AccountEnvelopeBinding.OperationKind.BARE_LOGIN_EXCHANGE,
                        claim.operation().operationId().toString(),
                        identity.requestId(),
                        Long.toString(identity.accountId()),
                        identity.tenantId().toString(),
                        identity.connectScopeId(),
                        identity.sourceConnectOperationId().toString(),
                        requestDigest,
                        contextEvidenceDigest,
                        authorityTupleDigest,
                        issuanceFenceDigest,
                        postconditionDigest);
                AccountEncryptedEnvelope encrypted =
                    prepared
                        .db()
                        .envelopeCrypto()
                        .encrypt(
                            AccountEnvelopePurpose.BARE_LOGIN_RESPONSE,
                            binding,
                            encryptedPlaintext);
                return prepared
                    .db()
                    .bareLoginExchangeRepository()
                    .completeWithEnvelope(
                        claim,
                        requestDigest,
                        resultTokenIdentity,
                        storedResultHash,
                        binding,
                        encrypted);
              });
      return new StoredBareLoginFixture(
          identity, requestDigest, source.identity(), stored.operationId());
    } finally {
      java.util.Arrays.fill(contextEvidenceDigest, (byte) 0);
      java.util.Arrays.fill(authorityTupleDigest, (byte) 0);
      java.util.Arrays.fill(issuanceFenceDigest, (byte) 0);
      java.util.Arrays.fill(postconditionDigest, (byte) 0);
    }
  }

  private static AccountStoredBareLoginRecoveryReader storedReader(PreparedAccount prepared) {
    return storedReader(prepared, prepared.db().envelopeCrypto());
  }

  private static AccountStoredBareLoginRecoveryReader storedReader(
      PreparedAccount prepared, AccountEnvelopeCrypto crypto) {
    return new AccountStoredBareLoginRecoveryReader(
        prepared.db().bareLoginExchangeRepository(),
        prepared.joinRepository(),
        crypto,
        prepared.reader(),
        prepared.gatewayKeys(),
        WORKLOAD_NAMESPACE);
  }

  private AccountEnvelopeCrypto missingKeyRingCrypto() {
    return new AccountEnvelopeCrypto(
        tempDir.resolve("missing-account-response-ring-" + UUID.randomUUID() + ".v1"));
  }

  private static AccountCommittedConnectSourceReader readerWithCrypto(
      PreparedAccount prepared, AccountEnvelopeCrypto crypto) {
    return new AccountCommittedConnectSourceReader(
        prepared.db().issuanceRepository(),
        prepared.accountRepository(),
        prepared.tenantResolver(),
        prepared.freshTenantIdentityRepository(),
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
      Clock clock,
      SourceTarget target) {
    BigInteger one = BigInteger.ONE;
    BigInteger now = BigInteger.valueOf(clock.instant().getEpochSecond());
    Map<String, Object> tenantGeneration = Map.of(tenantUuid.toString(), one);
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
    List<?> privateRealmGrantVersions =
        target.playtestLifecycleId() == null
            ? List.of()
            : List.of(
                Map.of(
                    "tenantId",
                    tenantUuid.toString(),
                    "worldSlug",
                    target.worldSlug(),
                    "realmSlug",
                    target.realmSlug(),
                    "playtestLifecycleId",
                    target.playtestLifecycleId().toString(),
                    "grantVersion",
                    one));
    Map<String, Object> authorityTuple =
        Map.of(
            "issuerAuthGeneration", one,
            "accountAuthorityGeneration", one,
            "tenantAuthorityGeneration", tenantGeneration,
            "membershipAuthorityGeneration", tenantGeneration,
            "privateRealmGrantVersions", privateRealmGrantVersions,
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
    claims.put("worldSlug", target.worldSlug());
    claims.put("realmSlug", target.realmSlug());
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
    claims.put("membershipVersion", tenantGeneration);
    claims.put("replayAdmissionFence", one);
    if (target.playtestLifecycleId() != null) {
      claims.put("playtestLifecycleId", target.playtestLifecycleId().toString());
      claims.put("playtestStateGeneration", target.playtestStateGeneration());
    }
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

  private static FreshTenantCreationEvidence freshTenantEvidence(UUID tenantUuid) {
    UUID creationRequestId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    long sourceGameRowId = 9001L;
    String sourceGameTenantKey = UUID.randomUUID().toString();
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            WORKLOAD_NAMESPACE,
            creationRequestId,
            sourceGameTenantKey,
            "Fresh source reader test tenant",
            null);
    String evidenceDigest =
        GameTenantCreationDigest.evidenceDigest(
            WORKLOAD_NAMESPACE,
            creationRequestId,
            operationId,
            requestDigest,
            tenantUuid,
            sourceGameRowId,
            sourceGameTenantKey,
            "NEW_GAME_ROW");
    return new FreshTenantCreationEvidence(
        1,
        WORKLOAD_NAMESPACE,
        creationRequestId,
        operationId,
        requestDigest,
        tenantUuid,
        sourceGameRowId,
        sourceGameTenantKey,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static AccountEnvelopeBinding bareLoginBinding(
      AccountBareLoginExchangeOperation operation, AccountBareLoginExchangeIdentity identity) {
    return new AccountEnvelopeBinding(
        AccountEnvelopeBinding.OperationKind.BARE_LOGIN_EXCHANGE,
        operation.operationId().toString(),
        identity.requestId(),
        Long.toString(identity.accountId()),
        identity.tenantId().toString(),
        identity.connectScopeId(),
        identity.sourceConnectOperationId().toString(),
        operation.requestDigest(),
        operation.contextEvidenceDigest(),
        operation.authorityTupleDigest(),
        operation.issuanceFenceDigest(),
        operation.postconditionDigest());
  }

  private static AccountEnvelopeBinding withTenantId(
      AccountEnvelopeBinding binding, String tenantId) {
    return new AccountEnvelopeBinding(
        binding.operationKind(),
        binding.operationId(),
        binding.requestId(),
        binding.accountId(),
        tenantId,
        binding.connectScopeId(),
        binding.sourceConnectOperationId(),
        binding.requestDigest(),
        binding.contextEvidenceDigest(),
        binding.authorityTupleDigest(),
        binding.issuanceFenceDigest(),
        binding.postconditionDigest());
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

  private static BareLoginSnapshot bareLoginSnapshot(PreparedAccount prepared, UUID operationId) {
    Record row =
        prepared
            .db()
            .dsl()
            .resultQuery(
                "SELECT operation_id, status, updated_at::text, "
                    + "(SELECT count(*) FROM account_bare_login_response_envelopes e "
                    + "WHERE e.operation_id = o.operation_id) "
                    + "FROM account_bare_login_exchange_operations o WHERE operation_id = ?",
                operationId)
            .fetchOne();
    return row == null
        ? new BareLoginSnapshot(null, null, null, 0L)
        : new BareLoginSnapshot(
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

  private static boolean awaitRelationLockBlock(
      DSLContext dsl, int readerBackendPid, int blockerBackendPid, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      Boolean blocked =
          dsl.resultQuery(
                  "SELECT EXISTS (SELECT 1 FROM pg_stat_activity waiting "
                      + "JOIN pg_locks waiting_lock ON waiting_lock.pid = waiting.pid "
                      + "WHERE waiting.pid = ? AND waiting.wait_event_type = 'Lock' "
                      + "AND waiting_lock.locktype = 'relation' AND NOT waiting_lock.granted "
                      + "AND ? = ANY(pg_blocking_pids(waiting.pid)))",
                  readerBackendPid,
                  blockerBackendPid)
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

  private static void assertHistoricalReadRejected(
      PreparedAccount prepared,
      SourceFixture source,
      Class<? extends Throwable> exceptionType,
      String messageFragment) {
    assertHistoricalReadRejected(
        prepared.reader(), prepared, source, exceptionType, messageFragment);
  }

  private static void assertHistoricalReadRejected(
      AccountCommittedConnectSourceReader reader,
      PreparedAccount prepared,
      SourceFixture source,
      Class<? extends Throwable> exceptionType,
      String messageFragment) {
    Snapshot before = sourceSnapshot(prepared, source.identity());
    assertThatThrownBy(
            () ->
                withPeer(
                    peer(WORKLOAD_NAMESPACE, "game-session-service"),
                    () ->
                        inTransaction(
                            prepared.db().transaction(),
                            () ->
                                reader.readHistorical(
                                    source.identity(), source.gatewayEnvelope()))))
        .isInstanceOf(exceptionType)
        .hasMessageContaining(messageFragment);
    assertThat(sourceSnapshot(prepared, source.identity())).isEqualTo(before);
  }

  private static void assertStoredReadRejected(
      AccountStoredBareLoginRecoveryReader reader,
      PreparedAccount prepared,
      StoredBareLoginFixture stored,
      byte[] requestDigest,
      Class<? extends Throwable> exceptionType,
      String messageFragment) {
    Snapshot sourceBefore = sourceSnapshot(prepared, stored.sourceIdentity());
    BareLoginSnapshot exchangeBefore = bareLoginSnapshot(prepared, stored.operationId());
    assertThatThrownBy(
            () ->
                withPeer(
                    peer(WORKLOAD_NAMESPACE, "game-session-service"),
                    () ->
                        inTransaction(
                            prepared.db().transaction(),
                            () -> reader.readHistorical(stored.identity(), requestDigest))))
        .isInstanceOf(exceptionType)
        .hasMessageContaining(messageFragment);
    assertThat(sourceSnapshot(prepared, stored.sourceIdentity())).isEqualTo(sourceBefore);
    assertThat(bareLoginSnapshot(prepared, stored.operationId())).isEqualTo(exchangeBefore);
  }

  private static HistoricalStoredBareLoginCorrelationEvidence readCorrelation(
      AccountStoredBareLoginRecoveryReader reader,
      PreparedAccount prepared,
      StoredBareLoginFixture stored,
      AccountConnectTokenIssuanceIdentity freshIdentity,
      String signedFreshGatewayContext) {
    return readCorrelation(
        reader,
        prepared,
        stored.identity(),
        stored.requestDigest(),
        freshIdentity,
        signedFreshGatewayContext);
  }

  private static HistoricalStoredBareLoginCorrelationEvidence readCorrelation(
      AccountStoredBareLoginRecoveryReader reader,
      PreparedAccount prepared,
      AccountBareLoginExchangeIdentity originalIdentity,
      byte[] originalRequestDigest,
      AccountConnectTokenIssuanceIdentity freshIdentity,
      String signedFreshGatewayContext) {
    return withPeer(
        peer(WORKLOAD_NAMESPACE, "game-session-service"),
        () ->
            inTransaction(
                prepared.db().transaction(),
                () ->
                    reader.readCorrelation(
                        originalIdentity,
                        originalRequestDigest,
                        freshIdentity,
                        signedFreshGatewayContext)));
  }

  private static void assertCorrelationReadRejected(
      PreparedAccount prepared,
      AccountStoredBareLoginRecoveryReader reader,
      StoredBareLoginFixture stored,
      AccountConnectTokenIssuanceIdentity freshIdentity,
      String signedFreshGatewayContext,
      Class<? extends Throwable> exceptionType,
      String messageFragment) {
    assertCorrelationReadRejected(
        prepared,
        reader,
        stored,
        stored.identity(),
        stored.requestDigest(),
        freshIdentity,
        signedFreshGatewayContext,
        exceptionType,
        messageFragment);
  }

  private static void assertCorrelationReadRejected(
      PreparedAccount prepared,
      AccountStoredBareLoginRecoveryReader reader,
      StoredBareLoginFixture stored,
      byte[] originalRequestDigest,
      AccountConnectTokenIssuanceIdentity freshIdentity,
      String signedFreshGatewayContext,
      Class<? extends Throwable> exceptionType,
      String messageFragment) {
    assertCorrelationReadRejected(
        prepared,
        reader,
        stored,
        stored.identity(),
        originalRequestDigest,
        freshIdentity,
        signedFreshGatewayContext,
        exceptionType,
        messageFragment);
  }

  private static void assertCorrelationReadRejected(
      PreparedAccount prepared,
      AccountStoredBareLoginRecoveryReader reader,
      StoredBareLoginFixture stored,
      AccountBareLoginExchangeIdentity originalIdentity,
      byte[] originalRequestDigest,
      AccountConnectTokenIssuanceIdentity freshIdentity,
      String signedFreshGatewayContext,
      Class<? extends Throwable> exceptionType,
      String messageFragment) {
    Snapshot originalSourceBefore = sourceSnapshot(prepared, stored.sourceIdentity());
    Snapshot freshSourceBefore = sourceSnapshot(prepared, freshIdentity);
    BareLoginSnapshot exchangeBefore = bareLoginSnapshot(prepared, stored.operationId());
    assertThatThrownBy(
            () ->
                readCorrelation(
                    reader,
                    prepared,
                    originalIdentity,
                    originalRequestDigest,
                    freshIdentity,
                    signedFreshGatewayContext))
        .isInstanceOf(exceptionType)
        .hasMessageContaining(messageFragment);
    assertThat(sourceSnapshot(prepared, stored.sourceIdentity())).isEqualTo(originalSourceBefore);
    assertThat(sourceSnapshot(prepared, freshIdentity)).isEqualTo(freshSourceBefore);
    assertThat(bareLoginSnapshot(prepared, stored.operationId())).isEqualTo(exchangeBefore);
  }

  private static void assertCorrelationIdentityRejected(
      PreparedAccount prepared,
      AccountStoredBareLoginRecoveryReader reader,
      StoredBareLoginFixture stored,
      AccountConnectTokenIssuanceIdentity freshIdentity) {
    Snapshot originalSourceBefore = sourceSnapshot(prepared, stored.sourceIdentity());
    BareLoginSnapshot exchangeBefore = bareLoginSnapshot(prepared, stored.operationId());
    assertThatThrownBy(
            () ->
                readCorrelation(
                    reader, prepared, stored, freshIdentity, "unused-unverified-context"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("same Account and tenant");
    assertThat(sourceSnapshot(prepared, stored.sourceIdentity())).isEqualTo(originalSourceBefore);
    assertThat(bareLoginSnapshot(prepared, stored.operationId())).isEqualTo(exchangeBefore);
  }

  private static String resignCurrentGatewayContext(PreparedAccount prepared, SourceFixture source)
      throws Exception {
    Map<String, Object> currentClaims =
        GatewayConnectContextCodec.projectVerifiedAccountGameplayConnectClaims(
            source.sourceClaims(),
            prepared.clock().instant().getEpochSecond(),
            "gateway-request-" + UUID.randomUUID());
    byte[] payload = JSON.writeValueAsBytes(currentClaims);
    return GatewayConnectContextSignature.sign(
        payload, GATEWAY_KEY_ID, prepared.gatewayKeyPair().getPrivate());
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

  private static byte[] randomDigest() {
    byte[] value = new byte[32];
    FIXTURE_RANDOM.nextBytes(value);
    return value;
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

  private record SourceTarget(
      String worldSlug,
      String realmSlug,
      UUID playtestLifecycleId,
      BigInteger playtestStateGeneration) {
    private static final SourceTarget NORMAL = new SourceTarget("world", "realm", null, null);

    private SourceTarget {
      if ((playtestLifecycleId == null) != (playtestStateGeneration == null)) {
        throw new IllegalArgumentException(
            "Playtest lifecycle and generation must appear together");
      }
    }
  }

  private record TestContext(
      DSLContext dsl,
      TransactionTemplate transaction,
      AccountConnectTokenIssuanceRepository issuanceRepository,
      AccountBareLoginExchangeRepository bareLoginExchangeRepository,
      AccountEnvelopeCrypto envelopeCrypto) {}

  private record PreparedAccount(
      TestContext db,
      long accountId,
      long tenantId,
      UUID accountUuid,
      UUID tenantUuid,
      UUID freshTenantUuid,
      KeyPair accountKeyPair,
      KeyPair unknownAccountKeyPair,
      KeyPair gatewayKeyPair,
      KeyPair unknownGatewayKeyPair,
      AccountGameplayConnectSourceVerifier verifier,
      Map<String, java.security.PublicKey> gatewayKeys,
      AccountRepository accountRepository,
      AccountTenantIdentityResolver tenantResolver,
      FreshTenantIdentityAssociationRepository freshTenantIdentityRepository,
      AccountJoinOperationRepository joinRepository,
      AccountCommittedConnectSourceReader reader,
      TestClock clock) {}

  private record SourceFixture(
      AccountConnectTokenIssuanceIdentity identity,
      String gatewayEnvelope,
      Map<String, Object> sourceClaims,
      Map<String, Object> gatewayClaims,
      String tokenIdentity) {}

  private record StoredBareLoginFixture(
      AccountBareLoginExchangeIdentity identity,
      byte[] requestDigest,
      AccountConnectTokenIssuanceIdentity sourceIdentity,
      UUID operationId) {
    private StoredBareLoginFixture {
      requestDigest = requestDigest.clone();
    }

    @Override
    public byte[] requestDigest() {
      return requestDigest.clone();
    }
  }

  private record Snapshot(UUID operationId, String status, String updatedAt, long envelopeCount) {}

  private record BareLoginSnapshot(
      UUID operationId, String status, String updatedAt, long envelopeCount) {}

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
