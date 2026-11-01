package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import io.grpc.Server;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.accountservice.repository.AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.PhysicalRedisFixture;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.CallerIdentity;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionCallerVerifier;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionLeaseManualFactory;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionAbortOutcome;
import net.firedevops.firemud.gamesession.client.AccountGameplayAdmissionLeaseClient;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayAdmissionAbortCompositionFixture;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayAdmissionAbortManualFactory;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

/**
 * Actual Account acquisition/current caller/physical token and abort owner, real GS durable
 * PROVISIONAL reverse read and guarded abort CAS, physical PostgreSQL/Redis and manual mTLS.
 * Launch/publication/initial-target and test PKI inputs remain controlled. The physical issuer
 * proof is binding-local only; it does not prove runtime registration, admission, production PKI,
 * or completion of the remaining Account and region terminal obligations. The never-admitted cases
 * use actual dispatch claims, qualified closure and both directions of mTLS without a positive
 * decision/ACK. External reset/restore/promotion exclusion is a controlled fixture input, not proof
 * of a production qualification provider.
 */
abstract class AccountGameSessionAbortCompositionFixture {
  private static final String GS = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String ACCOUNT = "spiffe://firemud/ns/test/sa/account-service";
  final AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest accountFixtures;
  @TempDir Path temporaryDirectory;

  AccountGameSessionAbortCompositionFixture(AccountPostgresIntegrationFixture postgres) {
    accountFixtures =
        AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.withPostgres(postgres);
  }

  @AfterEach
  void cleanupAccountSchemas() {
    accountFixtures.dropRunOwnedSchemas();
  }

  Session open() throws Exception {
    accountFixtures.temporaryDirectory = temporaryDirectory;
    return new Session(false);
  }

  Session open(boolean prepareMemberRepair) throws Exception {
    accountFixtures.temporaryDirectory = temporaryDirectory;
    return new Session(prepareMemberRepair);
  }

  Session openWithWrongPeerIdentity() throws Exception {
    accountFixtures.temporaryDirectory = temporaryDirectory;
    return new Session(false, false, false, true);
  }

  Session openNeverAdmitted() throws Exception {
    return openNeverAdmitted(false);
  }

  Session openNeverAdmitted(boolean installedBootstrap) throws Exception {
    accountFixtures.temporaryDirectory = temporaryDirectory;
    return new Session(false, true, installedBootstrap);
  }

  final class Session implements AutoCloseable {
    private final PhysicalRedisFixture redis = PhysicalRedisFixture.start();
    final AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.Fixture accountFixture;
    final CanonicalGameplayAdmissionAbortCompositionFixture gs;
    final LoopbackMtlsTestPki.Pki pki;
    final Path pkiDirectory;
    final String jwt;
    final CanonicalGameplayAdmissionDecisionReadClient reverse;
    private final AccountGameplayAdmissionCallerVerifier verifier;
    Server gsServer;
    private Server accountServer;
    AccountGameplayAdmissionLeaseClient client;
    AccountGameplayAdmissionLeaseEvidence lease;

    private Session(boolean prepareMemberRepair) throws Exception {
      this(prepareMemberRepair, false);
    }

    private Session(boolean prepareMemberRepair, boolean claimedNeverAdmitted) throws Exception {
      this(prepareMemberRepair, claimedNeverAdmitted, false);
    }

    private Session(
        boolean prepareMemberRepair, boolean claimedNeverAdmitted, boolean installedBootstrap)
        throws Exception {
      this(prepareMemberRepair, claimedNeverAdmitted, installedBootstrap, false);
    }

