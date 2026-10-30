package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import io.grpc.Server;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.GameplayLoginSourceContext;
import net.firedevops.firemud.account.v1.GameplayLoginTransportClass;
import net.firedevops.firemud.accountservice.client.AccountOriginalNewFinalAdmissionGrpcReadClient;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.accountservice.dto.InitialGameplayLoginResult;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.CallerIdentity;
import net.firedevops.firemud.accountservice.repository.AccountOriginalNewBoundGameplayIssuanceRepository.Genesis;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionCallerVerifier;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionLeaseManualFactory;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient.ActiveRegistryReadback;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationTokenRegistry;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeKeyring;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec.OriginalNewCredential;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.client.AccountGameplayAdmissionLeaseClient;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayOriginalNewFinalAdmissionCompositionFixture;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manual actual-owner final NEW composition, not default registration or public gameplay proof.
 * Real Account recovered LOGIN issuance passes GS signature/aggregate verification and actual
 * journal/INITIAL owners; its initial response delivery is controlled, not an Authenticate network
 * test. Actual Account lease, credential and confirmation receivers, concrete bidirectional mTLS
 * clients, GS protected final reader, physical indexes/region/final CAS and both SQL owners
 * compose. Test PKI, Account signer/materializer/JWKS custody, upstream published target and
 * World/Entity responses, issuer checkpoint and signed incarnation observations remain controlled
 * inputs. Recreated coordinator/Account owners retain the actual original region owner's private
 * grant.
 */
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("resource")
class AccountGameSessionOriginalNewFinalAdmissionCompositionIntegrationTest {
  private static final String GS = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String ACCOUNT = "spiffe://firemud/ns/test/sa/account-service";
  private static final Clock CLOCK = Clock.systemUTC();
  private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
  private static final String FAULT_FUNCTION = "actual_final_test_fail_confirmation";
  private static final String FAULT_TRIGGER = "actual_final_test_confirmation_fault";
  private final AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest accountFixtures =
      new AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest();
  @TempDir Path temporaryDirectory;

  @BeforeAll
  static void startPostgres() {
    AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.startPostgres();
  }

  @AfterAll
  static void stopPostgres() {
    AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.stopPostgres();
  }

  @AfterEach
  void dropOwnedSchemas() {
    accountFixtures.dropRunOwnedSchemas();
  }

  @Test
  void actualCoordinatorConfirmsAndRecreatedOwnersRetryExactOriginalResult() throws Exception {
    try (Session session = session();
        OriginalNewCredential before = session.issue()) {
      byte[] handle = before.opaqueRebindHandle();
      try {
        var first = session.complete();
        Genesis confirmed = session.genesis();
        assertConfirmed(first, confirmed);
        assertThat(session.gs.finalReadCalls()).isOne();
        ActiveRegistryReadback active =
            session.registry.requireExactOriginalNewActiveRecord(confirmed);
        byte[] sessionBytes = session.gs.physicalSession();
        assertPhysicalSession(sessionBytes, first);
        String rows = session.durableImage();
        session.restartAccount();
        var retry = session.complete();
        assertThat(retry).isEqualTo(first);
        assertThat(MessageDigest.isEqual(session.gs.physicalSession(), sessionBytes)).isTrue();
        assertThat(session.durableImage()).isEqualTo(rows);
        try (OriginalNewCredential replay = session.issue()) {
          assertSameCredential(before, replay, handle);
        }
        assertSameRegistry(
            active, session.registry.requireExactOriginalNewActiveRecord(session.genesis()));
        assertThat(session.gs.finalReadCalls()).isOne();
      } finally {
        Arrays.fill(handle, (byte) 0);
      }
    }
  }

