package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import io.grpc.Context;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding.ApiCall;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding.ApiResponse;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding.ParsedBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountPublicJwksReadWorkloadGuard;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationRequest;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationResult;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PrivatePromotionObservation;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PrivatePromotionReceipt;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PublicKeyIdentity;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.repository.AccountJwtValidatorInventoryRepository;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.ConfigMapSnapshot;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.InvalidJwksException;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourcePreparedDenyException;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceUnavailableException;
import net.firedevops.firemud.common.security.AccountPublicJwksReadCodec;
import net.firedevops.firemud.common.security.AccountPublicJwksReadCodec.ReadResult;
import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Physical PostgreSQL witnesses at both existing Account public ConfigMap writers. Normal Flyway
 * guards and owner repositories produce all durable evidence. Only API transport, projected files
 * and authenticated readiness-receiver inputs are controlled. A common-cache bridge consumes real
 * public service responses; it is not Game Session transport, protected-mount or activation proof.
 * Exact pending prepublication is unavailable without a deny latch; exact PREPARED ACTIVE writer
 * windows deny without keys until the real owner commit. Genuine tamper remains terminal.
 */
class AccountPublicJwksWriterWindowPostgresIntegrationTest {
  private static final String SCHEMA_PREFIX = "jwks_writer_window";
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture(true);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String CLUSTER_UID = "11111111-1111-4111-8111-111111111111";
  private static final String NAMESPACE_UID = "22222222-2222-4222-8222-222222222222";
  private static final String SECRET_UID = "33333333-3333-4333-8333-333333333333";
  private static final String CONFIG_MAP_UID = "44444444-4444-4444-8444-444444444444";
  private static final String API_DIGEST = "c".repeat(64);
  private static final String API_REVISION = "api-r1";
  private final Set<String> schemas = ConcurrentHashMap.newKeySet();
  @TempDir Path temporaryDirectory;

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @AfterEach
  void dropRunOwnedSchemas() {
    JdbcTemplate jdbc = new JdbcTemplate(POSTGRES.dataSource());
    for (String schema : schemas) {
      if (!schema.startsWith(SCHEMA_PREFIX + "_") || !schema.matches("[a-z][a-z0-9_]{0,62}")) {
        throw new IllegalStateException("Refusing to clean an unowned PostgreSQL schema");
      }
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
    schemas.clear();
  }

  @Test
  void initialPrepublicationCutsRemainUnavailableUntilExactFailedReceiptReconciliation()
      throws Exception {
    Harness harness = harness();
    harness.generate();
    AtomicReference<String> publishedVersion = new AtomicReference<>();
    harness.transport.beforePatch =
        () -> {
          assertThat(harness.count("account_jwt_jwks_prepublication_intents")).isEqualTo(1);
          assertThat(harness.count("account_jwt_jwks_publication_receipts")).isZero();
          assertThat(harness.transport.data.get("jwks.json")).isEqualTo("{\"keys\":[]}");
          harness.assertUnavailableRead();
        };
    harness.transport.afterPatch =
        () -> {
          publishedVersion.set(harness.transport.version);
          harness.assertLiveMatchesCurrentPrepublicationIntent();
          assertThat(harness.count("account_jwt_jwks_publication_receipts")).isZero();
          harness.assertUnavailableRead();
          harness.manager.beforeCommit.set(
              () ->
                  harness.observeBlockedPublicRead(
                      harness::assertUnavailableRead,
                      () ->
                          assertThat(harness.count("account_jwt_jwks_publication_receipts"))
                              .isZero()));
        };
    try {
      assertThatThrownBy(harness.prepublication::prepublishCurrentGeneration)
          .isInstanceOf(TransactionSystemException.class);
    } finally {
      harness.awaitBlockedPublicRead();
    }
    assertThat(harness.count("account_jwt_jwks_publication_receipts")).isZero();
    assertThat(harness.transport.patches).isEqualTo(1);
    harness.assertUnavailableRead();
    harness.transport.clearHooks();
    var receipt = harness.prepublication.prepublishCurrentGeneration();
    assertThat(receipt.observedResourceVersion()).isEqualTo(publishedVersion.get());
    assertThat(harness.transport.patches).isEqualTo(1);
    assertThat(harness.prepublication.prepublishCurrentGeneration()).isEqualTo(receipt);
    harness.mountAndObserve();
    ReadResult stable = harness.read();
    assertThat(stable.preparedDeny()).isFalse();
    assertThat(stable.committedRecoveryEligible()).isFalse();
    assertThat(stable.jwksBytes()).isEqualTo(harness.publicBytes());
    assertThat(harness.count("account_jwt_jwks_prepublication_intents")).isEqualTo(1);
    assertThat(harness.count("account_jwt_jwks_publication_receipts")).isEqualTo(1);
    assertThat(harness.count("account_jwt_jwks_mount_observations")).isEqualTo(1);
  }

  @Test
  void committedSignerRefusesNextPublicationWithoutDurableRotationRequestAndPreservesCache()
      throws Exception {
    Harness harness = harness();
    harness.commitInitialSigner();
    AccountPublicJwksCache cache = harness.cache();
    String committedKid = harness.result.targetKid();
    var cachedKey = cache.keyFor(committedKid);
    var committed =
        harness
            .tx(
                () ->
                    harness.desired.readCurrentCommittedSigner(
                        harness.binding.accountBinding(), harness.trust, API_DIGEST, API_REVISION))
            .orElseThrow();
    var state =
        harness
            .tx(() -> harness.desired.readEnrollmentState(harness.binding.accountBinding()))
            .orElseThrow();
    assertThat(state.generationOperationId()).isEmpty();
    assertThat(state.preparedOperationId()).isEmpty();
    ConfigMapSnapshot publicResource = harness.configMap.observe();
    Map<String, List<String>> durableHistory = harness.ownerHistoryImages();
    String mountedJwks = Files.readString(harness.publicMount.resolve("jwks.json"));
    String mountedMarker = Files.readString(harness.publicMount.resolve("jwt-generation.json"));
    int patchCount = harness.transport.patches;

    // A subsequent rotation-request entrypoint and its prepublication/pruning lifecycle are not
    // implemented or physically qualified. Neither a retained signer nor a test may invent it.
    assertThatThrownBy(
            () ->
                harness.tx(
                    () ->
                        harness.desired.ensureCurrentGenerationRequest(
                            harness.binding.accountBinding(), harness.trust)))
        .isExactlyInstanceOf(
            AccountJwtSignerDesiredStateRepository.MissingGenerationOperationException.class)
        .hasMessage("Account has no durable rotation request for its committed signer");
    assertThat(harness.ownerHistoryImages()).isEqualTo(durableHistory);
    assertThat(harness.transport.patches).isEqualTo(patchCount);
    assertThatThrownBy(harness.prepublication::prepublishCurrentGeneration)
        .isExactlyInstanceOf(
            AccountJwtSignerDesiredStateRepository.MissingGenerationOperationException.class)
        .hasMessage("Account has no owner-selected JWT generation operation");

    assertThat(harness.ownerHistoryImages()).isEqualTo(durableHistory);
    assertThat(
            harness
                .tx(() -> harness.desired.readEnrollmentState(harness.binding.accountBinding()))
                .orElseThrow())
        .isEqualTo(state);
    assertThat(
            harness
                .tx(
                    () ->
                        harness.desired.readCurrentCommittedSigner(
                            harness.binding.accountBinding(),
                            harness.trust,
                            API_DIGEST,
                            API_REVISION))
                .orElseThrow())
        .isEqualTo(committed);
    assertThat(harness.transport.patches).isEqualTo(patchCount);
    assertThat(harness.configMap.observe()).isEqualTo(publicResource);
    assertThat(Files.readString(harness.publicMount.resolve("jwks.json"))).isEqualTo(mountedJwks);
    assertThat(Files.readString(harness.publicMount.resolve("jwt-generation.json")))
        .isEqualTo(mountedMarker);
    ReadResult stable = harness.read();
    assertThat(stable.preparedDeny()).isFalse();
    assertThat(stable.committedRecoveryEligible()).isTrue();
    assertThat(stable.jwksBytes()).isEqualTo(harness.publicBytes());
    cache.refresh();
    assertThat(cache.keyFor(committedKid)).isEqualTo(cachedKey);
    assertThat(harness.count("account_jwt_jwks_prepublication_intents")).isEqualTo(1);
    assertThat(harness.count("account_jwt_jwks_publication_receipts")).isEqualTo(1);
    assertThat(harness.count("account_jwt_jwks_mount_observations")).isEqualTo(1);
  }

  @Test
  void activeCasWithoutReceiptDeniesCachedKeysAcrossOutageAndRecoversAfterExactOwnerCommit()
      throws Exception {
    Harness harness = harness();
    harness.generate();
    harness.prepublication.prepublishCurrentGeneration();
    harness.mountAndObserve();
    AccountPublicJwksCache transitionCache = harness.cache();
    AccountPublicJwksCache preparedCache = harness.cache();
    String kid = harness.result.targetKid();
    assertThat(transitionCache.keyFor(kid)).isNotNull();
    assertThat(preparedCache.keyFor(kid)).isNotNull();
    harness.prepareAndRecordPrivatePromotion();
    harness.assertPreparedDeny();
    assertThatThrownBy(preparedCache::refresh).isInstanceOf(SourcePreparedDenyException.class);
    assertThatThrownBy(() -> preparedCache.keyFor(kid))
        .isInstanceOf(SourcePreparedDenyException.class);
    harness.transport.beforePatch = harness::assertPreparedDeny;
    harness.transport.afterPatch =
        () -> {
          harness.assertLiveMatchesPreparedActiveIntent();
          harness.assertNoActiveReceipt();
          harness.assertPreparedDeny();
          assertThatThrownBy(transitionCache::refresh)
              .isInstanceOf(SourcePreparedDenyException.class);
          harness.manager.beforeCommit.set(
              () ->
                  harness.observeBlockedPublicRead(
                      harness::assertPreparedDeny,
                      () ->
                          assertThat(
                                  harness
                                      .dsl
                                      .resultQuery(
                                          "SELECT count(*) FROM account_jwt_signer_promotion_operations "
                                              + "WHERE active_jwks_receipt_digest IS NOT NULL")
                                      .fetchOne(0, Long.class))
                              .isZero()));
        };
    try {
      assertThatThrownBy(harness::reconcilePromotion)
          .isInstanceOf(TransactionSystemException.class);
    } finally {
      harness.awaitBlockedPublicRead();
    }
    harness.assertNoActiveReceipt();
    harness.assertLiveMatchesPreparedActiveIntent();
    harness.assertPreparedDeny();
    int writtenPatches = harness.transport.patches;
    harness.transport.clearHooks();
    harness.assertChangedPrivateReceiptReplayRefused();
    harness.transport.unavailable = true;
    try {
      harness.assertUnavailableRead();
      assertThatThrownBy(transitionCache::refresh).isInstanceOf(SourcePreparedDenyException.class);
      assertThatThrownBy(() -> transitionCache.keyFor(kid))
          .isInstanceOf(SourcePreparedDenyException.class);
      assertThatThrownBy(() -> preparedCache.keyFor(kid))
          .isInstanceOf(SourcePreparedDenyException.class);
    } finally {
      harness.transport.unavailable = false;
    }
    harness.assertPreparedDeny();
    AtomicInteger exactReceiptPreparedReads = new AtomicInteger();
    harness.afterInventory =
        () -> {
          var promotion =
              harness
                  .tx(
                      () ->
                          harness.desired.readPreparedGenerationForRecovery(
                              harness.binding.accountBinding(), harness.trust))
                  .promotion();
          if (promotion.activeJwksReceiptDigest().isPresent()) {
            exactReceiptPreparedReads.incrementAndGet();
            harness.assertPreparedDeny();
            assertThatThrownBy(preparedCache::refresh)
                .isInstanceOf(SourcePreparedDenyException.class);
          }
        };
    harness.reconcilePromotion();
    harness.afterInventory = () -> {};
    assertThat(exactReceiptPreparedReads.get()).isEqualTo(1);
    assertThat(harness.transport.patches).isEqualTo(writtenPatches);
    var committed =
        harness
            .tx(
                () ->
                    harness.desired.readCurrentCommittedSigner(
                        harness.binding.accountBinding(), harness.trust, API_DIGEST, API_REVISION))
            .orElseThrow();
    assertThat(committed.promotion().status()).isEqualTo("COMMITTED");
    assertThat(committed.publicReceipt().observedResourceVersion())
        .isEqualTo(harness.transport.version);
    ReadResult stable = harness.read();
    assertThat(stable.preparedDeny()).isFalse();
    assertThat(stable.committedRecoveryEligible()).isTrue();
    assertThat(stable.jwksBytes()).isEqualTo(harness.publicBytes());
    preparedCache.refresh();
    assertThat(preparedCache.keyFor(kid)).isNotNull();
    harness.assertCurrentMountedKeyCorrespondence();
    assertThat(stable.sourceIdentity()).isEqualTo(transitionCache.sourceIdentity());
    transitionCache.refresh();
    assertThat(transitionCache.keyFor(kid)).isNotNull();
    harness.reconcilePromotion();
    assertThat(harness.transport.patches).isEqualTo(writtenPatches);
    assertThat(
            harness
                .tx(
                    () ->
                        harness.desired.readCurrentCommittedSigner(
                            harness.binding.accountBinding(),
                            harness.trust,
                            API_DIGEST,
                            API_REVISION))
                .orElseThrow())
        .isEqualTo(committed);

    // Removing a key at the same UID is not a supported owner transition, even after prior commit.
    Map<String, String> stableData = harness.transport.data;
    AccountPublicJwksCache tamperedCache = harness.cache();
    assertThat(tamperedCache.keyFor(kid)).isNotNull();
    Map<String, String> removed = new LinkedHashMap<>(stableData);
    removed.put("jwks.json", "{\"keys\":[]}");
    harness.transport.data = Map.copyOf(removed);
    harness.assertInvalidRead();
    assertThatThrownBy(tamperedCache::refresh).isInstanceOf(InvalidJwksException.class);
    harness.transport.data = stableData;
    ReadResult restored = harness.read();
    assertThat(restored.sourceIdentity()).isEqualTo(stable.sourceIdentity());
    assertThat(restored.jwksBytes()).isEqualTo(stable.jwksBytes());
    assertThat(restored.preparedDeny()).isEqualTo(stable.preparedDeny());
    assertThat(restored.committedRecoveryEligible()).isEqualTo(stable.committedRecoveryEligible());
    assertThatThrownBy(tamperedCache::refresh).isInstanceOf(InvalidJwksException.class);
    assertThatThrownBy(() -> tamperedCache.keyFor(kid)).isInstanceOf(InvalidJwksException.class);
  }

  @Test
  void durableReceiptCommitAcknowledgementLossRetainsBothWriterImagesAcrossExactReplay()
      throws Exception {
    Harness harness = harness();
    harness.generate();
    harness.transport.afterPatch = () -> harness.manager.loseCommitAcknowledgement.set(true);
    assertThatThrownBy(harness.prepublication::prepublishCurrentGeneration)
        .isInstanceOf(TransactionSystemException.class)
        .hasMessage("Controlled durable receipt acknowledgement loss");
    harness.transport.clearHooks();
    var durablePublication =
        harness
            .independent(
                () ->
                    harness.tx(
                        () ->
                            harness.publication.readCurrentPublication(
                                harness.binding.accountBinding(), harness.trust)))
            .orElseThrow();
    var durableReceipt = durablePublication.receipt().orElseThrow();
    assertThat(durableReceipt.observedResourceVersion()).isEqualTo(harness.transport.version);
    assertThat(durablePublication.intent().jwksJson())
        .isEqualTo(harness.transport.data.get("jwks.json"));
    assertThat(durablePublication.intent().generationMarkerJson())
        .isEqualTo(harness.transport.data.get("jwt-generation.json"));
    assertThat(harness.count("account_jwt_jwks_publication_receipts")).isEqualTo(1);
    assertThat(harness.read().jwksBytes()).isEqualTo(harness.publicBytes());
    assertThat(harness.read().committedRecoveryEligible()).isFalse();
    int prepublicationPatches = harness.transport.patches;
    assertThat(harness.prepublication.prepublishCurrentGeneration()).isEqualTo(durableReceipt);
    assertThat(harness.transport.patches).isEqualTo(prepublicationPatches);
    assertThat(
            harness
                .tx(
                    () ->
                        harness.publication.readCurrentPublication(
                            harness.binding.accountBinding(), harness.trust))
                .orElseThrow())
        .isEqualTo(durablePublication);
    harness.mountAndObserve();
    harness.prepareAndRecordPrivatePromotion();
    harness.transport.afterPatch = () -> harness.manager.loseCommitAcknowledgement.set(true);
    assertThatThrownBy(harness::reconcilePromotion)
        .isInstanceOf(TransactionSystemException.class)
        .hasMessage("Controlled durable receipt acknowledgement loss");
    harness.transport.clearHooks();
    var durableActive =
        harness
            .independent(
                () ->
                    harness.tx(
                        () ->
                            harness.desired.readPreparedGenerationForRecovery(
                                harness.binding.accountBinding(), harness.trust)))
            .promotion();
    assertThat(durableActive.status()).isEqualTo("PREPARED");
    assertThat(durableActive.activeJwksReceiptDigest()).isPresent();
    assertThat(durableActive.activeJwksObservedResourceVersion())
        .contains(harness.transport.version);
    assertThat(durableActive.activeJwksPublicDataDigest())
        .contains(
            AccountJwtJwksPublicationRepository.publicDataDigest(
                harness.transport.data.get("jwks.json"),
                harness.transport.data.get("jwt-generation.json")));
    assertThat(durableActive.expectedPublicJwksJson())
        .isEqualTo(harness.transport.data.get("jwks.json"));
    assertThat(durableActive.expectedActiveMarkerJson())
        .isEqualTo(harness.transport.data.get("jwt-generation.json"));
    harness.assertPreparedDeny();
    int activePatches = harness.transport.patches;
    harness.reconcilePromotion();
    assertThat(harness.transport.patches).isEqualTo(activePatches);
    var committed =
        harness
            .independent(
                () ->
                    harness.tx(
                        () ->
                            harness.desired.readCurrentCommittedSigner(
                                harness.binding.accountBinding(),
                                harness.trust,
                                API_DIGEST,
                                API_REVISION)))
            .orElseThrow();
    assertThat(committed.publicReceipt().receiptDigest())
        .isEqualTo(durableActive.activeJwksReceiptDigest().orElseThrow());
    assertThat(committed.promotion().expectedPublicJwksJson())
        .isEqualTo(durableActive.expectedPublicJwksJson());
    assertThat(committed.promotion().expectedActiveMarkerJson())
        .isEqualTo(durableActive.expectedActiveMarkerJson());
    assertThat(harness.read().committedRecoveryEligible()).isTrue();
    assertThat(harness.read().jwksBytes()).isEqualTo(harness.publicBytes());
    assertThat(harness.count("account_jwt_jwks_publication_receipts")).isEqualTo(1);
  }

  @Test
  void bothWritersReconcileLostCasRepliesFromExactLiveReadbackWithoutASecondMutation()
      throws Exception {
    Harness harness = harness();
    harness.generate();
    harness.transport.losePatchReply.set(true);
    harness.transport.afterPatch =
        () -> {
          harness.assertLiveMatchesCurrentPrepublicationIntent();
          assertThat(harness.count("account_jwt_jwks_publication_receipts")).isZero();
          harness.assertUnavailableRead();
        };
    var receipt = harness.prepublication.prepublishCurrentGeneration();
    harness.transport.clearHooks();
    assertThat(harness.transport.lostPatchReplies).isEqualTo(1);
    assertThat(harness.transport.patches).isEqualTo(1);
    assertThat(receipt.observedResourceVersion()).isEqualTo(harness.transport.version);
    assertThat(harness.prepublication.prepublishCurrentGeneration()).isEqualTo(receipt);
    assertThat(harness.transport.patches).isEqualTo(1);
    harness.mountAndObserve();
    assertThat(harness.read().jwksBytes()).isEqualTo(harness.publicBytes());
    harness.prepareAndRecordPrivatePromotion();
    harness.transport.losePatchReply.set(true);
    harness.transport.afterPatch =
        () -> {
          harness.assertLiveMatchesPreparedActiveIntent();
          harness.assertNoActiveReceipt();
          harness.assertPreparedDeny();
        };
    harness.reconcilePromotion();
    harness.transport.clearHooks();
    assertThat(harness.transport.lostPatchReplies).isEqualTo(2);
    assertThat(harness.transport.patches).isEqualTo(2);
    var committed =
        harness
            .tx(
                () ->
                    harness.desired.readCurrentCommittedSigner(
                        harness.binding.accountBinding(), harness.trust, API_DIGEST, API_REVISION))
            .orElseThrow();
    assertThat(committed.publicReceipt().observedResourceVersion())
        .isEqualTo(harness.transport.version);
    assertThat(harness.read().committedRecoveryEligible()).isTrue();
    assertThat(harness.read().jwksBytes()).isEqualTo(harness.publicBytes());
    harness.reconcilePromotion();
    assertThat(harness.transport.patches).isEqualTo(2);
    assertThat(harness.count("account_jwt_jwks_publication_receipts")).isEqualTo(1);
  }

  @Test
  void ordinaryPrepublicationOutagePreservesOriginalCacheAgeAndPendingReadinessCanProgress()
      throws Exception {
    Harness harness = harness();
    harness.generate();
    harness.prepublication.prepublishCurrentGeneration();
    harness.mountAndObserve();
    AdjustableClock clock = new AdjustableClock(Instant.parse("2026-11-10T12:00:00Z"));
    AccountPublicJwksCache cache = harness.cache(clock, Duration.ofSeconds(5));
    String kid = harness.result.targetKid();
    assertThat(cache.keyFor(kid)).isNotNull();
    var pin = cache.sourceIdentity();
    assertThat(harness.read().committedRecoveryEligible()).isFalse();
    clock.advance(Duration.ofSeconds(4));
    harness.transport.unavailable = true;
    try {
      harness.assertUnavailableRead();
      cache.refresh();
      assertThat(cache.keyFor(kid)).isNotNull();
      clock.advance(Duration.ofSeconds(1));
      assertThatThrownBy(() -> cache.keyFor(kid)).isInstanceOf(SourceUnavailableException.class);
      clock.advance(Duration.ofSeconds(1));
      assertThatThrownBy(cache::refresh).isInstanceOf(SourceUnavailableException.class);
    } finally {
      harness.transport.unavailable = false;
    }
    cache.refresh();
    assertThat(cache.keyFor(kid)).isNotNull();
    assertThat(harness.read().sourceIdentity()).isEqualTo(pin);
    assertThat(harness.read().committedRecoveryEligible()).isFalse();
    // Receipt-backed pending keys are usable for real mounted-key readiness before PREPARED.
    harness.prepareAndRecordPrivatePromotion();
    harness.assertPreparedDeny();
    assertThatThrownBy(cache::refresh).isInstanceOf(SourcePreparedDenyException.class);
    assertThatThrownBy(() -> cache.keyFor(kid)).isInstanceOf(SourcePreparedDenyException.class);
    harness.reconcilePromotion();
    harness.assertCurrentMountedKeyCorrespondence();
    assertThat(harness.read().sourceIdentity()).isEqualTo(pin);
    assertThat(harness.read().committedRecoveryEligible()).isTrue();
    cache.refresh();
    assertThat(cache.keyFor(kid)).isNotNull();
  }

  private static final class AdjustableClock extends Clock {
    private Instant now;

    private AdjustableClock(Instant now) {
      this.now = now;
    }

    private void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private Harness harness() throws Exception {
    String schema = SCHEMA_PREFIX + "_" + UUID.randomUUID().toString().replace("-", "");
    schemas.add(schema);
    return new Harness(
        POSTGRES.dataSource(schema),
        schema,
        Files.createDirectory(temporaryDirectory.resolve(schema)));
  }

  private static final class ReceiptCommitFailureManager extends DataSourceTransactionManager {
    private static final long serialVersionUID = 1L;
    private final transient ThreadLocal<IntConsumer> readerBackend = new ThreadLocal<>();
    private final AtomicReference<Runnable> beforeCommit = new AtomicReference<>();
    private final AtomicBoolean loseCommitAcknowledgement = new AtomicBoolean();

    private ReceiptCommitFailureManager(DataSource dataSource) {
      super(dataSource);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      super.doBegin(transaction, definition);
      IntConsumer capture = readerBackend.get();
      if (capture != null) {
        var connection =
            DataSourceUtils.getConnection(java.util.Objects.requireNonNull(getDataSource()));
        try (var statement = connection.createStatement();
            var result = statement.executeQuery("SELECT pg_backend_pid()")) {
          if (!result.next()) throw new IllegalStateException("Reader backend PID unavailable");
          capture.accept(result.getInt(1));
        } catch (java.sql.SQLException failure) {
          throw new TransactionSystemException("Reader backend PID unavailable", failure);
        } finally {
          DataSourceUtils.releaseConnection(connection, getDataSource());
        }
      }
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      Runnable cut = beforeCommit.getAndSet(null);
      if (cut != null) {
        try {
          cut.run();
        } finally {
          super.doRollback(status);
        }
        throw new TransactionSystemException("Controlled owner receipt commit loss");
      }
      super.doCommit(status);
      if (loseCommitAcknowledgement.getAndSet(false)) {
        throw new TransactionSystemException("Controlled durable receipt acknowledgement loss");
      }
    }
  }

  private static final class Harness {
    private final DSLContext dsl;
    private final ReceiptCommitFailureManager manager;
    private final TransactionTemplate transaction;
    private final AccountJwtSignerMaterializerTrustBinding.Binding binding;
    private final TrustFence trust;
    private final AccountJwtSignerDesiredStateRepository desired;
    private final AccountJwtJwksPublicationRepository publication;
    private final AccountJwtReadinessProbeRepository readiness;
    private final AccountJwtJwksPrepublicationService prepublication;
    private final AccountJwtSignerBootstrapCoordinator coordinator;
    private final AccountPublicJwksReadService publicRead;
    private final ControlledTransport transport;
    private final AccountJwtJwksConfigMapClient configMap;
    private final Path privateMount;
    private final Path publicMount;
    private GenerationRequest request;
    private GenerationResult result;
    private KeyPair keyPair;
    private AccountJwtPreparedPublicationPostgresFixture readinessFixture;
    private PrivatePromotionObservation privateObservation;
    private PrivatePromotionReceipt privateReceipt;
    private Runnable afterInventory = () -> {};
    private final AtomicReference<CompletableFuture<Void>> blockedPublicRead =
        new AtomicReference<>();

    private Harness(DataSource dataSource, String schema, Path directory) throws Exception {
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .placeholders(Map.of("serviceSchema", schema))
          .locations("classpath:db/migration")
          .load()
          .migrate();
      manager = new ReceiptCommitFailureManager(dataSource);
      transaction = new TransactionTemplate(manager);
      dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
      String peer = "spiffe://firemud/ns/firemud-prod/sa/jwt-signer-materializer";
      List<String> pins = List.of("d".repeat(64));
      String digest =
          AccountJwtSignerMaterializerTrustBinding.computeBindingDigest(
              "trust-r1",
              "prod",
              "prod-cluster-1",
              "firemud-prod",
              CLUSTER_UID,
              NAMESPACE_UID,
              peer,
              pins);
      binding =
          new AccountJwtSignerMaterializerTrustBinding.Binding(
              "prod",
              "prod-cluster-1",
              "firemud-prod",
              CLUSTER_UID,
              NAMESPACE_UID,
              peer,
              pins,
              "trust-r1",
              digest);
      trust = new TrustFence(CLUSTER_UID, NAMESPACE_UID, digest, "trust-r1");
      var materializer = mock(AccountJwtSignerMaterializerTrustBinding.class);
      when(materializer.current()).thenReturn(Optional.of(binding));
      ParsedBinding parsed =
          new ParsedBinding(
              API_REVISION,
              "prod",
              "prod-cluster-1",
              URI.create("https://kubernetes.test:6443"),
              "kubernetes.test",
              Path.of("unused-ca.pem"),
              "b".repeat(64),
              Path.of("unused-token"),
              "firemud-prod",
              CLUSTER_UID,
              NAMESPACE_UID,
              "system:serviceaccount:firemud-prod:account-service",
              API_DIGEST);
      var api = mock(AccountJwtJwksApiBinding.class);
      when(api.current()).thenReturn(parsed);
      transport = new ControlledTransport(parsed);
      configMap = new AccountJwtJwksConfigMapClient(() -> transport);
      desired = new AccountJwtSignerDesiredStateRepository(dsl);
      publication = new AccountJwtJwksPublicationRepository(dsl, desired);
      readiness =
          new AccountJwtReadinessProbeRepository(
              dsl, desired, publication, new AccountJwtValidatorInventoryRepository(dsl));
      privateMount = Files.createDirectory(directory.resolve("private"));
      publicMount = Files.createDirectory(directory.resolve("public"));
      transport.projectPublishedData =
          () -> {
            try {
              Files.writeString(publicMount.resolve("jwks.json"), transport.data.get("jwks.json"));
              Files.writeString(
                  publicMount.resolve("jwt-generation.json"),
                  transport.data.get("jwt-generation.json"));
            } catch (java.io.IOException failure) {
              throw new IllegalStateException(
                  "Controlled public projection could not catch up", failure);
            }
          };
      prepublication =
          new AccountJwtJwksPrepublicationService(
              desired,
              publication,
              materializer,
              configMap,
              manager,
              new AccountJwtJwksPrepublicationService.MountedProjection(
                  privateMount,
                  Path.of("pending.key"),
                  publicMount,
                  Path.of("jwks.json"),
                  Path.of("jwt-generation.json")));
      var inventory = mock(AccountJwtValidatorInventorySource.class);
      when(inventory.observe(any(AccountJwtValidatorInventorySource.ObservationContext.class)))
          .thenAnswer(
              invocation -> {
                afterInventory.run();
                return readinessFixture.currentInventory(dsl, invocation.getArgument(0));
              });
      var beans = new DefaultListableBeanFactory();
      beans.registerSingleton("controlledInventory", inventory);
      coordinator =
          new AccountJwtSignerBootstrapCoordinator(
              materializer,
              configMap,
              desired,
              prepublication,
              readiness,
              beans.getBeanProvider(AccountJwtValidatorInventorySource.class),
              transaction);
      var source =
          new AccountJwtJwksTrustedSource(configMap, materializer, desired, publication, manager);
      publicRead =
          new AccountPublicJwksReadService(
              source,
              new AccountPublicJwksReadWorkloadGuard(materializer, api, binding.namespace()));
      coordinator.enrollOnce();
    }

    private void generate() throws Exception {
      request = tx(() -> desired.ensureCurrentGenerationRequest(binding.accountBinding(), trust));
      String prior = result == null ? "12" : "14";
      String next = result == null ? "13" : "15";
      var observed =
          tx(
              () ->
                  desired.recordSecretObservation(
                      binding.accountBinding(),
                      trust,
                      request.operationId(),
                      request.operationDigest(),
                      SECRET_UID,
                      prior));
      keyPair = generateRsa3072();
      PublicJwk jwk = publicJwk(request.targetKid(), (RSAPublicKey) keyPair.getPublic());
      result =
          tx(
              () ->
                  desired.recordGenerationResult(
                      binding.accountBinding(),
                      trust,
                      request.operationId(),
                      observed.generationRequestDigest(),
                      SECRET_UID,
                      prior,
                      next,
                      jwk.json()));
      Files.writeString(
          privateMount.resolve("pending.key"), privateBundleJson(request, result, keyPair));
      readinessFixture =
          new AccountJwtPreparedPublicationPostgresFixture(
              binding.accountBinding(), trust, result, API_DIGEST, API_REVISION);
    }

    private void mountAndObserve() throws Exception {
      Files.writeString(publicMount.resolve("jwks.json"), transport.data.get("jwks.json"));
      Files.writeString(
          publicMount.resolve("jwt-generation.json"), transport.data.get("jwt-generation.json"));
      prepublication.observeCurrentMountedCorrespondence();
    }

    private void prepareAndRecordPrivatePromotion() throws Exception {
      readinessFixture.completeReadiness(readiness, transaction, dsl, privateMount, publicMount);
      assertThat(coordinator.prepareCurrentPromotionOnce()).isPresent();
      var prepared =
          tx(() -> desired.readAndMarkPreparedPromotionDispatched(binding.accountBinding(), trust))
              .promotion();
      privateObservation =
          new PrivatePromotionObservation(
              prepared.operationId(),
              prepared.requestDigest(),
              prepared.generationOperationId(),
              prepared.generationOperationDigest(),
              result.secretUid(),
              prepared.expectedPrivateResourceVersion(),
              "14",
              new PublicKeyIdentity(
                  prepared.targetGeneration(),
                  prepared.targetKid(),
                  prepared.targetPublicKeyFingerprint()),
              Optional.empty(),
              List.of("current"));
      privateReceipt =
          tx(
              () ->
                  desired.recordPrivatePromotionResult(
                      binding.accountBinding(), trust, privateObservation));
      Files.writeString(
          privateMount.resolve("current.key"), privateBundleJson(request, result, keyPair));
    }

    private void commitInitialSigner() throws Exception {
      generate();
      prepublication.prepublishCurrentGeneration();
      mountAndObserve();
      prepareAndRecordPrivatePromotion();
      reconcilePromotion();
      assertThat(read().committedRecoveryEligible()).isTrue();
    }

    private void reconcilePromotion() {
      coordinator.reconcilePreparedPromotionOnce(privateObservation, privateReceipt);
    }

    private ReadResult read() {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      var peer =
          GrpcPeerIdentity.parseUri("spiffe://firemud/ns/firemud-prod/sa/game-session-service")
              .orElseThrow();
      try {
        return Context.current()
            .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
            .call(
                () ->
                    AccountPublicJwksReadCodec.result(
                        publicRead.read(AccountPublicJwksReadCodec.request())));
      } catch (RuntimeException failure) {
        throw failure;
      } catch (Exception failure) {
        throw new IllegalStateException(failure);
      }
    }

    private void assertUnavailableRead() {
      assertThatThrownBy(this::read).isExactlyInstanceOf(SourceUnavailableException.class);
    }

    private void assertInvalidRead() {
      assertThatThrownBy(this::read).isExactlyInstanceOf(InvalidJwksException.class);
    }

    private void assertPreparedDeny() {
      ReadResult deny = read();
      assertThat(deny.preparedDeny()).isTrue();
      assertThat(deny.committedRecoveryEligible()).isFalse();
      assertThat(deny.jwksBytes()).isEmpty();
    }

    private void assertNoActiveReceipt() {
      assertThat(
              tx(() -> desired.readPreparedGenerationForRecovery(binding.accountBinding(), trust))
                  .promotion()
                  .activeJwksReceiptDigest())
          .isEmpty();
    }

    private void assertLiveMatchesCurrentPrepublicationIntent() {
      var evidence =
          tx(() -> publication.readCurrentPublication(binding.accountBinding(), trust))
              .orElseThrow();
      var intent = evidence.intent();
      ConfigMapSnapshot live = configMap.observe();
      assertThat(intent.operationId()).isEqualTo(result.operationId());
      assertThat(intent.operationDigest()).isEqualTo(result.operationDigest());
      assertThat(intent.generationReceiptDigest()).isEqualTo(result.receiptDigest());
      assertThat(intent.binding()).isEqualTo(binding.accountBinding());
      assertThat(intent.trustFence()).isEqualTo(trust);
      assertThat(intent.apiBindingDigest()).isEqualTo(API_DIGEST);
      assertThat(intent.apiConfigRevision()).isEqualTo(API_REVISION);
      assertThat(live.uid()).isEqualTo(intent.configMapUid());
      assertThat(live.resourceVersion()).isNotEqualTo(intent.expectedResourceVersion());
      assertThat(live.data().get("jwks.json")).isEqualTo(intent.jwksJson());
      assertThat(live.data().get("jwt-generation.json")).isEqualTo(intent.generationMarkerJson());
      assertThat(
              AccountJwtJwksPublicationRepository.publicDataDigest(
                  live.data().get("jwks.json"), live.data().get("jwt-generation.json")))
          .isEqualTo(intent.publicDataDigest());
    }

    private void assertLiveMatchesPreparedActiveIntent() {
      var promotion =
          tx(() -> desired.readPreparedGenerationForRecovery(binding.accountBinding(), trust))
              .promotion();
      var prepublished =
          tx(() -> publication.readPreparedPublicationForRecovery(binding.accountBinding(), trust));
      ConfigMapSnapshot live = configMap.observe();
      assertThat(promotion.generationOperationId()).isEqualTo(result.operationId());
      assertThat(promotion.generationOperationDigest()).isEqualTo(result.operationDigest());
      assertThat(promotion.generationReceiptDigest()).isEqualTo(result.receiptDigest());
      assertThat(promotion.binding()).isEqualTo(binding.accountBinding());
      assertThat(promotion.trustFence()).isEqualTo(trust);
      assertThat(promotion.apiBindingDigest()).isEqualTo(API_DIGEST);
      assertThat(promotion.apiConfigRevision()).isEqualTo(API_REVISION);
      assertThat(promotion.prepublicationIntentDigest())
          .isEqualTo(prepublished.intent().intentDigest());
      assertThat(promotion.prepublicationReceiptDigest())
          .isEqualTo(prepublished.receipt().receiptDigest());
      assertThat(promotion.mountedObservationDigest())
          .isEqualTo(prepublished.mountedCorrespondence().observationDigest());
      assertThat(promotion.expectedPublicResourceVersion())
          .isEqualTo(prepublished.receipt().observedResourceVersion());
      assertThat(live.uid()).isEqualTo(promotion.publicConfigMapUid());
      assertThat(live.resourceVersion()).isNotEqualTo(promotion.expectedPublicResourceVersion());
      assertThat(live.data().get("jwks.json")).isEqualTo(promotion.expectedPublicJwksJson());
      assertThat(live.data().get("jwt-generation.json"))
          .isEqualTo(promotion.expectedActiveMarkerJson());
      assertThat(
              AccountJwtJwksPublicationRepository.publicDataDigest(
                  live.data().get("jwks.json"), live.data().get("jwt-generation.json")))
          .isEqualTo(
              AccountJwtJwksPublicationRepository.publicDataDigest(
                  promotion.expectedPublicJwksJson(), promotion.expectedActiveMarkerJson()));
    }

    private void assertChangedPrivateReceiptReplayRefused() {
      Map<String, List<String>> retained = ownerHistoryImages();
      PrivatePromotionObservation changed =
          new PrivatePromotionObservation(
              privateObservation.promotionOperationId(),
              privateObservation.promotionRequestDigest(),
              privateObservation.generationOperationId(),
              privateObservation.generationOperationDigest(),
              privateObservation.secretUid(),
              privateObservation.expectedPriorResourceVersion(),
              privateObservation.observedResourceVersion() + "-changed",
              privateObservation.current(),
              privateObservation.previous(),
              privateObservation.resultingSlots());
      assertThatThrownBy(
              () ->
                  tx(
                      () ->
                          desired.recordPrivatePromotionResult(
                              binding.accountBinding(), trust, changed)))
          .isExactlyInstanceOf(
              AccountJwtSignerDesiredStateRepository.IdempotencyConflictException.class)
          .hasMessage("Private promotion readback changed for the same Account operation");
      assertThat(ownerHistoryImages()).isEqualTo(retained);
      assertThat(
              tx(
                  () ->
                      desired.recordPrivatePromotionResult(
                          binding.accountBinding(), trust, privateObservation)))
          .isEqualTo(privateReceipt);
      assertPreparedDeny();
    }

    private void assertCurrentMountedKeyCorrespondence() {
      var identity =
          new AccountMountedJwtSignerBundle.ExpectedIdentity(
              binding.environmentId(),
              binding.clusterId(),
              binding.namespace(),
              result.operationId().toString(),
              result.targetGeneration(),
              result.targetKid(),
              result.publicKeyFingerprint());
      assertThat(
              AccountMountedJwtSignerBundle.observeMounted(
                      privateMount,
                      Path.of("current.key"),
                      publicMount,
                      Path.of("jwks.json"),
                      identity)
                  .expectedIdentity())
          .isEqualTo(identity);
    }

    private AccountPublicJwksCache cache() {
      return cache(Clock.systemUTC(), Duration.ofMinutes(5));
    }

    private AccountPublicJwksCache cache(Clock clock, Duration maximumAge) {
      ReadResult initial = read();
      return new AccountPublicJwksCache(
          () -> {
            ReadResult current = read();
            if (current.preparedDeny()) throw new SourcePreparedDenyException();
            return new PublicJwksSnapshot(
                current.sourceIdentity(), current.jwksBytes(), current.committedRecoveryEligible());
          },
          initial.sourceIdentity(),
          clock,
          maximumAge);
    }

    private byte[] publicBytes() {
      return transport.data.get("jwks.json").getBytes(StandardCharsets.UTF_8);
    }

    private <T> T tx(Supplier<T> operation) {
      return transaction.execute(status -> operation.get());
    }

    /** The same public read waits on real owner locks and is awaited only after rollback. */
    private void observeBlockedPublicRead(
        Runnable assertAfterRollback, Runnable assertCommittedReceiptAbsent) {
      int writerPid =
          java.util.Objects.requireNonNull(
              dsl.resultQuery("SELECT pg_backend_pid()").fetchOne(0, Integer.class));
      AtomicInteger readerPid = new AtomicInteger();
      CountDownLatch readerBegan = new CountDownLatch(1);
      CompletableFuture<Void> reader =
          CompletableFuture.runAsync(
              () -> {
                manager.readerBackend.set(
                    pid -> {
                      if (readerPid.compareAndSet(0, pid)) readerBegan.countDown();
                    });
                try {
                  assertAfterRollback.run();
                } finally {
                  manager.readerBackend.remove();
                }
              });
      assertThat(blockedPublicRead.compareAndSet(null, reader)).isTrue();
      try {
        assertThat(readerBegan.await(5, TimeUnit.SECONDS)).isTrue();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Independent public read did not start", interrupted);
      }
      Awaitility.await()
          .alias("exact public reader waits on actual receipt writer")
          .atMost(Duration.ofSeconds(5))
          .until(
              () ->
                  independent(
                      () ->
                          Boolean.TRUE.equals(
                              dsl.resultQuery(
                                      "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid = ? "
                                          + "AND wait_event_type = 'Lock' AND ? = ANY(pg_blocking_pids(pid)))",
                                      readerPid.get(),
                                      writerPid)
                                  .fetchOne(0, Boolean.class))));
      assertThat(reader.isDone()).isFalse();
      independent(
          () -> {
            assertCommittedReceiptAbsent.run();
            return null;
          });
    }

    private void awaitBlockedPublicRead() {
      CompletableFuture<Void> reader = blockedPublicRead.getAndSet(null);
      assertThat(reader).isNotNull();
      try {
        reader.get(10, TimeUnit.SECONDS);
      } catch (Exception failure) {
        throw new IllegalStateException(
            "Same public reader did not reach its expected classification after rollback", failure);
      } finally {
        reader.cancel(true);
      }
    }

    private <T> T independent(Supplier<T> operation) {
      try {
        return CompletableFuture.supplyAsync(operation).get(10, TimeUnit.SECONDS);
      } catch (Exception failure) {
        throw new IllegalStateException("Independent public read failed", failure);
      }
    }

    /** Full retained owner/receipt row images plus MVCC identity; these are read-only witnesses. */
    private Map<String, List<String>> ownerHistoryImages() {
      Map<String, List<String>> images = new LinkedHashMap<>();
      for (String table :
          List.of(
              "account_jwt_signer_desired_states",
              "account_jwt_signer_generation_operations",
              "account_jwt_signer_secret_observations",
              "account_jwt_signer_generation_results",
              "account_jwt_signer_generation_abort_receipts",
              "account_jwt_signer_promotion_operations",
              "account_jwt_jwks_prepublication_intents",
              "account_jwt_jwks_publication_receipts",
              "account_jwt_jwks_mount_observations")) {
        images.put(
            table,
            dsl.resultQuery(
                    "SELECT history.xmin::text || ':' || row_to_json(history)::text "
                        + "FROM "
                        + table
                        + " history ORDER BY row_to_json(history)::text")
                .fetch(0, String.class));
      }
      return Map.copyOf(images);
    }

    private long count(String table) {
      return java.util.Objects.requireNonNull(
          dsl.resultQuery("SELECT COUNT(*) FROM " + table).fetchOne(0, Long.class));
    }
  }

  private static final class ControlledTransport
      implements AccountJwtJwksConfigMapClient.ApiSession {
    private final ParsedBinding binding;
    private Map<String, String> data =
        Map.of("jwks.json", "{\"keys\":[]}", "unrelated.txt", "preserve");
    private String version = "41";
    private int patches;
    private boolean unavailable;
    private final AtomicBoolean losePatchReply = new AtomicBoolean();
    private int lostPatchReplies;
    private Runnable projectPublishedData = () -> {};
    private Runnable beforePatch = () -> {};
    private Runnable afterPatch = () -> {};

    private ControlledTransport(ParsedBinding binding) {
      this.binding = binding;
    }

    @Override
    public ParsedBinding binding() {
      return binding;
    }

    @Override
    public ApiResponse send(ApiCall call, byte[] body) {
      try {
        if (unavailable && call == ApiCall.REVIEW_AUTHENTICATED_PRINCIPAL) {
          return response(503, Map.of());
        }
        return switch (call) {
          case REVIEW_AUTHENTICATED_PRINCIPAL ->
              response(
                  201,
                  Map.of(
                      "apiVersion",
                      "authentication.k8s.io/v1",
                      "kind",
                      "SelfSubjectReview",
                      "status",
                      Map.of("userInfo", Map.of("username", binding.expectedApiUsername()))));
          case READ_KUBE_SYSTEM_NAMESPACE, READ_TARGET_NAMESPACE ->
              response(
                  200,
                  Map.of(
                      "apiVersion",
                      "v1",
                      "kind",
                      "Namespace",
                      "metadata",
                      Map.of(
                          "name",
                              call == ApiCall.READ_KUBE_SYSTEM_NAMESPACE
                                  ? "kube-system"
                                  : binding.namespace(),
                          "uid",
                              call == ApiCall.READ_KUBE_SYSTEM_NAMESPACE
                                  ? CLUSTER_UID
                                  : NAMESPACE_UID),
                      "status",
                      Map.of("phase", "Active")));
          case READ_JWKS_CONFIG_MAP -> configMapResponse();
          case PATCH_JWKS_CONFIG_MAP -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            beforePatch.run();
            var patch = JSON.readTree(body);
            assertThat(patch.path("metadata").path("uid").asText()).isEqualTo(CONFIG_MAP_UID);
            assertThat(patch.path("metadata").path("resourceVersion").asText()).isEqualTo(version);
            assertThat(patch.path("data").size()).isEqualTo(2);
            assertThat(patch.path("data").has("jwks.json")).isTrue();
            assertThat(patch.path("data").has("jwt-generation.json")).isTrue();
            Map<String, String> changed = new LinkedHashMap<>(data);
            for (String key : List.of("jwks.json", "jwt-generation.json")) {
              changed.put(key, patch.path("data").path(key).asText());
            }
            data = Map.copyOf(changed);
            version = Integer.toString(Integer.parseInt(version) + 1);
            patches++;
            projectPublishedData.run();
            afterPatch.run();
            if (losePatchReply.getAndSet(false)) {
              lostPatchReplies++;
              throw new AccountJwtJwksApiBinding.ApiTransportException();
            }
            yield configMapResponse();
          }
          default -> throw new IllegalStateException("Unexpected controlled API endpoint");
        };
      } catch (RuntimeException failure) {
        throw failure;
      } catch (Exception failure) {
        throw new IllegalStateException(failure);
      }
    }

    private ApiResponse configMapResponse() throws Exception {
      return response(
          200,
          Map.of(
              "apiVersion",
              "v1",
              "kind",
              "ConfigMap",
              "metadata",
              Map.of(
                  "name",
                  "jwt-jwks",
                  "namespace",
                  binding.namespace(),
                  "uid",
                  CONFIG_MAP_UID,
                  "resourceVersion",
                  version),
              "data",
              data));
    }

    private static ApiResponse response(int status, Map<String, ?> value) throws Exception {
      return new ApiResponse(status, JSON.writeValueAsBytes(value));
    }

    private void clearHooks() {
      beforePatch = () -> {};
      afterPatch = () -> {};
    }

    @Override
    public void close() {}
  }

  private static String privateBundleJson(
      GenerationRequest request, GenerationResult result, KeyPair pair) {
    Map<String, Object> bundle = new LinkedHashMap<>();
    bundle.put("version", 1);
    bundle.put("environmentId", request.binding().environmentId());
    bundle.put("clusterId", request.binding().clusterId());
    bundle.put("namespace", request.binding().namespace());
    bundle.put("operationId", request.operationId().toString());
    bundle.put("generation", result.targetGeneration());
    bundle.put("kid", result.targetKid());
    bundle.put("algorithm", "RS256");
    bundle.put(
        "privateKeyPkcs8",
        Base64.getUrlEncoder().withoutPadding().encodeToString(pair.getPrivate().getEncoded()));
    bundle.put("publicKeyFingerprint", result.publicKeyFingerprint());
    try {
      return JSON.writeValueAsString(bundle);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private static PublicJwk publicJwk(String kid, RSAPublicKey key) throws Exception {
    String modulus = encodeUnsigned(key.getModulus());
    String exponent = encodeUnsigned(key.getPublicExponent());
    Map<String, Object> jwk = new LinkedHashMap<>();
    jwk.put("alg", "RS256");
    jwk.put("e", exponent);
    jwk.put("key_ops", List.of("verify"));
    jwk.put("kid", kid);
    jwk.put("kty", "RSA");
    jwk.put("n", modulus);
    jwk.put("use", "sig");
    String json = canonicalJson(jwk);
    Map<String, Object> withoutKeyOps = new LinkedHashMap<>(jwk);
    withoutKeyOps.remove("key_ops");
    String jsonWithoutKeyOps = canonicalJson(withoutKeyOps);
    String fingerprint =
        sha256(
            Rfc8785CanonicalJson.canonicalizeUtf8(
                JSON.writeValueAsString(Map.of("e", exponent, "kty", "RSA", "n", modulus))));
    return new PublicJwk(json, jsonWithoutKeyOps, fingerprint);
  }

  private static String canonicalJson(Map<String, Object> value) throws Exception {
    return new String(
        Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value)),
        StandardCharsets.UTF_8);
  }

  private static String encodeUnsigned(BigInteger value) {
    byte[] bytes = value.toByteArray();
    int offset = bytes.length > 1 && bytes[0] == 0 ? 1 : 0;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(java.util.Arrays.copyOfRange(bytes, offset, bytes.length));
  }

  private static KeyPair generateRsa3072() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3_072);
    return generator.generateKeyPair();
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is required for the persistence fixture", failure);
    }
  }

  private record PublicJwk(String json, String jsonWithoutKeyOps, String fingerprint) {}
}