    private Session(
        boolean prepareMemberRepair,
        boolean claimedNeverAdmitted,
        boolean installedBootstrap,
        boolean includeWrongPeerIdentity)
        throws Exception {
      try {
        accountFixture = accountFixtures.newFixture(redis.client());
        var issuance = accountFixture.committedCandidate.identity();
        var credential =
            accountFixture.responseRecoveryOwner.recoverInitialLoginResponse(
                issuance.requestId(),
                issuance.accountId(),
                new CallerIdentity(issuance.callerWorkload(), issuance.callerContextId()));
        jwt = new String(credential.compactJwtBytes(), StandardCharsets.US_ASCII);
        Candidate candidate =
            AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.admissionCandidate(
                accountFixture);
        Candidate capacityCandidate =
            new Candidate(
                candidate.accountId(),
                candidate.tenantId(),
                candidate.playableStateNamespaceId(),
                candidate.characterId(),
                candidate.playableStateScope(),
                candidate.gameInstanceId(),
                candidate.runtimeGameInstanceId(),
                candidate.sessionId(),
                candidate.regionId(),
                candidate.regionEpoch(),
                candidate.issuerId(),
                candidate.issuerAuthGeneration(),
                candidate.issuerIndexLayoutVersion(),
                candidate.issuerIndexPartitionCount(),
                java.math.BigInteger.valueOf(prepareMemberRepair || installedBootstrap ? 3 : 2));
        gs =
            new CanonicalGameplayAdmissionAbortCompositionFixture(
                accountFixture.dataSource,
                capacityCandidate,
                redis.issuerOwnerClient(),
                installedBootstrap);
        if (prepareMemberRepair) gs.prepareMemberRepairRedis();
        pkiDirectory = Files.createTempDirectory(temporaryDirectory, "actual-abort-mtls-");
        var identities =
            new ArrayList<>(
                List.of(
                    new LoopbackMtlsTestPki.IdentitySpec("account", ACCOUNT, true),
                    new LoopbackMtlsTestPki.IdentitySpec("gs", GS, true),
                    new LoopbackMtlsTestPki.IdentitySpec("gs-client", GS, false),
                    new LoopbackMtlsTestPki.IdentitySpec("account-client", ACCOUNT, false)));
        if (includeWrongPeerIdentity) {
          identities.add(
              new LoopbackMtlsTestPki.IdentitySpec(
                  "wrong", "spiffe://firemud/ns/test/sa/world-management-service", false));
        }
        pki =
            LoopbackMtlsTestPki.create(
                pkiDirectory,
                "Account GS abort test CA",
                identities.toArray(LoopbackMtlsTestPki.IdentitySpec[]::new));
        if (claimedNeverAdmitted) {
          gsServer =
              CanonicalGameplayAdmissionAbortManualFactory
                  .createUnregisteredNegativeDecisionReadServer(
                      "127.0.0.1:0",
                      pki.keyManagerFactory(pki.identity("gs")),
                      pki.trustManagerFactory(),
                      gs.dsl(),
                      "test",
                      gs.negativeClosure());
        } else {
          gsServer =
              CanonicalGameplayAdmissionAbortManualFactory.createUnregisteredDecisionReadServer(
                  "127.0.0.1:0",
                  pki.keyManagerFactory(pki.identity("gs")),
                  pki.trustManagerFactory(),
                  gs.dsl(),
                  "test");
        }
        gsServer.start();
        var endpoints = new ServiceEndpointsProperties();
        endpoints.setGameSessionService("127.0.0.1:" + gsServer.getPort());
        reverse =
            spy(
                new CanonicalGameplayAdmissionDecisionReadClient(
                    endpoints,
                    pki.clientProperties(pkiDirectory, pki.identity("account-client")),
                    new GrpcChannelFactory(),
                    "test"));
        // Only published-target authority is controlled. read(decisionId, originalLease) uses the
        // actual mTLS receiver/repository; it is never stubbed or replaced by DTO equality.
        reverse.init();
        UUID requestId = UUID.randomUUID();
        doReturn(
                AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest
                    .controlledPublishedTarget(gs.candidate(), gs.target(), requestId))
            .when(reverse)
            .readPublishedRealmAdmissionOwner(any());
        verifier =
            new AccountGameplayAdmissionCallerVerifier(
                accountFixture.signerFixture.trustedJwksSource(),
                accountFixture.committedIssuanceOwner,
                Clock.systemUTC());
        restartAccount();
        lease =
            client
                .acquire(requestId, jwt, gs.candidate(), gs.target(), gs.generation())
                .originalLeaseEvidence();
        if (claimedNeverAdmitted) gs.claimOriginalNewCandidateWithoutDispatch(lease);
        else gs.publish(lease);
      } catch (Exception | Error setupFailure) {
        try {
          close();
        } catch (Exception cleanupFailure) {
          setupFailure.addSuppressed(cleanupFailure);
        }
        throw setupFailure;
      }
    }