  @Test
  void actualPostActiveSqlConfirmationLossRecoversThroughFreshProtectedFinalRead()
      throws Exception {
    try (Session session = session();
        OriginalNewCredential before = session.issue()) {
      byte[] handle = before.opaqueRebindHandle();
      try {
        session.installConfirmationFault();
        try {
          assertThatThrownBy(session::complete).isInstanceOf(IllegalStateException.class);
        } finally {
          session.removeConfirmationFault();
        }
        Genesis unconfirmed = session.genesis();
        assertThat(unconfirmed.confirmedAdmissionAtMillis()).isNull();
        assertThat(unconfirmed.confirmedContinuityExpiresAtMillis()).isNull();
        assertThat(unconfirmed.confirmationReadbackSha256()).isNull();
        assertThat(unconfirmed.tokenHash()).isEqualTo(before.tokenSha256());
        ActiveRegistryReadback active =
            session.registry.requireExactOriginalNewActiveRecord(unconfirmed);
        assertThat(session.gs.finalReadCalls()).isOne();
        byte[] committedSession = session.gs.physicalSession();
        var physical =
            tools.jackson.databind.json.JsonMapper.builder().build().readTree(committedSession);
        assertThat(physical.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(physical.path("admissionState").asText()).isEqualTo("COMPLETE");
        String gsReceipt = session.gsReceiptImage();
        session.restartAccount();
        var recovered = session.complete();
        assertConfirmed(recovered, session.genesis());
        assertThat(session.gs.finalReadCalls()).isEqualTo(2);
        assertThat(MessageDigest.isEqual(session.gs.physicalSession(), committedSession)).isTrue();
        assertThat(session.gsReceiptImage()).isEqualTo(gsReceipt);
        assertSameRegistry(
            active, session.registry.requireExactOriginalNewActiveRecord(session.genesis()));
        try (OriginalNewCredential replay = session.issue()) {
          assertSameCredential(before, replay, handle);
        }
        String confirmed = session.durableImage();
        assertThat(session.complete()).isEqualTo(recovered);
        assertThat(session.durableImage()).isEqualTo(confirmed);
        assertThat(session.gs.finalReadCalls()).isEqualTo(2);
      } finally {
        Arrays.fill(handle, (byte) 0);
      }
    }
  }

  @Test
  void missingActualInitialBarrierRefusesWithoutRecreatingOpenOrAdmittingCandidate()
      throws Exception {
    try (Session session = session();
        OriginalNewCredential before = session.issue()) {
      byte[] candidate = session.gs.physicalSession();
      session.gs.removeInitialBarrierForFault();
      String beforeRows = session.durableImage();
      assertThatThrownBy(session::complete).isInstanceOf(IllegalStateException.class);
      assertThat(session.gs.initialBarrierAbsent()).isTrue();
      assertThat(MessageDigest.isEqual(session.gs.physicalSession(), candidate)).isTrue();
      assertThat(session.durableImage()).isEqualTo(beforeRows);
      assertThat(session.gs.finalReadCalls()).isZero();
      assertThat(session.genesis().tokenHash()).isEqualTo(before.tokenSha256());
      assertThat(session.genesis().confirmedAdmissionAtMillis()).isNull();
      assertThat(
              session
                  .gs
                  .dsl()
                  .fetchSingle(
                      "SELECT status FROM game_session_canonical_binding_transition WHERE transition_id = ?",
                      session.gs.decisionId())
                  .get(0, String.class))
          .isEqualTo("PROVISIONAL");
    }
  }

  private Session session() throws Exception {
    accountFixtures.temporaryDirectory = temporaryDirectory;
    return new Session();
  }

  private final class Session implements AutoCloseable {
    private final AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.PhysicalRedisFixture
        redis =
            AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.PhysicalRedisFixture
                .start();
    private AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.Fixture account;
    private CanonicalGameplayOriginalNewFinalAdmissionCompositionFixture gs;
    private CanonicalGameplayAdmissionDecisionReadClient reverse;
    private AccountOriginalNewFinalAdmissionGrpcReadClient finalRead;
    private AccountGameplayAdmissionLeaseClient client;
    private AccountGameplayDelegationTokenRegistry registry;
    private AccountResponseEnvelopeCryptography responseCrypto;
    private DSLContext dsl;
    private TransactionTemplate serializable;
    private Server accountServer;
    private LoopbackMtlsTestPki.Pki pki;
    private Path pkiDirectory;
    private AccountPublicJwksCache jwks;
    private String jwt;
    private AccountGameplayAdmissionLeaseEvidence lease;

    private Session() throws Exception {
      try {
        account = accountFixtures.newFixture(redis.client());
        dsl =
            DSL.using(new TransactionAwareDataSourceProxy(account.dataSource), SQLDialect.POSTGRES);
        var manager = new DataSourceTransactionManager(account.dataSource);
        serializable = new TransactionTemplate(manager);
        serializable.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        var generations = new AccountAuthorityGenerationRepository(dsl);
        var outbox = new AccountAuthorityOutboxRepository(dsl);
        var source = new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
        var bundles = new AccountAuthEvidenceBundleRepository(dsl, generations, outbox);
        registry =
            new AccountGameplayDelegationTokenRegistry(
                new AccountGameplayDelegationIssuanceRepository(dsl, source, bundles),
                manager,
                redis.client(),
                CLOCK,
                GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES,
                30_000L);
        responseCrypto =
            responseCryptography(
                temporaryDirectory.resolve("actual-final-key-" + UUID.randomUUID()));
        var identity = account.committedCandidate.identity();
        var recovered =
            account.responseRecoveryOwner.recoverInitialLoginResponse(
                identity.requestId(),
                identity.accountId(),
                new CallerIdentity(identity.callerWorkload(), identity.callerContextId()));
        var initial = InitialGameplayLoginResult.fromRecoveredCredential(recovered);
        byte[] token = initial.compactJwtBytes();
        try {
          jwt = new String(token, StandardCharsets.US_ASCII);
        } finally {
          Arrays.fill(token, (byte) 0);
        }
        var trusted = account.signerFixture.trustedJwksSource();
        jwks =
            new AccountPublicJwksCache(
                trusted, trusted.sourceIdentity(), CLOCK, Duration.ofSeconds(30));
        var request =
            AuthenticateRequest.newBuilder()
                .setRequestId(initial.requestId().toString())
                .setEmail("controlled-initial-delivery@example.test")
                .setPassword("controlled-input")
                .setSourceContext(
                    GameplayLoginSourceContext.newBuilder()
                        .setCallerContextId(initial.callerContextId().toString())
                        .setCanonicalClientAddress("203.0.113.10")
                        .setTransportClass(
                            GameplayLoginTransportClass
                                .GAMEPLAY_LOGIN_TRANSPORT_CLASS_TRUSTED_TCP_PROXY))
                .build();
        // Controlled initial delivery only: all response fields derive from real recovered owner
        // bytes. GS independently verifies signature and the complete aggregate before journaling.
        try (var validated =
            CanonicalGameplayLoginResult.fromAuthenticatedResponse(
                projectRecoveredInitial(initial),
                request,
                new AccountAsymmetricJwtVerifier(jwks, CLOCK),
                CLOCK)) {
          gs =
              CanonicalGameplayOriginalNewFinalAdmissionCompositionFixture.open(
                  account.dataSource,
                  AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.admissionCandidate(
                      account),
                  validated,
                  Files.createDirectory(
                      temporaryDirectory.resolve("actual-final-sources-" + UUID.randomUUID())));
        }
        pkiDirectory =
            Files.createDirectory(
                temporaryDirectory.resolve("actual-final-pki-" + UUID.randomUUID()));
        pki =
            LoopbackMtlsTestPki.create(
                pkiDirectory,
                "Actual final composition test CA",
                new LoopbackMtlsTestPki.IdentitySpec("account", ACCOUNT, true),
                new LoopbackMtlsTestPki.IdentitySpec("gs", GS, true),
                new LoopbackMtlsTestPki.IdentitySpec("account-client", ACCOUNT, false),
                new LoopbackMtlsTestPki.IdentitySpec("gs-client", GS, false));
        UUID requestId = UUID.randomUUID();
        var gsServer =
            gs.startServer(
                pki.keyManagerFactory(pki.identity("gs")), pki.trustManagerFactory(), requestId);
        var endpoints = new ServiceEndpointsProperties();
        endpoints.setGameSessionService("localhost:" + gsServer.getPort());
        var accountTls = pki.clientProperties(pkiDirectory, pki.identity("account-client"));
        reverse =
            new CanonicalGameplayAdmissionDecisionReadClient(
                endpoints, accountTls, new GrpcChannelFactory(), "test");
        reverse.init();
        finalRead =
            new AccountOriginalNewFinalAdmissionGrpcReadClient(
                endpoints, accountTls, new GrpcChannelFactory(), "test");
        finalRead.init();
        restartAccount();
        // All containers, keys, source servers, and INITIAL setup precede the <=15-second lease.
        lease =
            client
                .acquire(requestId, jwt, gs.candidate(), gs.target(), gs.generation())
                .originalLeaseEvidence();
        gs.installActualCandidate(lease);
        var finalized = client.finalizeLease(jwt, leaseBytes(), gs.decisionId());
        assertThat(finalized.state())
            .isEqualTo(
                net.firedevops.firemud.common.account.admission
                    .AccountGameplayAdmissionLeaseGrpcCodec.State.COMMITTED);
        assertThat(finalized.originalLeaseEvidence().canonicalJson())
            .isEqualTo(lease.canonicalJson());
      } catch (Exception | Error failure) {
        try {
          close();
        } catch (Exception cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    }

    private void restartAccount() throws Exception {
      if (client != null) client.close();
      if (accountServer != null) {
        accountServer.shutdownNow();
        if (!accountServer.awaitTermination(5, TimeUnit.SECONDS))
          throw new IllegalStateException("Account teardown timed out");
      }
      var verifier =
          new AccountGameplayAdmissionCallerVerifier(
              account.signerFixture.trustedJwksSource(), account.committedIssuanceOwner, CLOCK);
      var leaseOwner =
          new AccountGameplayAdmissionLeaseOwner(verifier, reverse, "test", account.dataSource);
      var credentialOwner =
          new AccountOriginalNewBoundGameplayCredentialOwner(
              account.dataSource,
              "test",
              verifier,
              account.signerFixture.signer(),
              registry,
              responseCrypto,
              CLOCK,
              finalRead);
      accountServer =
          AccountGameplayAdmissionLeaseManualFactory.createUnregisteredServer(
              "127.0.0.1:0",
              pki.keyManagerFactory(pki.identity("account")),
              pki.trustManagerFactory(),
              leaseOwner,
              credentialOwner,
              "test");
      accountServer.start();
      var endpoints = new ServiceEndpointsProperties();
      endpoints.setAccountService("localhost:" + accountServer.getPort());
      client =
          new AccountGameplayAdmissionLeaseClient(
              endpoints,
              pki.clientProperties(pkiDirectory, pki.identity("gs-client")),
              new GrpcChannelFactory(),
              "test");
      client.init();
    }

    private byte[] leaseBytes() {
      return lease.canonicalJson().getBytes(StandardCharsets.UTF_8);
    }

    private OriginalNewCredential issue() {
      return client.issueOriginalNewBoundGameplayCredential(jwt, leaseBytes());
    }

    private CanonicalGameplayOriginalNewFinalAdmissionCompositionFixture.Receipt complete() {
      return gs.complete(client, jwks, jwt, System.currentTimeMillis() + 10_000L);
    }

    private Genesis genesis() {
      return serializable.execute(
          status ->
              new AccountOriginalNewBoundGameplayIssuanceRepository(dsl)
                  .readByLease(UUID.fromString((String) lease.carrier().get("leaseId"))));
    }

    private String durableImage() {
      return gsReceiptImage()
          + dsl.fetch(
                  "SELECT to_jsonb(t)::text AS image FROM account_original_new_bound_gameplay_issuances t ORDER BY 1")
              .getValues("image", String.class);
    }

    private String gsReceiptImage() {
      return gs.dsl()
          .fetch(
              "SELECT to_jsonb(t)::text AS image FROM game_session_canonical_binding_transition t ORDER BY 1")
          .getValues("image", String.class)
          .toString();
    }

    private void installConfirmationFault() {
      dsl.execute(
          "CREATE FUNCTION "
              + FAULT_FUNCTION
              + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
              + "RAISE EXCEPTION 'controlled actual final confirmation SQL loss'; RETURN NEW; END; $$");
      dsl.execute(
          "CREATE TRIGGER "
              + FAULT_TRIGGER
              + " BEFORE UPDATE ON account_original_new_bound_gameplay_issuances "
              + "FOR EACH ROW WHEN (OLD.confirmed_admission_at_ms IS NULL AND NEW.confirmed_admission_at_ms IS NOT NULL) "
              + "EXECUTE FUNCTION "
              + FAULT_FUNCTION
              + "()");
    }

    private void removeConfirmationFault() {
      dsl.execute(
          "DROP TRIGGER " + FAULT_TRIGGER + " ON account_original_new_bound_gameplay_issuances");
      dsl.execute("DROP FUNCTION " + FAULT_FUNCTION + "()");
    }

    @Override
    public void close() throws Exception {
      try {
        if (client != null) client.close();
      } finally {
        try {
          if (accountServer != null) {
            accountServer.shutdownNow();
            if (!accountServer.awaitTermination(5, TimeUnit.SECONDS))
              throw new IllegalStateException("Account teardown timed out");
          }
        } finally {
          try {
            if (finalRead != null) finalRead.close();
          } finally {
            try {
              if (reverse != null) reverse.close();
            } finally {
              try {
                if (gs != null) gs.close();
              } finally {
                redis.close();
              }
            }
          }
        }
      }
    }
  }

  private static void assertPhysicalSession(
      byte[] bytes, CanonicalGameplayOriginalNewFinalAdmissionCompositionFixture.Receipt result)
      throws Exception {
    var value = tools.jackson.databind.json.JsonMapper.builder().build().readTree(bytes);
    assertThat(value.path("schemaVersion").asInt()).isEqualTo(2);
    assertThat(value.path("admissionState").asText()).isEqualTo("COMPLETE");
    assertThat(value.path("admissionAt").asLong()).isEqualTo(result.admissionAtMillis());
    assertThat(value.path("continuityBindingExpiresAt").asLong())
        .isEqualTo(result.continuityExpiresAtMillis());
  }

  private static void assertConfirmed(
      CanonicalGameplayOriginalNewFinalAdmissionCompositionFixture.Receipt result,
      Genesis genesis) {
    assertThat(genesis.confirmedAdmissionAtMillis()).isEqualTo(result.admissionAtMillis());
    assertThat(genesis.confirmedContinuityExpiresAtMillis())
        .isEqualTo(result.continuityExpiresAtMillis());
    // Account seals a different domain: final readback plus its ACTIVE registry proof.
    assertThat(genesis.confirmationReadbackSha256())
        .matches("[0-9a-f]{64}")
        .isNotEqualTo(result.finalSessionSha256());
    assertThat(result.continuityExpiresAtMillis() - result.admissionAtMillis()).isEqualTo(120_000L);
  }

  private static void assertSameCredential(
      OriginalNewCredential expected, OriginalNewCredential actual, byte[] originalHandle) {
    byte[] replayHandle = actual.opaqueRebindHandle();
    try {
      assertThat(MessageDigest.isEqual(originalHandle, replayHandle)).isTrue();
    } finally {
      Arrays.fill(replayHandle, (byte) 0);
    }
    assertThat(actual.signedBoundGsACompactJwt().equals(expected.signedBoundGsACompactJwt()))
        .isTrue();
    assertThat(actual.tokenSha256()).isEqualTo(expected.tokenSha256());
    assertThat(actual.tokenJti()).isEqualTo(expected.tokenJti());
    assertThat(actual.lineageId()).isEqualTo(expected.lineageId());
    assertThat(actual.issuanceFence()).isEqualTo(expected.issuanceFence());
    assertThat(actual.originalAckReceiptXid()).isEqualTo(expected.originalAckReceiptXid());
  }

  private static void assertSameRegistry(
      ActiveRegistryReadback expected, ActiveRegistryReadback actual) {
    assertThat(actual.tokenHash()).isEqualTo(expected.tokenHash());
    assertThat(actual.accountId()).isEqualTo(expected.accountId());
    assertThat(actual.jti()).isEqualTo(expected.jti());
    assertThat(actual.tokenExpiryEpochSecond()).isEqualTo(expected.tokenExpiryEpochSecond());
    assertThat(actual.absoluteExpiryMillis()).isEqualTo(expected.absoluteExpiryMillis());
    assertThat(actual.canonicalPendingRecordSha256())
        .isEqualTo(expected.canonicalPendingRecordSha256());
  }

  private static AuthenticateResponse projectRecoveredInitial(InitialGameplayLoginResult result) {
    byte[] token = result.compactJwtBytes();
    try {
      return AuthenticateResponse.newBuilder()
          .setAuthToken(new String(token, StandardCharsets.US_ASCII))
          .setAccountId(result.accountId().toString())
          .setRequestId(result.requestId().toString())
          .setCallerContextId(result.callerContextId().toString())
          .setTokenJti(result.tokenJti().toString())
          .setTokenSha256(result.tokenSha256())
          .setTokenProfile(result.profile())
          .setIssuedAtEpochSeconds(result.issuedAtEpochSecond())
          .setNotBeforeEpochSeconds(result.notBeforeEpochSecond())
          .setExpiresAtEpochSeconds(result.expiresAtEpochSecond())
          .setTokenGeneration(result.tokenGeneration())
          .setIssuanceFence(result.issuanceFence())
          .setAuthorityTupleCanonicalJson(ByteString.copyFrom(result.authorityTupleCanonicalJson()))
          .setOutboxCheckpointsCanonicalJson(
              ByteString.copyFrom(result.outboxCheckpointsCanonicalJson()))
          .setOutboxSourceEventEvidenceCanonicalJson(
              ByteString.copyFrom(result.outboxSourceEventEvidenceCanonicalJson()))
          .build();
    } finally {
      Arrays.fill(token, (byte) 0);
    }
  }

  private static AccountResponseEnvelopeCryptography responseCryptography(Path directory)
      throws Exception {
    Files.createDirectories(directory);
    byte[] key = new byte[32];
    RANDOM.nextBytes(key);
    try {
      Files.writeString(
          directory.resolve("keyring"),
          "firemud-account-response-envelope-keyring-v1\nactive integration-key "
              + Base64.getUrlEncoder().withoutPadding().encodeToString(key)
              + "\n",
          StandardCharsets.US_ASCII);
      return new AccountResponseEnvelopeCryptography(
          new AccountResponseEnvelopeKeyring(directory.toString()));
    } finally {
      Arrays.fill(key, (byte) 0);
    }
  }
}
