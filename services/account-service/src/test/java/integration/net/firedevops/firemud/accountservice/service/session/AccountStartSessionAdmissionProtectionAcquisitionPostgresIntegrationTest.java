package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository;
import net.firedevops.firemud.accountservice.service.session.AccountOriginalStartSessionAuthorizationProofFixture.OriginalStartSession;
import net.firedevops.firemud.accountservice.service.session.AccountOriginalStartSessionAuthorizationProofFixture.OwnerAttemptLifecycle;
import net.firedevops.firemud.accountservice.service.session.AccountOriginalStartSessionAuthorizationProofFixture.OwnerClaim;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptClient;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Result;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL proof of real Account issue, redemption, source-currentness, World participation, and
 * admission-protection acquisition over the default migration history. The Account source-row
 * update is preempted by the older pending World-participation guard; a separate direct SQL helper
 * assertion proves the admission guard against the exact keys acquired here.
 *
 * <p>The Game Session current-attempt result, World admission-hold identity, and workload peer
 * contexts are controlled in-process fixture inputs. They exercise Account's actual owners and
 * database guards, but do not prove Game Session mTLS, Game Session custody, a genuine World
 * admission producer, or production registration.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AccountStartSessionAdmissionProtectionAcquisitionPostgresIntegrationTest {
  private static final String NAMESPACE = "control-ui-owner-proof";
  private static final String WORLD_PEER_URI =
      "spiffe://firemud/ns/control-ui-owner-proof/sa/world-management-service";
  private static final String GAME_SESSION_PEER_URI =
      "spiffe://firemud/ns/control-ui-owner-proof/sa/game-session-service";
  private static final long GAME_TEMPLATE_ID = 42L;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Network NETWORK = Network.newNetwork();

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("admission-proof-primary")
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
              "admission-proof-primary",
              "6379");

  @TempDir Path temporary;

  @Test
  void actualIssueRedemptionAndCurrentnessAcquireRetryAndExcludeCapturedSourceWrites()
      throws Exception {
    try (var controlUi =
        new AccountControlUiOriginalOrderFixture(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword(),
            redis.getHost(),
            redis.getMappedPort(6379),
            temporary)) {
      var creator = controlUi.preparedOriginalCreator();
      var accountSources = creator.sources();
      UUID accountId = accountSources.account.getAccountUuid();
      UUID tenantId = accountSources.tenant;
      UUID gameInstanceId = UUID.randomUUID();
      UUID ownerAttemptId = UUID.randomUUID();
      UUID ownerMutationId = UUID.randomUUID();
      long ownerFence = 47L;
      String controlPlaneRequestId = "admission-acquisition-" + UUID.randomUUID();
      String worldSlug = "controlled-admission-world";

      var proof =
          new AccountOriginalStartSessionAuthorizationProofFixture(controlUi, temporary, NAMESPACE);
      var action =
          new StartSessionOperatorAction(
              StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
              StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
              new StartSessionOperatorAction.Scope(tenantId, NAMESPACE),
              new StartSessionOperatorAction.Target(GAME_TEMPLATE_ID, accountId),
              StartSessionOperatorAction.ExpectedVersion.ABSENT,
              new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
              "controlled PostgreSQL admission acquisition proof");
      UUID reservationOwnerId = UUID.randomUUID();
      long reservationFence = 31L;
      var attachedProjection = new AtomicReference<byte[]>();
      OriginalStartSession original =
          proof.issueAndRedeemOriginal(
              controlPlaneRequestId,
              action,
              reservationOwnerId,
              reservationFence,
              new OwnerAttemptLifecycle() {
                @Override
                public OwnerClaim reserve(
                    net.firedevops.firemud.common.operator
                            .StartSessionPostAuthorizationExecutionTuple
                        tuple) {
                  assertThat(tuple.controlPlaneRequestId()).isEqualTo(controlPlaneRequestId);
                  return new OwnerClaim(ownerAttemptId, ownerMutationId, ownerFence);
                }

                @Override
                public void attachAccountRedemptionProjection(
                    OwnerClaim claim,
                    net.firedevops.firemud.common.operator
                            .StartSessionPostAuthorizationExecutionTuple
                        tuple) {
                  assertThat(claim.ownerAttemptId()).isEqualTo(ownerAttemptId);
                  attachedProjection.set(
                      StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple));
                }
              });

      byte[] redemptionProjection =
          StartSessionAccountRedemptionProjection.fromOriginalTuple(original.originalTuple());
      assertThat(attachedProjection.get()).containsExactly(redemptionProjection);
      Instant leaseExpiresAt = original.accountReferenceExpiresAt();
      var currentAttemptClient =
          controlledCurrentAttemptClient(
              original,
              ownerAttemptId,
              ownerMutationId,
              ownerFence,
              leaseExpiresAt,
              redemptionProjection);

      String preparationInputJson =
          preparationInput(
              NAMESPACE,
              tenantId,
              controlPlaneRequestId,
              gameInstanceId,
              worldSlug,
              GAME_TEMPLATE_ID);
      String preparationInputDigest =
          digestPrefixed(preparationInputJson.getBytes(StandardCharsets.UTF_8));
      var worldAcquisition = proof.worldParticipationAcquisitionService(currentAttemptClient);
      var worldRequest =
          new AccountStartSessionWorldParticipationAcquisitionService.AcquisitionRequest(
              original.originalTuple().canonicalBytes(),
              ownerMutationId,
              ownerAttemptId,
              ownerFence,
              gameInstanceId,
              preparationInputJson,
              preparationInputDigest);
      var participation = withPeer(WORLD_PEER_URI, () -> worldAcquisition.acquire(worldRequest));
      assertThat(participation.participation().controlPlaneRequestId())
          .isEqualTo(controlPlaneRequestId);
      assertThat(participation.participation().sources()).isNotEmpty();
      assertThat(participation.originalAttemptEvidence().result().accountRedemptionProjection())
          .containsExactly(redemptionProjection);

      var hold = holdIdentity(tenantId, gameInstanceId, worldSlug);
      var request =
          AccountStartSessionAdmissionProtectionRequest.create(
              original.originalTuple().canonicalBytes(),
              redemptionProjection,
              ownerMutationId,
              ownerAttemptId,
              ownerFence,
              leaseExpiresAt,
              participation.participation().participationId(),
              participation.participation().participationFence(),
              hold);
      var admissionAcquisition = proof.admissionProtectionAcquisitionService(currentAttemptClient);
      assertThatThrownBy(
              () -> withPeer(WORLD_PEER_URI, () -> admissionAcquisition.acquire(request)))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(((StatusRuntimeException) failure).getStatus().getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));
      assertThatThrownBy(
              () -> withPeer(GAME_SESSION_PEER_URI, () -> worldAcquisition.acquire(worldRequest)))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(((StatusRuntimeException) failure).getStatus().getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));
      var acquired = withPeer(GAME_SESSION_PEER_URI, () -> admissionAcquisition.acquire(request));
      assertThat(acquired.request().canonicalBytes()).containsExactly(request.canonicalBytes());
      assertThat(acquired.accountProtectionId()).isNotEqualTo(new UUID(0L, 0L));
      assertThat(acquired.accountProtectionFence()).isPositive();
      assertThat(acquired.sourceEvidenceVector()).isNotEmpty();

      var protectionRepository =
          new AccountStartSessionAdmissionProtectionRepository(proof.sourceDsl());
      var readback =
          accountSources.transactions.execute(
              ignored ->
                  protectionRepository
                      .findCurrentExact(
                          acquired.accountProtectionId(),
                          acquired.accountProtectionFence(),
                          request.canonicalBytes())
                      .orElseThrow());
      assertThat(readback.evidence().canonicalBytes()).containsExactly(acquired.canonicalBytes());
      assertThat(
              proof
                  .sourceDsl()
                  .fetchSingle(
                      "SELECT count(*) FROM account_start_session_admission_protections "
                          + "WHERE control_plane_request_id = ?",
                      controlPlaneRequestId)
                  .get(0, Long.class))
          .isEqualTo(1L);

      var exactRetry = withPeer(GAME_SESSION_PEER_URI, () -> admissionAcquisition.acquire(request));
      assertThat(exactRetry.canonicalBytes()).containsExactly(acquired.canonicalBytes());
      assertThat(exactRetry.accountProtectionId()).isEqualTo(acquired.accountProtectionId());
      assertThat(exactRetry.accountProtectionFence()).isEqualTo(acquired.accountProtectionFence());

      var changedHold = changedHoldIdentity(hold);
      var changedRequest =
          AccountStartSessionAdmissionProtectionRequest.create(
              original.originalTuple().canonicalBytes(),
              redemptionProjection,
              ownerMutationId,
              ownerAttemptId,
              ownerFence,
              leaseExpiresAt,
              participation.participation().participationId(),
              participation.participation().participationFence(),
              changedHold);
      assertThat(changedRequest.canonicalBytes()).isNotEqualTo(request.canonicalBytes());
      assertThatThrownBy(
              () ->
                  withPeer(
                      GAME_SESSION_PEER_URI, () -> admissionAcquisition.acquire(changedRequest)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              "Original StartSession admission protection conflicts with its immutable binding");
      assertThat(
              proof
                  .sourceDsl()
                  .fetchSingle(
                      "SELECT count(*) FROM account_start_session_admission_protections "
                          + "WHERE control_plane_request_id = ?",
                      controlPlaneRequestId)
                  .get(0, Long.class))
          .isEqualTo(1L);

      List<String> admissionSourceKeys =
          acquired.sourceEvidenceVector().stream().map(source -> source.key()).toList();
      assertThat(admissionSourceKeys).contains("ACCOUNT:" + accountId);
      String storedPasswordHash =
          proof
              .sourceDsl()
              .fetchSingle("SELECT password_hash FROM accounts WHERE account_uuid = ?", accountId)
              .get("password_hash", String.class);
      assertThatThrownBy(
              () ->
                  accountSources.transactions.executeWithoutResult(
                      ignored ->
                          proof
                              .sourceDsl()
                              .execute(
                                  "UPDATE accounts SET password_hash = password_hash || '-blocked' "
                                      + "WHERE account_uuid = ?",
                                  accountId)))
          .satisfies(
              failure -> {
                SQLException sqlFailure = findSqlException(failure);
                assertThat(sqlFailure.getSQLState()).isEqualTo("55P03");
                assertThat(sqlFailure.getMessage())
                    .contains(
                        "StartSession World participation remains pending for a required source");
              });
      assertThat(
              proof
                  .sourceDsl()
                  .fetchSingle(
                      "SELECT password_hash FROM accounts WHERE account_uuid = ?", accountId)
                  .get("password_hash", String.class))
          .isEqualTo(storedPasswordHash);

      // The real source-row UPDATE above reaches the older World guard first. Lock all actual
      // acquired source keys in canonical SQL order, then directly exercise the separate
      // admission hook so its installed protection is proved without claiming exclusive causation.
      assertThat(admissionSourceKeys).isNotEmpty();
      String sourcePlaceholders =
          String.join(", ", java.util.Collections.nCopies(admissionSourceKeys.size(), "?"));
      Object[] sourceBindings = admissionSourceKeys.toArray();
      assertThatThrownBy(
              () ->
                  accountSources.transactions.executeWithoutResult(
                      ignored -> {
                        var lockedKeys =
                            proof
                                .sourceDsl()
                                .fetch(
                                    "SELECT source_key FROM account_draft_authorization_source_locks "
                                        + "WHERE source_key IN ("
                                        + sourcePlaceholders
                                        + ") ORDER BY account_publication_authorization_source_sort_key(source_key) "
                                        + "FOR UPDATE",
                                    sourceBindings)
                                .getValues(0, String.class);
                        assertThat(lockedKeys)
                            .containsExactlyInAnyOrderElementsOf(admissionSourceKeys);
                        proof
                            .sourceDsl()
                            .execute(
                                "SELECT account_ss_admission_assert_no_pending(ARRAY["
                                    + sourcePlaceholders
                                    + "]::text[])",
                                sourceBindings);
                      }))
          .satisfies(
              failure -> {
                SQLException sqlFailure = findSqlException(failure);
                assertThat(sqlFailure.getSQLState()).isEqualTo("55P03");
                assertThat(sqlFailure.getMessage())
                    .contains(
                        "Original StartSession admission protection remains pending for a required source");
              });
    }
  }

  private static OriginalStartSessionCurrentAttemptClient controlledCurrentAttemptClient(
      OriginalStartSession original,
      UUID attemptId,
      UUID mutationId,
      long ownerFence,
      Instant leaseExpiresAt,
      byte[] redemptionProjection) {
    return request -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
      assertThat(request.targetNamespace()).isEqualTo(NAMESPACE);
      assertThat(request.canonicalPostAuthorizationTuple())
          .containsExactly(original.originalTuple().canonicalBytes());
      assertThat(request.expectedOwnerAttemptId()).isEqualTo(attemptId);
      assertThat(request.expectedOwnerMutationId()).isEqualTo(mutationId);
      assertThat(request.expectedOwnerFence()).isEqualTo(ownerFence);
      return new Result(request, leaseExpiresAt, redemptionProjection);
    };
  }

  private static WorldCanonicalInitialAdmissionHold.HoldIdentity holdIdentity(
      UUID tenantId, UUID gameInstanceId, String worldSlug) throws Exception {
    String requestDigest = digest(bytes("controlled initial-admission request"));
    var request =
        new WorldCanonicalInitialAdmissionHold.Request(
            NAMESPACE,
            tenantId,
            worldSlug,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "SHARED",
            gameInstanceId,
            UUID.randomUUID(),
            1L,
            "controlled-initial-admission-" + UUID.randomUUID(),
            requestDigest,
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            1L,
            null);
    return new WorldCanonicalInitialAdmissionHold.HoldIdentity(
        request, UUID.randomUUID(), UUID.randomUUID());
  }

  private static WorldCanonicalInitialAdmissionHold.HoldIdentity changedHoldIdentity(
      WorldCanonicalInitialAdmissionHold.HoldIdentity original) {
    var request = original.request();
    var changedRequest =
        new WorldCanonicalInitialAdmissionHold.Request(
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.worldSlug(),
            UUID.randomUUID(),
            request.playableStateNamespaceId(),
            request.playableStateScope(),
            request.canonicalGameInstanceId(),
            request.canonicalVersionId(),
            request.activeLifecycleEpoch(),
            request.initialAdmissionRequestId(),
            request.initialAdmissionRequestDigest(),
            request.initialAdmissionOrigin(),
            request.expectedCatalogRevision(),
            request.expectedPriorPointerVersion());
    return new WorldCanonicalInitialAdmissionHold.HoldIdentity(
        changedRequest, original.holdId(), original.holdFence());
  }

  private static String preparationInput(
      String namespace,
      UUID tenantId,
      String requestId,
      UUID gameInstanceId,
      String worldSlug,
      long gameTemplateId)
      throws Exception {
    String descriptorJson = JSON.writeValueAsString(Map.of("gameTemplateId", gameTemplateId));
    return JSON.writeValueAsString(
        Map.of(
            "schemaVersion",
            1,
            "identity",
            Map.of(
                "targetNamespace",
                namespace,
                "canonicalTenantId",
                tenantId.toString(),
                "controlPlaneRequestId",
                requestId,
                "canonicalGameInstanceId",
                gameInstanceId.toString(),
                "worldSlug",
                worldSlug),
            "gameSessionReadRequest",
            Map.of(
                "targetNamespace",
                namespace,
                "canonicalTenantId",
                tenantId.toString(),
                "controlPlaneRequestId",
                requestId,
                "canonicalGameInstanceId",
                gameInstanceId.toString(),
                "worldSlug",
                worldSlug),
            "gameSessionReadEvidence",
            Map.of(
                "targetNamespace",
                namespace,
                "canonicalTenantId",
                tenantId.toString(),
                "controlPlaneRequestId",
                requestId,
                "canonicalGameInstanceId",
                gameInstanceId.toString(),
                "worldSlug",
                worldSlug,
                "descriptorJson",
                descriptorJson),
            "launchBinding",
            Map.of(
                "targetNamespace",
                namespace,
                "canonicalTenantId",
                tenantId.toString(),
                "controlPlaneRequestId",
                requestId,
                "worldSlug",
                worldSlug)));
  }

  private static <T> T withPeer(String peerUri, Callable<T> action) {
    try (var ignored = AccountControlUiOwnerSourcesFixture.withPeer(peerUri)) {
      return action.call();
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalStateException("Test peer-scoped Account action failed", failure);
    }
  }

  private static SQLException findSqlException(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException) return sqlException;
    }
    throw new AssertionError("Expected database guard rejection", failure);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static String digest(byte[] value) throws Exception {
    return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static String digestPrefixed(byte[] value) throws Exception {
    return "sha256:" + digest(value);
  }
}