    void restartAccount() throws Exception {
      restartAccount(verifier);
    }

    void restartAccountAfterPresentedCallerExpiry() throws Exception {
      var expiredClock =
          Clock.fixed(
              Instant.ofEpochSecond(
                  accountFixture.committedCandidate.identity().expiresAtEpochSecond() + 6L),
              ZoneOffset.UTC);
      var expiredVerifier =
          new AccountGameplayAdmissionCallerVerifier(
              accountFixture.signerFixture.trustedJwksSource(),
              accountFixture.committedIssuanceOwner,
              expiredClock);
      assertThatThrownBy(() -> expiredVerifier.verifyPresentedCompactToken(jwt))
          .isInstanceOf(IllegalStateException.class);
      restartAccount(expiredVerifier);
    }

    private void restartAccount(AccountGameplayAdmissionCallerVerifier receivingVerifier)
        throws Exception {
      if (client != null) client.close();
      if (accountServer != null) {
        accountServer.shutdownNow();
        if (!accountServer.awaitTermination(5, TimeUnit.SECONDS))
          throw new IllegalStateException("Account server teardown timed out");
      }
      var owner =
          new AccountGameplayAdmissionLeaseOwner(
              receivingVerifier, reverse, "test", accountFixture.dataSource);
      accountServer =
          AccountGameplayAdmissionLeaseManualFactory.createUnregisteredServer(
              "127.0.0.1:0",
              pki.keyManagerFactory(pki.identity("account")),
              pki.trustManagerFactory(),
              owner,
              "test");
      accountServer.start();
      var endpoints = new ServiceEndpointsProperties();
      endpoints.setAccountService("127.0.0.1:" + accountServer.getPort());
      client =
          new AccountGameplayAdmissionLeaseClient(
              endpoints,
              pki.clientProperties(pkiDirectory, pki.identity("gs-client")),
              new GrpcChannelFactory(),
              "test");
      client.init();
    }

    String image() {
      return java.util.List.of(
              "game_session_canonical_binding_transition",
              "game_session_canonical_gameplay_binding_inventory",
              "game_session_canonical_issuer_partition_reservation",
              "game_session_canonical_binding_issuer_index_obligation",
              "game_session_canonical_issuer_binding_local_operation",
              "game_session_canonical_issuer_binding_local_release_operation")
          .stream()
          .map(
              table ->
                  gs.dsl()
                      .fetch("SELECT to_jsonb(t)::text AS image FROM " + table + " t ORDER BY 1")
                      .getValues("image")
                      .toString())
          .collect(java.util.stream.Collectors.joining("\n"));
    }

    String issuerReleaseIdentityImage() {
      return gs.dsl()
          .fetchSingle(
              "SELECT (to_jsonb(release) - ARRAY['status', 'completion_inventory_revision',"
                  + " 'physical_readback_field_set_bytes', 'physical_readback_field_set_digest', 'completed_at'])::text"
                  + " FROM game_session_canonical_issuer_binding_local_release_operation release WHERE transition_id = ?",
              gs.transitionId())
          .get(0, String.class);
    }

    String issuerCleanupHistoryImage() {
      return java.util.List.of(
              "game_session_canonical_issuer_binding_local_operation",
              "game_session_canonical_issuer_bootstrap_member_operation",
              "game_session_canonical_issuer_bootstrap_partition_proof",
              "game_session_canonical_issuer_binding_local_release_operation",
              "game_session_canonical_issuer_binding_local_retirement",
              "game_session_canonical_issuer_partition_mutation")
          .stream()
          .map(
              table ->
                  gs.dsl()
                      .fetch(
                          "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text AS image FROM "
                              + table
                              + " t ORDER BY 1")
                      .getValues("image")
                      .toString())
          .collect(java.util.stream.Collectors.joining("\n"));
    }

