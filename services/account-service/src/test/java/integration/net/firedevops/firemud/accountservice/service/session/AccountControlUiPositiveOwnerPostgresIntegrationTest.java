package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.codec.ByteArrayCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import net.firedevops.firemud.account.v1.IssueHumanOperatorAuthorizationReferenceRequest;
import net.firedevops.firemud.account.v1.ReadRedeemedOperationProjectionRequest;
import net.firedevops.firemud.account.v1.RecoverOperatorAuthorizationReferenceRequest;
import net.firedevops.firemud.account.v1.RedeemOperatorAuthorizationRequest;
import net.firedevops.firemud.account.v1.StartSessionOperatorAuthorizationServiceGrpc;
import net.firedevops.firemud.accountservice.client.StartSessionReservationEvidenceClient;
import net.firedevops.firemud.accountservice.config.AccountStartSessionOperatorAuthorizationConfiguration;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository;
import net.firedevops.firemud.accountservice.service.impl.StartSessionOperatorAuthorizationGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountOperatorAuthorizationFingerprintKeyring.OwnerKeySource;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerCertificateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptContribution;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.StartSessionReservationMutationTestFixtures;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.ClaimEvidence;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.ClaimPurpose;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.ClaimState;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.Phase;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.State;
import net.firedevops.firemud.loggingadmin.service.impl.StartSessionReservationEvidenceGrpcService;
import net.firedevops.firemud.loggingadmin.service.impl.StartSessionReservationEvidenceLeafApproval;
import net.firedevops.firemud.loggingadmin.v1.ReadCurrentClaimEvidenceRequest;
import net.firedevops.firemud.loggingadmin.v1.ReadCurrentClaimEvidenceResponse;
import net.firedevops.firemud.loggingadmin.v1.StartSessionReservationEvidencePurpose;
import net.firedevops.firemud.loggingadmin.v1.StartSessionReservationEvidenceServiceGrpc;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Isolated positive Account storage/producer proof with explicitly stipulated upstream trust,
 * materializer Secret observations, validator inventory/acceptance and external creator/legal
 * evidence. Generated ephemeral key custody and test-only ConfigMap CAS replace platform delivery.
 * Real Account repositories, transactions, coordinator, materialization RPC owner, mounted signer,
 * issuance, physical Coordination Redis and actor/order owners execute. No production trust,
 * deployed Pod attribution, World APPLIED, public wiring or runtime activation is established.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AccountControlUiPositiveOwnerPostgresIntegrationTest {
  private static final Network NETWORK = Network.newNetwork();
  private static final String PASSWORD = UUID.randomUUID().toString();

  @Container
  static final PostgreSQLContainer<?> postgres =
      AccountControlUiOwnerWorkflowPostgresIntegrationTest.postgres;

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("positive-control-ui-primary")
          .withExposedPorts(6379)
          .withCommand(
              "redis-server",
              "--bind",
              "0.0.0.0",
              "--protected-mode",
              "no",
              "--appendonly",
              "yes",
              "--appendfsync",
              "always");

  @Container
  static final GenericContainer<?> replica =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .dependsOn(redis)
          .withCommand(
              "redis-server",
              "--bind",
              "0.0.0.0",
              "--protected-mode",
              "no",
              "--appendonly",
              "yes",
              "--appendfsync",
              "always",
              "--replicaof",
              "positive-control-ui-primary",
              "6379");

  @TempDir Path temporary;

  private InProcessLoggingTransport loggingTransport;

  @AfterEach
  void clearLoggingTransport() throws Exception {
    SessionContext.clear();
    if (loggingTransport != null) {
      loggingTransport.close();
      loggingTransport = null;
    }
  }

  @Test
  void realSignerLifecycleIssuesAuthenticatesAndClaimsExactOriginalAccountOrder() throws Exception {
    var f =
        new AccountControlUiOwnerSourcesFixture(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword(),
            temporary,
            false);
    var lifecycle = new AccountControlUiSignerFixture(f, temporary);
    lifecycle.commit();
    // This is the actual private lifecycle-derived signer receipt, never a handcrafted Capture.
    var originalSigner = f.tx(lifecycle.signer::captureCurrent);
    assertThat(f.tx(() -> lifecycle.signer.requireOriginal(originalSigner.receipt())).receipt())
        .isEqualTo(originalSigner.receipt());
    custody("encryption", "enc1", 11);
    custody("request-mac", "mac1", 29);
    try (var connections = new RedisFixture()) {
      var admin = connections.admin;
      var accountClient = connections.account;
      try (var connection = admin.connect(ByteArrayCodec.INSTANCE)) {
        connection
            .sync()
            .aclSetuser(
                "account_coord_app",
                new io.lettuce.core.AclSetuserArgs()
                    .reset()
                    .on()
                    .addPassword(PASSWORD)
                    .keyPattern("session:auth:token:*")
                    .addCommand(io.lettuce.core.protocol.CommandType.HELLO)
                    .addCommand(io.lettuce.core.protocol.CommandType.PING)
                    .addCommand(
                        io.lettuce.core.protocol.CommandType.CLIENT,
                        io.lettuce.core.protocol.CommandKeyword.SETINFO)
                    .addCommand(
                        io.lettuce.core.protocol.CommandType.ACL,
                        io.lettuce.core.protocol.CommandKeyword.WHOAMI)
                    .addCommand(
                        io.lettuce.core.protocol.CommandType.SCRIPT,
                        io.lettuce.core.protocol.CommandKeyword.LOAD)
                    .addCommand(io.lettuce.core.protocol.CommandType.EVALSHA)
                    .addCommand(io.lettuce.core.protocol.CommandType.GET)
                    .addCommand(io.lettuce.core.protocol.CommandType.SET)
                    .addCommand(io.lettuce.core.protocol.CommandType.PEXPIRETIME)
                    .addCommand(io.lettuce.core.protocol.CommandType.TIME));
        connection
            .sync()
            .dispatch(
                TestAclCommand.INSTANCE,
                new io.lettuce.core.output.StatusOutput<>(ByteArrayCodec.INSTANCE),
                new io.lettuce.core.protocol.CommandArgs<>(ByteArrayCodec.INSTANCE)
                    .add("SETUSER")
                    .add("account_coord_app")
                    .add("+waitaof"));
        awaitLocalAndReplicaAof(connection.sync());
      }
      var registry =
          new AccountControlUiCoordination(
              () -> accountClient.connect(ByteArrayCodec.INSTANCE),
              new AccountCoordinationPinnedConnectionProvider.AcknowledgementRequirements(
                  1, 1, 5000),
              testCatalog());
      var operations = new AccountControlUiIssuanceRepository(f.dsl);
      var publicSource =
          new AccountJwtJwksTrustedSource(
              lifecycle.client,
              lifecycle.trust,
              lifecycle.desired,
              lifecycle.publication,
              f.manager);
      var actors =
          new AccountControlUiActorService(
              operations,
              f.authority,
              lifecycle.signer,
              registry,
              publicSource,
              f.fences,
              f.manager,
              Clock.systemUTC());
      var issuance =
          new AccountControlUiIssuanceService(
              f.primary,
              operations,
              f.authority,
              f.fences,
              lifecycle.signer,
              new AccountControlUiResponseCryptography(
                  new AccountControlUiKeyring(temporary.resolve("custody")), Clock.systemUTC()),
              registry,
              actors,
              f.manager,
              Clock.systemUTC(),
              AccountControlUiOwnerWorkflowPostgresIntegrationTest.CALLER);
      var environment = f.terms.captureCurrentEnvironmentBoundary();
      String compact;
      byte[] originalCompact;
      Instant originalExpiry, originalRecoveryExpiry;
      try (var peer =
              AccountControlUiOwnerWorkflowPostgresIntegrationTest.withPeer(
                  AccountControlUiOwnerWorkflowPostgresIntegrationTest.CALLER);
          var issued =
              issuance.issue(
                  f.request(AccountControlUiOwnerWorkflowPostgresIntegrationTest.OTP),
                  environment)) {
        originalCompact = issued.compactBytes();
        compact = new String(originalCompact, StandardCharsets.US_ASCII);
        originalExpiry = issued.expiresAt();
        originalRecoveryExpiry = issued.recoveryExpiresAt();
      }
      assertThat(f.challenges.findByAccountId(f.account.getId())).isEmpty();
      var originalOperation = f.tx(() -> operations.findRequest(f.request));
      var originalEnvelope = f.tx(() -> operations.envelope(originalOperation));
      // Simulate loss of the completed response: recover with the original, now-consumed OTP.
      // No new challenge or primary-auth credential is installed before the exact retry.
      try (var peer =
              AccountControlUiOwnerWorkflowPostgresIntegrationTest.withPeer(
                  AccountControlUiOwnerWorkflowPostgresIntegrationTest.CALLER);
          var recovered =
              issuance.issue(
                  f.request(AccountControlUiOwnerWorkflowPostgresIntegrationTest.OTP),
                  environment)) {
        assertThat(recovered.compactBytes()).isEqualTo(originalCompact);
        assertThat(recovered.expiresAt()).isEqualTo(originalExpiry);
        assertThat(recovered.recoveryExpiresAt()).isEqualTo(originalRecoveryExpiry);
        assertThatThrownBy(() -> issuance.issue(f.request("changed-original-otp"), environment))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Control-ui issuance idempotency conflict");
      }
      assertThat(f.challenges.findByAccountId(f.account.getId())).isEmpty();
      var recoveredOperation = f.tx(() -> operations.findRequest(f.request));
      assertThat(recoveredOperation.operationId).isEqualTo(originalOperation.operationId);
      assertThat(recoveredOperation.status).isEqualTo("COMMITTED");
      assertThat(recoveredOperation.tokenHash).isEqualTo(originalOperation.tokenHash);
      assertThat(recoveredOperation.claims).isEqualTo(originalOperation.claims);
      var recoveredEnvelope = f.tx(() -> operations.envelope(recoveredOperation));
      assertThat(recoveredEnvelope.encrypted()).isEqualTo(originalEnvelope.encrypted());
      assertThat(recoveredEnvelope.binding()).isEqualTo(originalEnvelope.binding());
      assertThat(f.dsl.fetchCount(DSL.table("account_control_ui_issuance_operations")))
          .isEqualTo(1);
      assertThat(f.dsl.fetchCount(DSL.table("account_control_ui_response_envelopes"))).isEqualTo(1);
      var actor = actors.authenticate(compact, f.tenant, environment);
      assertThat(actor.accountId()).isEqualTo(f.account.getAccountUuid());
      var snapshot = f.tx(() -> f.authority.captureInitial(f.tenant, environment));
      var binding = originalBinding(f, snapshot.sources());
      var claimed = actors.claimOriginalDraft(compact, binding, environment);
      assertThat(claimed.ordering().name()).isEqualTo("COMMIT_ORDER");
      assertOrder(actors.claimOriginalDraft(compact, binding, environment), binding);
      assertOrder(f.tx(() -> f.fences.read(binding)), binding);
      assertThat(f.dsl.fetchCount(DSL.table("account_control_ui_issuance_operations")))
          .isEqualTo(1);
      assertThat(f.dsl.fetchCount(DSL.table("account_control_ui_response_envelopes"))).isEqualTo(1);
      String tokenHash =
          AccountControlUiIssuanceRepository.hash(compact.getBytes(StandardCharsets.US_ASCII));
      var retained = f.tx(() -> operations.findToken(tokenHash));
      assertThat(retained.status).isEqualTo("COMMITTED");
      assertThat(retained.signerReceipt).isEqualTo(originalSigner.receipt());
      var active = registry.readActive(tokenHash);
      assertThat(active).isNotEmpty();
      var committed = f.tx(() -> operations.requireCommitted(operations.findToken(tokenHash)));
      var insufficientAck =
          new AccountControlUiCoordination(
              () -> accountClient.connect(ByteArrayCodec.INSTANCE),
              new AccountCoordinationPinnedConnectionProvider.AcknowledgementRequirements(
                  1, 2, 1000),
              testCatalog());
      assertThatThrownBy(() -> insufficientAck.activate(committed))
          .isInstanceOf(IllegalStateException.class);
      assertThat(registry.readActive(tokenHash)).isEqualTo(active);
      // An exact actor cannot claim an original binding whose retained Account source is changed.
      var changed = new ArrayList<>(snapshot.sources());
      var first = changed.getFirst();
      changed.set(
          0,
          new DraftAuthorizationFenceBinding.SourceEvidence(
              first.kind(),
              first.scopeId(),
              first.generation(),
              first.sourceVersion(),
              first.checkpointStream(),
              first.checkpointSequence(),
              new byte[] {99}));
      var changedBinding = originalBinding(f, changed);
      assertThatThrownBy(() -> actors.claimOriginalDraft(compact, changedBinding, environment))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Exact current authenticated initial creator required");
      assertOrder(f.tx(() -> f.fences.read(binding)), binding);

      var startSessionAction =
          new StartSessionOperatorAction(
              StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
              StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
              new StartSessionOperatorAction.Scope(f.tenant, "control-ui-owner-proof"),
              new StartSessionOperatorAction.Target(17L, actor.accountId()),
              StartSessionOperatorAction.ExpectedVersion.ABSENT,
              new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
              "test-only Account operator composition");
      assertThat(actor.tenantId()).isEqualTo(f.tenant);
      Map<String, List<String>> verifiedScopedRoles =
          actors.withCurrent(
              compact,
              f.tenant,
              environment,
              current -> {
                if (!current.stored().accountId.equals(actor.accountId())
                    || !current.stored().tenantId.equals(actor.tenantId())) {
                  throw new IllegalStateException(
                      "Current Account actor differs from the authenticated operator");
                }
                return verifiedScopedRoles(
                    AccountControlUiIssuanceRepository.object(current.stored().claims),
                    actor.tenantId());
              });
      assertThat(verifiedScopedRoles)
          .containsEntry(actor.tenantId().toString(), List.of("tenantAdmin"));
      SessionContext.setContext(actor.accountId().toString(), List.of(), verifiedScopedRoles);
      net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationTuple
          loggingTuple;
      try {
        loggingTuple =
            net.firedevops.firemud.loggingadmin.operator
                .StartSessionPreAuthorizationReservationTuple.fromCurrentTenantAdmin(
                "start-session-" + UUID.randomUUID(), startSessionAction);
      } finally {
        SessionContext.clear();
      }
      assertThat(loggingTuple.isAuthorityDerived()).isTrue();
      assertThat(loggingTuple.actor().accountId()).isEqualTo(actor.accountId());
      var tuple =
          StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(
              loggingTuple.canonicalJson());
      byte[] tupleBytes = tuple.canonicalJson().getBytes(StandardCharsets.UTF_8);
      // Controlled test-only Game Session owner-attempt binding; no GS mutation workflow runs here.
      UUID ownerAttemptId = UUID.randomUUID();
      long ownerFence = 7L;
      String loggingSchema = "start_session_" + UUID.randomUUID().toString().replace("-", "");
      var loggingDataSource =
          new DriverManagerDataSource(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
      loggingDataSource.setSchema(loggingSchema);
      Flyway.configure()
          .dataSource(loggingDataSource)
          .schemas(loggingSchema)
          .defaultSchema(loggingSchema)
          .placeholders(Map.of("serviceSchema", loggingSchema))
          .locations("filesystem:" + loggingMigrations())
          .load()
          .migrate();
      var reservationFixture =
          StartSessionReservationMutationTestFixtures.forRunOwnedPostgres(postgres, loggingSchema);
      var reservationService = reservationFixture.service();
      var approvedAccountLeaf = testAccountLeafEvidence();
      Path approvedLeafFile =
          temporary.resolve("account-reservation-evidence-approved-test-leaf.txt");
      Files.writeString(
          approvedLeafFile,
          "active=" + approvedAccountLeaf.leafSha256() + "\n",
          StandardCharsets.US_ASCII);
      loggingTransport =
          inProcessLoggingTransport(
              new StartSessionReservationEvidenceGrpcService(
                  reservationService,
                  new StartSessionReservationEvidenceLeafApproval(approvedLeafFile.toString()),
                  "control-ui-owner-proof"),
              "spiffe://firemud/ns/control-ui-owner-proof/sa/account-service",
              approvedAccountLeaf);

      var acquisition = reservationService.acquire(loggingTuple);
      assertThat(acquisition.newlyAcquired()).isTrue();
      var claim = acquisition.claim();
      var pending = reservationService.markAuthorizationPending(claim);
      assertThat(pending.mayDispatchAccountAuthorization()).isTrue();
      assertThat(pending.snapshot().tuple().canonicalJson())
          .isEqualTo(loggingTuple.canonicalJson());
      assertThat(pending.snapshot().mutationDigest()).isEqualTo(loggingTuple.mutationDigest());
      assertThat(pending.snapshot().phase()).isEqualTo(Phase.ACCOUNT_AUTHORIZATION);
      assertThat(pending.snapshot().state()).isEqualTo(State.AUTHORIZATION_PENDING);
      ClaimEvidence claimEvidence = claim.claimEvidence();
      assertThat(claimEvidence.reservationOwnerId()).isNotEqualTo(new UUID(0L, 0L));
      assertThat(claimEvidence.currentClaimOwnerId()).isEqualTo(claimEvidence.reservationOwnerId());
      assertThat(claimEvidence.currentClaimFence()).isEqualTo(pending.snapshot().claimFence());

      ReadCurrentClaimEvidenceResponse currentClaim =
          loggingTransport
              .accountClient()
              .readCurrentClaimEvidence(
                  tuple,
                  claimEvidence.reservationOwnerId(),
                  claimEvidence.reservationClaimFence(),
                  claimEvidence.currentClaimOwnerId(),
                  claimEvidence.currentClaimFence(),
                  StartSessionReservationEvidenceClient.Purpose.ISSUE);
      assertThat(currentClaim.getControlPlaneRequestId()).isEqualTo(tuple.controlPlaneRequestId());
      assertThat(currentClaim.getPreAuthorizationTupleJson().toByteArray()).isEqualTo(tupleBytes);
      assertThat(currentClaim.getMutationDigest()).isEqualTo(tuple.mutationDigest());
      assertThat(currentClaim.getReservationOwnerId())
          .isEqualTo(claimEvidence.reservationOwnerId().toString());
      assertThat(currentClaim.getReservationClaimFence())
          .isEqualTo(claimEvidence.reservationClaimFence());
      assertThat(currentClaim.getClaimOwnerId())
          .isEqualTo(claimEvidence.currentClaimOwnerId().toString());
      assertThat(currentClaim.getClaimFence()).isEqualTo(claimEvidence.currentClaimFence());
      assertThat(currentClaim.getPurpose())
          .isEqualTo(
              StartSessionReservationEvidencePurpose
                  .START_SESSION_RESERVATION_EVIDENCE_PURPOSE_ISSUE);
      assertThat(currentClaim.getClaimExpiresAtEpochMillis())
          .isGreaterThan(currentClaim.getObservedAtEpochMillis());
      assertThat(currentClaim.getClaimExpiresAtEpochMillis())
          .isEqualTo(pending.snapshot().claimExpiresAtEpochMillis());

      ReadCurrentClaimEvidenceRequest substitutedClaim =
          currentClaimRequest(tuple, claimEvidence, claimEvidence.currentClaimFence() + 1L);
      assertThatThrownBy(
              () -> loggingTransport.receiverStub().readCurrentClaimEvidence(substitutedClaim))
          .isInstanceOf(StatusRuntimeException.class)
          .extracting(failure -> ((StatusRuntimeException) failure).getStatus().getCode())
          .isEqualTo(Status.Code.FAILED_PRECONDITION);
      var storedPending = reservationService.find(tuple.controlPlaneRequestId()).orElseThrow();
      assertThat(storedPending.tuple().canonicalJson()).isEqualTo(loggingTuple.canonicalJson());
      assertThat(storedPending.state()).isEqualTo(State.AUTHORIZATION_PENDING);

      byte[] operatorFingerprintKey = new byte[32];
      Arrays.fill(operatorFingerprintKey, (byte) 0x5a);
      var fingerprintKey =
          new AccountOperatorAuthorizationFingerprintKeyring.KeyMaterial(
              "test-operator-key", new SecretKeySpec(operatorFingerprintKey, "HmacSHA256"));
      Arrays.fill(operatorFingerprintKey, (byte) 0);
      custody("operator-response-envelope", "test-envelope-key", 53);
      var operatorRepository = new AccountStartSessionOperatorAuthorizationRepository(f.dsl);
      String gameDesignPeer = "spiffe://firemud/ns/control-ui-owner-proof/sa/game-design-service";
      String gameSessionPeer = "spiffe://firemud/ns/control-ui-owner-proof/sa/game-session-service";
      try (var operatorContext =
          operatorAuthorizationContext(
              actors,
              operations,
              loggingTransport.accountClient(),
              operatorRepository,
              () ->
                  new AccountOperatorAuthorizationFingerprintKeyring.Snapshot(
                      fingerprintKey, List.of()),
              f.terms,
              f.manager,
              temporary.resolve("custody/operator-response-envelope"))) {
        var operatorAuthorization =
            operatorContext.getBean(AccountStartSessionOperatorAuthorizationService.class);
        var accountReceiver =
            new StartSessionOperatorAuthorizationGrpcService(
                operatorAuthorization, "control-ui-owner-proof");
        try (var accountTransport =
            inProcessAccountTransport(accountReceiver, "control-ui-owner-proof")) {
          var issueRequest =
              IssueHumanOperatorAuthorizationReferenceRequest.newBuilder()
                  .setCanonicalPreAuthorizationTupleBytes(ByteString.copyFrom(tupleBytes))
                  .setControlUiJwt(compact)
                  .setReservationOwnerId(claimEvidence.reservationOwnerId().toString())
                  .setReservationClaimFence(claimEvidence.reservationClaimFence())
                  .setCurrentClaimOwnerId(claimEvidence.currentClaimOwnerId().toString())
                  .setCurrentClaimFence(claimEvidence.currentClaimFence())
                  .build();

          assertThatThrownBy(
                  () ->
                      accountTransport
                          .gameDesign()
                          .issueHumanOperatorAuthorizationReference(issueRequest))
              .isInstanceOf(StatusRuntimeException.class)
              .extracting(failure -> ((StatusRuntimeException) failure).getStatus().getCode())
              .isEqualTo(Status.Code.PERMISSION_DENIED);
          assertThat(
                  f.tx(
                      () ->
                          operatorRepository.findByControlPlaneRequestId(
                              tuple.controlPlaneRequestId())))
              .isEmpty();

          var issuedOperator =
              accountTransport.logging().issueHumanOperatorAuthorizationReference(issueRequest);
          assertThat(issuedOperator.getAuthenticatedLoggingWorkloadIdentity())
              .isEqualTo(AccountControlUiOwnerWorkflowPostgresIntegrationTest.CALLER);

          var staleRecoveryRequest =
              RecoverOperatorAuthorizationReferenceRequest.newBuilder()
                  .setCanonicalPreAuthorizationTupleBytes(ByteString.copyFrom(tupleBytes))
                  .setReservationOwnerId(claimEvidence.reservationOwnerId().toString())
                  .setReservationClaimFence(claimEvidence.reservationClaimFence())
                  .setCurrentClaimOwnerId(claimEvidence.currentClaimOwnerId().toString())
                  .setCurrentClaimFence(claimEvidence.currentClaimFence())
                  .build();

          assertThatThrownBy(
                  () ->
                      accountTransport
                          .logging()
                          .recoverOperatorAuthorizationReference(staleRecoveryRequest))
              .isInstanceOf(StatusRuntimeException.class)
              .extracting(failure -> ((StatusRuntimeException) failure).getStatus().getCode())
              .isEqualTo(Status.Code.INVALID_ARGUMENT);

          long originalClaimExpiresAt = pending.snapshot().claimExpiresAtEpochMillis();
          long remainingClaimMillis = originalClaimExpiresAt - System.currentTimeMillis();
          if (remainingClaimMillis >= 0L) {
            Thread.sleep(remainingClaimMillis + 5L);
          }
          var expiredClaim =
              reservationService.expireClaim(
                  claim, Phase.ACCOUNT_AUTHORIZATION, State.AUTHORIZATION_PENDING);
          assertThat(expiredClaim.phase()).isEqualTo(Phase.ACCOUNT_AUTHORIZATION);
          assertThat(expiredClaim.state()).isEqualTo(State.AUTHORIZATION_PENDING);
          assertThat(expiredClaim.claimState()).isEqualTo(ClaimState.EXPIRED);
          assertThat(expiredClaim.reservationClaimFence())
              .isEqualTo(claimEvidence.reservationClaimFence());
          assertThat(expiredClaim.claimFence()).isEqualTo(claimEvidence.currentClaimFence() + 1L);

          var recovery =
              reservationService.acquireAuthorizationRecoveryClaim(loggingTuple).orElseThrow();
          var recoveryEvidence = recovery.claim().claimEvidence();
          assertThat(recovery.snapshot().claimPurpose())
              .isEqualTo(ClaimPurpose.AUTHORIZATION_RECOVERY);
          assertThat(recovery.snapshot().claimState()).isEqualTo(ClaimState.ACTIVE);
          assertThat(recoveryEvidence.reservationOwnerId())
              .isEqualTo(claimEvidence.reservationOwnerId());
          assertThat(recoveryEvidence.reservationClaimFence())
              .isEqualTo(claimEvidence.reservationClaimFence());
          assertThat(recoveryEvidence.currentClaimOwnerId())
              .isNotEqualTo(recoveryEvidence.reservationOwnerId());
          assertThat(recoveryEvidence.currentClaimFence())
              .isEqualTo(expiredClaim.claimFence() + 1L);

          var recoverRequest =
              RecoverOperatorAuthorizationReferenceRequest.newBuilder()
                  .setCanonicalPreAuthorizationTupleBytes(ByteString.copyFrom(tupleBytes))
                  .setReservationOwnerId(recoveryEvidence.reservationOwnerId().toString())
                  .setReservationClaimFence(recoveryEvidence.reservationClaimFence())
                  .setCurrentClaimOwnerId(recoveryEvidence.currentClaimOwnerId().toString())
                  .setCurrentClaimFence(recoveryEvidence.currentClaimFence())
                  .build();
          var recoveredOperator =
              accountTransport.logging().recoverOperatorAuthorizationReference(recoverRequest);
          assertThat(recoveredOperator.getOperatorAuthorizationReference())
              .isEqualTo(issuedOperator.getOperatorAuthorizationReference());
          assertThat(recoveredOperator.getAuthorizationReferenceFingerprint())
              .isEqualTo(issuedOperator.getAuthorizationReferenceFingerprint());
          assertThat(recoveredOperator.getExpiresAt()).isEqualTo(issuedOperator.getExpiresAt());
          assertThat(recoveredOperator.getAuthorityEvidenceBundle())
              .isEqualTo(issuedOperator.getAuthorityEvidenceBundle());
          assertThat(recoveredOperator.getBundleReference())
              .isEqualTo(issuedOperator.getBundleReference());
          assertThat(recoveredOperator.getAuthenticatedLoggingWorkloadIdentity())
              .isEqualTo(issuedOperator.getAuthenticatedLoggingWorkloadIdentity());

          var issuedBundle =
              AccountStartSessionOperatorAuthorityBundle.decode(
                  issuedOperator.getAuthorityEvidenceBundle().toByteArray());
          assertThat(issuedBundle.jsonValue()).containsEntry("issuanceKind", "human_operator");
          assertThat(issuedBundle.jsonValue().get("issuanceEvidence"))
              .isInstanceOf(java.util.Map.class);
          var humanEvidence =
              (java.util.Map<?, ?>) issuedBundle.jsonValue().get("issuanceEvidence");
          assertThat(humanEvidence.get("evidenceType")).isEqualTo("HumanAuthorityEvidence/v1");
          assertThat(humanEvidence.get("role")).isEqualTo("tenantAdmin");
          assertThat(humanEvidence.containsKey("assurance")).isFalse();

          var redeemRequest =
              RedeemOperatorAuthorizationRequest.newBuilder()
                  .setCanonicalPreAuthorizationTupleBytes(ByteString.copyFrom(tupleBytes))
                  .setOperatorAuthorizationReference(
                      issuedOperator.getOperatorAuthorizationReference())
                  .setAuthorizationReferenceFingerprint(
                      issuedOperator.getAuthorizationReferenceFingerprint())
                  .setReservationOwnerId(claimEvidence.reservationOwnerId().toString())
                  .setReservationClaimFence(claimEvidence.reservationClaimFence())
                  .setOwnerAttemptId(ownerAttemptId.toString())
                  .setOwnerFence(ownerFence)
                  .build();
          var redeemed = accountTransport.gameSession().redeemOperatorAuthorization(redeemRequest);
          assertThat(redeemed.getReplay()).isFalse();
          assertThat(redeemed.getAuthorizationReferenceFingerprint())
              .isEqualTo(issuedOperator.getAuthorizationReferenceFingerprint());
          assertThat(redeemed.getAuthorityEvidenceBundle())
              .isEqualTo(issuedOperator.getAuthorityEvidenceBundle());

          var projectionRequest =
              ReadRedeemedOperationProjectionRequest.newBuilder()
                  .setCanonicalPreAuthorizationTupleBytes(ByteString.copyFrom(tupleBytes))
                  .setAuthorizationReferenceFingerprint(
                      issuedOperator.getAuthorizationReferenceFingerprint())
                  .setReservationOwnerId(claimEvidence.reservationOwnerId().toString())
                  .setReservationClaimFence(claimEvidence.reservationClaimFence())
                  .setOwnerAttemptId(ownerAttemptId.toString())
                  .setOwnerFence(ownerFence)
                  .build();
          var projection =
              accountTransport.gameDesign().readRedeemedOperationProjection(projectionRequest);
          var exactRetry =
              accountTransport.gameDesign().readRedeemedOperationProjection(projectionRequest);

          var changedAction =
              new StartSessionOperatorAction(
                  startSessionAction.actionFamilySchemaId(),
                  startSessionAction.actionFamilySchemaVersion(),
                  startSessionAction.scope(),
                  new StartSessionOperatorAction.Target(
                      startSessionAction.target().gameTemplateId() + 1L,
                      startSessionAction.target().ownerAccountId()),
                  startSessionAction.expectedVersion(),
                  startSessionAction.mutation(),
                  "test-only changed StartSession operation");
          var changedTuple =
              StartSessionPreAuthorizationReservationTuple.createHuman(
                  tuple.controlPlaneRequestId(), tuple.actor().accountId(), changedAction);
          var wrongOperationRead =
              projectionRequest.toBuilder()
                  .setCanonicalPreAuthorizationTupleBytes(
                      ByteString.copyFrom(
                          changedTuple.canonicalJson().getBytes(StandardCharsets.UTF_8)))
                  .build();
          assertThatThrownBy(
                  () ->
                      accountTransport
                          .gameDesign()
                          .readRedeemedOperationProjection(wrongOperationRead))
              .isInstanceOf(StatusRuntimeException.class)
              .extracting(failure -> ((StatusRuntimeException) failure).getStatus().getCode())
              .isEqualTo(Status.Code.FAILED_PRECONDITION);

          var storedAfterDeniedRead =
              f.tx(
                  () ->
                      operatorRepository
                          .findByControlPlaneRequestId(tuple.controlPlaneRequestId())
                          .orElseThrow());
          assertThat(storedAfterDeniedRead.status())
              .isEqualTo(AccountStartSessionOperatorAuthorizationRepository.Status.REDEEMED);
          assertThat(storedAfterDeniedRead.redemptionRedeemerWorkloadUri())
              .isEqualTo(gameSessionPeer);
          assertThat(storedAfterDeniedRead.redemptionOwnerAttemptId()).isEqualTo(ownerAttemptId);
          assertThat(storedAfterDeniedRead.redemptionOwnerFence()).isEqualTo(ownerFence);
          assertThat(storedAfterDeniedRead.redemptionAuthorityEvidenceBundle())
              .isEqualTo(issuedOperator.getAuthorityEvidenceBundle().toByteArray());

          assertThat(projection.getControlPlaneRequestId())
              .isEqualTo(tuple.controlPlaneRequestId());
          assertThat(projection.getCanonicalPreAuthorizationTupleBytes().toByteArray())
              .isEqualTo(tupleBytes);
          assertThat(projection.getMutationDigest()).isEqualTo(tuple.mutationDigest());
          assertThat(projection.getAuthorizationReferenceFingerprint())
              .isEqualTo(issuedOperator.getAuthorizationReferenceFingerprint());
          assertThat(projection.getAuthenticatedRedeemerWorkloadIdentity())
              .isEqualTo(gameSessionPeer);
          assertThat(projection.getOwnerAttemptId()).isEqualTo(ownerAttemptId.toString());
          assertThat(projection.getOwnerFence()).isEqualTo(ownerFence);
          assertThat(projection.getReferenceExpiresAt()).isEqualTo(issuedOperator.getExpiresAt());
          assertThat(projection.getIssuanceOperationId())
              .isEqualTo(redeemed.getIssuanceOperationId());
          assertThat(projection.getIssuanceFence()).isEqualTo(redeemed.getIssuanceFence());
          assertThat(projection.getBundleReference())
              .isEqualTo(issuedOperator.getBundleReference());
          assertThat(projection.getAuthorityEvidenceBundle())
              .isEqualTo(issuedOperator.getAuthorityEvidenceBundle());
          assertThat(exactRetry).isEqualTo(projection);
        }
      }
    }
  }

  private AnnotationConfigApplicationContext operatorAuthorizationContext(
      AccountControlUiActorService actors,
      AccountControlUiIssuanceRepository issuanceOperations,
      StartSessionReservationEvidenceClient reservationEvidence,
      AccountStartSessionOperatorAuthorizationRepository repository,
      OwnerKeySource ownerKeySource,
      AccountHostedTermsService hostedTerms,
      PlatformTransactionManager transactions,
      Path operatorEnvelopeMount) {
    var context = new AnnotationConfigApplicationContext();
    context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "positive-owner-test-operator-authorization",
                Map.of(
                    "firemud.account.start-session-operator-authorization.enabled",
                    "true",
                    "firemud.grpc.workload-namespace",
                    "control-ui-owner-proof",
                    "firemud.account.start-session-operator-authorization.fingerprint-post-expiry-retention",
                    "PT2M",
                    "firemud.account.start-session-operator-authorization.response-envelope.keyring-path",
                    operatorEnvelopeMount.toAbsolutePath().toString(),
                    "firemud.account.start-session-operator-authorization.reference-lifetime",
                    "PT5M",
                    "firemud.account.start-session-operator-authorization.response-recovery-window",
                    "PT1M")));
    context.registerBean(AccountControlUiActorService.class, () -> actors);
    context.registerBean(AccountControlUiIssuanceRepository.class, () -> issuanceOperations);
    context.registerBean(StartSessionReservationEvidenceClient.class, () -> reservationEvidence);
    context.registerBean(
        AccountStartSessionOperatorAuthorizationRepository.class, () -> repository);
    context.registerBean(OwnerKeySource.class, () -> ownerKeySource);
    context.registerBean(AccountHostedTermsService.class, () -> hostedTerms);
    context.registerBean(PlatformTransactionManager.class, () -> transactions);
    context.register(AccountStartSessionOperatorAuthorizationConfiguration.class);
    context.refresh();
    return context;
  }

  private static DraftAuthorizationFenceBinding originalBinding(
      AccountControlUiOwnerSourcesFixture f,
      List<DraftAuthorizationFenceBinding.SourceEvidence> sources) {
    var complete =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                f.tenant,
                UUID.randomUUID(),
                1,
                "test-game-key",
                2,
                "test-game-key",
                "NEW_GAME_ROW"),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "test-only-base",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "test-only-world-input")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "region",
                    "region-1",
                    "aggregate",
                    "region-1",
                    "0")));
    return new DraftAuthorizationFenceBinding(
        UUID.randomUUID(),
        complete.requestId(),
        complete.commitId(),
        UUID.randomUUID(),
        f.account.getAccountUuid(),
        f.tenant,
        complete.target().canonicalVersionId(),
        complete.baseCommitId(),
        "0",
        complete.canonicalBytes(),
        complete.canonicalBytes(),
        complete.digest(),
        sources);
  }

  private static Map<String, List<String>> verifiedScopedRoles(
      Map<String, Object> currentClaims, UUID tenantId) {
    Object rawRoles = currentClaims.get("scopedRoles");
    Map<String, List<String>> exactRoles = Map.of(tenantId.toString(), List.of("tenantAdmin"));
    if (!(rawRoles instanceof Map<?, ?> roles) || !roles.equals(exactRoles)) {
      throw new IllegalStateException("Exact current tenant-admin Account role evidence required");
    }
    return exactRoles;
  }

  private static ReadCurrentClaimEvidenceRequest currentClaimRequest(
      StartSessionPreAuthorizationReservationTuple tuple,
      ClaimEvidence claim,
      long observedClaimFence) {
    return ReadCurrentClaimEvidenceRequest.newBuilder()
        .setControlPlaneRequestId(tuple.controlPlaneRequestId())
        .setPreAuthorizationTupleJson(
            ByteString.copyFrom(tuple.canonicalJson(), StandardCharsets.UTF_8))
        .setReservationOwnerId(claim.reservationOwnerId().toString())
        .setReservationClaimFence(claim.reservationClaimFence())
        .setClaimOwnerId(claim.currentClaimOwnerId().toString())
        .setClaimFence(observedClaimFence)
        .setPurpose(
            StartSessionReservationEvidencePurpose.START_SESSION_RESERVATION_EVIDENCE_PURPOSE_ISSUE)
        .build();
  }

  private static Path loggingMigrations() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      Path migrations =
          current.resolve("services/logging-admin-service/src/main/resources/db/migration");
      if (Files.isDirectory(migrations)) {
        return migrations;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Exact Logging/Admin migration directory is required");
  }

  /**
   * Test-only in-process composition supplies the Account URI and a run-owned approved leaf; it
   * exercises receiver authorization, not a TLS handshake or mTLS certificate binding.
   */
  private static InProcessLoggingTransport inProcessLoggingTransport(
      StartSessionReservationEvidenceGrpcService receiver,
      String accountPeerUri,
      GrpcPeerCertificateEvidence accountLeafEvidence)
      throws Exception {
    String serverName = InProcessServerBuilder.generateName();
    var peer = GrpcPeerIdentity.parseUri(accountPeerUri).orElseThrow();
    Server server =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(
                ServerInterceptors.intercept(
                    receiver,
                    new ServerInterceptor() {
                      @Override
                      public <RequestT, ResponseT> ServerCall.Listener<RequestT> interceptCall(
                          ServerCall<RequestT, ResponseT> call,
                          Metadata headers,
                          ServerCallHandler<RequestT, ResponseT> next) {
                        Context peerContext =
                            Context.current()
                                .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
                                .withValue(
                                    GrpcPeerCertificateEvidence.CONTEXT_KEY, accountLeafEvidence);
                        return Contexts.interceptCall(peerContext, call, headers, next);
                      }
                    }))
            .build()
            .start();
    ManagedChannel receiverChannel =
        InProcessChannelBuilder.forName(serverName).directExecutor().build();
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setLoggingAdminService(serverName);
    CommonGrpcClientProperties testTransport = new CommonGrpcClientProperties();
    testTransport.setPlaintext(true);
    GrpcChannelFactory channelFactory =
        new GrpcChannelFactory() {
          @Override
          public ManagedChannel buildChannel(
              String target,
              int defaultPort,
              CommonGrpcClientProperties properties,
              boolean keepAlive) {
            if (!serverName.equals(target) || !properties.isPlaintext()) {
              throw new IllegalArgumentException(
                  "Only the named in-process test transport is valid");
            }
            return InProcessChannelBuilder.forName(serverName).directExecutor().build();
          }
        };
    StartSessionReservationEvidenceClient accountClient =
        new StartSessionReservationEvidenceClient(
            endpoints, testTransport, channelFactory, BlockingGrpcStubCustomizer.noop());
    try {
      ReflectionTestUtils.invokeMethod(accountClient, "init");
      return new InProcessLoggingTransport(
          server,
          receiverChannel,
          accountClient,
          StartSessionReservationEvidenceServiceGrpc.newBlockingStub(receiverChannel));
    } catch (RuntimeException | Error failure) {
      accountClient.close();
      receiverChannel.shutdownNow();
      server.shutdownNow();
      throw failure;
    }
  }

  private static GrpcPeerCertificateEvidence testAccountLeafEvidence() {
    X509Certificate leaf = mock(X509Certificate.class);
    try {
      when(leaf.getEncoded())
          .thenReturn(
              "run-owned Account test leaf certificate".getBytes(StandardCharsets.US_ASCII));
    } catch (CertificateEncodingException exception) {
      throw new AssertionError(exception);
    }
    return GrpcPeerCertificateEvidence.fromCertificate(leaf).orElseThrow();
  }

  private static InProcessAccountTransport inProcessAccountTransport(
      StartSessionOperatorAuthorizationGrpcService receiver, String namespace) throws Exception {
    return new InProcessAccountTransport(
        inProcessAccountEndpoint(
            receiver, "spiffe://firemud/ns/" + namespace + "/sa/logging-admin-service"),
        inProcessAccountEndpoint(
            receiver, "spiffe://firemud/ns/" + namespace + "/sa/game-session-service"),
        inProcessAccountEndpoint(
            receiver, "spiffe://firemud/ns/" + namespace + "/sa/game-design-service"));
  }

  private static InProcessAccountEndpoint inProcessAccountEndpoint(
      StartSessionOperatorAuthorizationGrpcService receiver, String peerUri) throws Exception {
    String serverName = InProcessServerBuilder.generateName();
    var peer = GrpcPeerIdentity.parseUri(peerUri).orElseThrow();
    Server server =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(
                ServerInterceptors.intercept(
                    receiver,
                    new ServerInterceptor() {
                      @Override
                      public <RequestT, ResponseT> ServerCall.Listener<RequestT> interceptCall(
                          ServerCall<RequestT, ResponseT> call,
                          Metadata headers,
                          ServerCallHandler<RequestT, ResponseT> next) {
                        Context peerContext =
                            Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
                        return Contexts.interceptCall(peerContext, call, headers, next);
                      }
                    }))
            .build()
            .start();
    ManagedChannel channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    return new InProcessAccountEndpoint(
        server, channel, StartSessionOperatorAuthorizationServiceGrpc.newBlockingStub(channel));
  }

  /**
   * Test-only peer-context composition. Each server injects one fixed expected workload identity;
   * this deliberately proves receiver authorization only, not transport TLS or mTLS.
   */
  private static final class InProcessAccountTransport implements AutoCloseable {
    private final InProcessAccountEndpoint logging;
    private final InProcessAccountEndpoint gameSession;
    private final InProcessAccountEndpoint gameDesign;

    private InProcessAccountTransport(
        InProcessAccountEndpoint logging,
        InProcessAccountEndpoint gameSession,
        InProcessAccountEndpoint gameDesign) {
      this.logging = logging;
      this.gameSession = gameSession;
      this.gameDesign = gameDesign;
    }

    private StartSessionOperatorAuthorizationServiceGrpc
            .StartSessionOperatorAuthorizationServiceBlockingStub
        logging() {
      return logging.stub();
    }

    private StartSessionOperatorAuthorizationServiceGrpc
            .StartSessionOperatorAuthorizationServiceBlockingStub
        gameSession() {
      return gameSession.stub();
    }

    private StartSessionOperatorAuthorizationServiceGrpc
            .StartSessionOperatorAuthorizationServiceBlockingStub
        gameDesign() {
      return gameDesign.stub();
    }

    @Override
    public void close() {
      logging.close();
      gameSession.close();
      gameDesign.close();
    }
  }

  private static final class InProcessAccountEndpoint implements AutoCloseable {
    private final Server server;
    private final ManagedChannel channel;
    private final StartSessionOperatorAuthorizationServiceGrpc
            .StartSessionOperatorAuthorizationServiceBlockingStub
        stub;

    private InProcessAccountEndpoint(
        Server server,
        ManagedChannel channel,
        StartSessionOperatorAuthorizationServiceGrpc
                .StartSessionOperatorAuthorizationServiceBlockingStub
            stub) {
      this.server = server;
      this.channel = channel;
      this.stub = stub;
    }

    private StartSessionOperatorAuthorizationServiceGrpc
            .StartSessionOperatorAuthorizationServiceBlockingStub
        stub() {
      return stub;
    }

    @Override
    public void close() {
      channel.shutdownNow();
      server.shutdownNow();
    }
  }

  /**
   * Test-only in-process composition. Its injected peer context exercises receiver authorization;
   * it is deliberately not a TLS or mTLS proof.
   */
  private static final class InProcessLoggingTransport implements AutoCloseable {
    private final Server server;
    private final ManagedChannel receiverChannel;
    private final StartSessionReservationEvidenceClient accountClient;
    private final StartSessionReservationEvidenceServiceGrpc
            .StartSessionReservationEvidenceServiceBlockingStub
        receiverStub;

    private InProcessLoggingTransport(
        Server server,
        ManagedChannel receiverChannel,
        StartSessionReservationEvidenceClient accountClient,
        StartSessionReservationEvidenceServiceGrpc
                .StartSessionReservationEvidenceServiceBlockingStub
            receiverStub) {
      this.server = server;
      this.receiverChannel = receiverChannel;
      this.accountClient = accountClient;
      this.receiverStub = receiverStub;
    }

    private StartSessionReservationEvidenceClient accountClient() {
      return accountClient;
    }

    private StartSessionReservationEvidenceServiceGrpc
            .StartSessionReservationEvidenceServiceBlockingStub
        receiverStub() {
      return receiverStub;
    }

    @Override
    public void close() throws Exception {
      accountClient.close();
      receiverChannel.shutdownNow();
      server.shutdownNow();
    }
  }

  private static RedisClient redisClient(boolean application) {
    var uri = RedisURI.Builder.redis(redis.getHost(), redis.getMappedPort(6379));
    if (application) uri.withAuthentication("account_coord_app", PASSWORD);
    var result = RedisClient.create(uri.build());
    result.setOptions(ClientOptions.builder().autoReconnect(false).build());
    return result;
  }

  private static RedisScriptCatalog testCatalog() {
    return RedisScriptCatalog.fromContributions(
        List.of(
            new RedisScriptContribution() {
              @Override
              public String ownerId() {
                return "account-service";
              }

              @Override
              public Collection<RedisScriptDescriptor> descriptors() {
                return List.of(AccountControlUiRegistryContract.descriptor());
              }
            }));
  }

  private void custody(String purpose, String id, int value) throws Exception {
    byte[] key = new byte[32];
    java.util.Arrays.fill(key, (byte) value);
    var directory = Files.createDirectories(temporary.resolve("custody").resolve(purpose));
    Files.writeString(
        directory.resolve("keyring"),
        "firemud-account-response-envelope-keyring-v1\nactive "
            + id
            + " "
            + Base64.getUrlEncoder().withoutPadding().encodeToString(key)
            + "\n");
  }

  private static void assertOrder(
      net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository
              .FenceSnapshot
          observed,
      DraftAuthorizationFenceBinding binding) {
    assertThat(observed.ordering().name()).isEqualTo("COMMIT_ORDER");
    assertThat(observed.binding()).isEqualTo(binding.canonicalBytes());
    assertThat(observed.orderedAt()).isNotNull();
  }

  private static final class RedisFixture implements AutoCloseable {
    final RedisClient admin = redisClient(false), account = redisClient(true);

    @Override
    public void close() {
      account.shutdown();
      admin.shutdown();
    }
  }

  private static void awaitLocalAndReplicaAof(
      io.lettuce.core.api.sync.RedisCommands<byte[], byte[]> commands) throws InterruptedException {
    byte[] probeKey =
        ("test-only:positive-account-coordination-aof-probe:" + UUID.randomUUID())
            .getBytes(StandardCharsets.US_ASCII);
    commands.set(probeKey, "test-only-replication-probe".getBytes(StandardCharsets.US_ASCII));
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      List<Object> counts =
          commands.dispatch(
              TestWaitAof.INSTANCE,
              new io.lettuce.core.output.ArrayOutput<>(ByteArrayCodec.INSTANCE),
              new io.lettuce.core.protocol.CommandArgs<>(ByteArrayCodec.INSTANCE)
                  .add(1)
                  .add(1)
                  .add(500));
      if (counts != null
          && counts.size() == 2
          && counts.get(0) instanceof Long local
          && counts.get(1) instanceof Long replicas
          && local >= 1
          && replicas >= 1) {
        return;
      }
      Thread.sleep(50);
    }
    throw new IllegalStateException("Test Coordination Redis replica did not acknowledge AOF");
  }

  private enum TestWaitAof implements io.lettuce.core.protocol.ProtocolKeyword {
    INSTANCE;

    @Override
    public byte[] getBytes() {
      return "WAITAOF".getBytes(StandardCharsets.US_ASCII);
    }
  }

  private enum TestAclCommand implements io.lettuce.core.protocol.ProtocolKeyword {
    INSTANCE;

    @Override
    public byte[] getBytes() {
      return "ACL".getBytes(StandardCharsets.US_ASCII);
    }
  }
}