    void assertNeverAdmittedExactRetryPreservesState() {
      String terminal = image();
      String history = issuerCleanupHistoryImage();
      String current = completedNegativeCleanupCurrentImage();
      String account = accountOperationImage();
      String resolution = negativeResolutionImage();
      String fence = gs.negativeFence();
      byte[] fields = gs.issuerPartitionFieldSetBytes();
      assertThat(abortNeverAdmitted().cleanupComplete()).isTrue();
      gs.assertCompletedNegativeReleaseAndRetirementReplay();
      assertThat(image()).isEqualTo(terminal);
      assertThat(issuerCleanupHistoryImage()).isEqualTo(history);
      assertThat(completedNegativeCleanupCurrentImage()).isEqualTo(current);
      assertThat(accountOperationImage()).isEqualTo(account);
      assertThat(negativeResolutionImage()).isEqualTo(resolution);
      assertThat(gs.negativeFence()).isEqualTo(fence);
      assertThat(gs.issuerPartitionFieldSetBytes()).containsExactly(fields);
      assertThat(gs.heldClosureQualifications()).isZero();
    }

    String completedNegativeCleanupCurrentImage() {
      return java.util.List.of(
              "game_session_canonical_binding_transition",
              "game_session_canonical_gameplay_binding_inventory",
              "game_session_canonical_issuer_partition_reservation",
              "game_session_canonical_binding_account_index_obligation",
              "game_session_canonical_binding_issuer_index_obligation",
              "game_session_canonical_binding_region_bridge_obligation",
              "game_session_canonical_issuer_current_local_operation",
              "game_session_canonical_issuer_partition_mutation_head",
              "game_session_canonical_binding_inventory_clock")
          .stream()
          .map(
              table ->
                  gs.dsl()
                      .fetch(
                          "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text AS image FROM "
                              + table
                              + " t ORDER BY 1")
                      .getValues("image")
                      .toString())
          .collect(java.util.stream.Collectors.joining("\n"));
    }

    byte[] leaseBytes() {
      return lease.canonicalJson().getBytes(StandardCharsets.UTF_8);
    }

    net.firedevops.firemud.gamesession.repository.CanonicalGameplayOriginalNewAbandonmentSource
        abortNeverAdmitted() {
      return gs.neverAdmittedOwner(client).abort(gs.transitionId(), jwt);
    }

    String accountOperationImage() {
      return DSL.using(accountFixture.dataSource, SQLDialect.POSTGRES)
          .fetchSingle(
              "SELECT to_jsonb(operation)::text FROM account_gameplay_admission_lease_operations operation WHERE lease_id = ?",
              UUID.fromString((String) lease.carrier().get("leaseId")))
          .get(0, String.class);
    }

    String negativeResolutionImage() {
      return DSL.using(accountFixture.dataSource, SQLDialect.POSTGRES)
          .fetchSingle(
              "SELECT to_jsonb(resolution)::text FROM account_gameplay_admission_abort_resolutions resolution WHERE original_lease_id = ?",
              UUID.fromString((String) lease.carrier().get("leaseId")))
          .get(0, String.class);
    }

    CanonicalGameplayAdmissionAbortOutcome abort() {
      return abortWith(jwt);
    }

    CanonicalGameplayAdmissionAbortOutcome abortWith(String callerToken) {
      return CanonicalGameplayAdmissionAbortManualFactory.createUnregisteredOwner(gs.dsl(), client)
          .abortFirstNew(gs.transitionId(), callerToken);
    }

    @Override
    public void close() throws Exception {
      try {
        if (client != null) client.close();
        if (reverse != null) reverse.close();
      } finally {
        try {
          if (accountServer != null) accountServer.shutdownNow();
          if (gsServer != null) gsServer.shutdownNow();
          if (accountServer != null)
            assertThat(accountServer.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
          if (gsServer != null) assertThat(gsServer.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
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
