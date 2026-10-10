package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.BindableService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import javax.crypto.spec.SecretKeySpec;
import net.firedevops.firemud.accountservice.client.StartSessionReservationEvidenceClient;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository;
import net.firedevops.firemud.accountservice.service.impl.StartSessionOperatorAuthorizationGrpcService;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.loggingadmin.v1.ReadCurrentClaimEvidenceResponse;
import net.firedevops.firemud.loggingadmin.v1.StartSessionReservationEvidencePurpose;
import org.jooq.Record;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test-only proof fixture for Account's original human StartSession issuer and one-time redemption.
 *
 * <p>Logging's current-claim read response and the supplied Game Session attempt/fence remain
 * stipulated inputs. The Account actor, source capture, issuance, reference custody, redemption,
 * and later projection read use the real Account owners and retained PostgreSQL rows. The peer
 * scopes around issue and redemption are in-process test stipulations, not authenticated producer
 * or mTLS proof.
 */
public final class AccountOriginalStartSessionAuthorizationProofFixture {
  private static final Duration REFERENCE_LIFETIME = Duration.ofMinutes(5);
  private static final Duration RESPONSE_RECOVERY_WINDOW = Duration.ofSeconds(30);
  private static final String LOGGING_SERVICE = "logging-admin-service";
  private static final String GAME_SESSION_SERVICE = "game-session-service";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Set<String> attemptedControlPlaneRequestIds = new HashSet<>();
  private final AccountControlUiOwnerSourcesFixture accountSources;
  private final AccountControlUiOriginalOrderFixture.IssuedCreator creator;
  private final String namespace;
  private final String loggingPeerUri;
  private final String gameSessionPeerUri;
  private final AccountStartSessionOperatorAuthorizationRepository repository;
  private final AccountStartSessionAuthorityCaptureRepository captureRepository;
  private final AccountStartSessionOperatorAuthorizationService authorizationService;
  private final StartSessionOperatorAuthorizationGrpcService grpcService;

  public AccountOriginalStartSessionAuthorizationProofFixture(
      AccountControlUiOriginalOrderFixture controlUi, Path temporary, String namespace)
      throws Exception {
    Objects.requireNonNull(controlUi, "Account control-ui fixture is required");
    Objects.requireNonNull(temporary, "temporary test directory is required");
    this.namespace = requireNamespace(namespace);
    creator = controlUi.preparedOriginalCreator();
    accountSources = creator.sources();

    loggingPeerUri = peerUri(namespace, LOGGING_SERVICE);
    gameSessionPeerUri = peerUri(namespace, GAME_SESSION_SERVICE);
    var claimClient = stipulatedLoggingClaimClient();
    repository = new AccountStartSessionOperatorAuthorizationRepository(accountSources.dsl);
    captureRepository = new AccountStartSessionAuthorityCaptureRepository(accountSources.dsl);
    authorizationService =
        new AccountStartSessionOperatorAuthorizationService(
            creator.actors(),
            new AccountControlUiIssuanceRepository(accountSources.dsl),
            captureRepository,
            claimClient,
            repository,
            fingerprintKeys(),
            responseCryptography(temporary),
            accountSources.terms,
            accountSources.manager,
            Clock.systemUTC(),
            new SecureRandom(),
            loggingPeerUri,
            gameSessionPeerUri,
            REFERENCE_LIFETIME,
            RESPONSE_RECOVERY_WINDOW);
    grpcService = new StartSessionOperatorAuthorizationGrpcService(authorizationService, namespace);
  }

  /**
   * Performs one real Account issue and one real Account redemption for a supplied typed action.
   *
   * <p>The action's tenant and namespace must match this retained Account fixture, and its target
   * owner account must be the authenticated creator. The reservation owner/fence identify the
   * stipulated Logging claim; the Game Session attempt/fence are also stipulated. This method
   * returns the exact original post-authorization tuple and durable owner identities, never the
   * opaque authorization reference or key material.
   */
  public synchronized OriginalStartSession issueAndRedeemOriginal(
      String controlPlaneRequestId,
      StartSessionOperatorAction action,
      UUID reservationOwnerId,
      long reservationClaimFence,
      UUID gameSessionOwnerAttemptId,
      long gameSessionOwnerFence) {
    Objects.requireNonNull(action, "canonical StartSession action is required");
    requireActionBinding(action);
    requireOwner(reservationOwnerId, "reservationOwnerId");
    requirePositive(reservationClaimFence, "reservationClaimFence");
    requireOwner(gameSessionOwnerAttemptId, "gameSessionOwnerAttemptId");
    requirePositive(gameSessionOwnerFence, "gameSessionOwnerFence");

    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            controlPlaneRequestId, accountSources.account.getAccountUuid(), action);
    if (!attemptedControlPlaneRequestIds.add(preTuple.controlPlaneRequestId())) {
      throw new IllegalStateException(
          "This proof fixture already attempted the original StartSession request");
    }
    byte[] preTupleBytes = preTuple.canonicalJson().getBytes(StandardCharsets.UTF_8);
    var authorization =
        withPeer(
            loggingPeerUri,
            () ->
                authorizationService.issue(
                    new AccountStartSessionOperatorAuthorizationService.IssueRequest(
                        creator.compact(),
                        preTupleBytes,
                        reservationOwnerId,
                        reservationClaimFence,
                        reservationOwnerId,
                        reservationClaimFence)));

    var sharedReference =
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            authorization.bundleReference().bundleVersion(),
            authorization.bundleReference().sourceVersion(),
            authorization.bundleReference().sourceFence(),
            authorization.bundleReference().linearization());
    var originalTuple =
        StartSessionPostAuthorizationExecutionTuple.createHuman(
            preTuple,
            loggingPeerUri,
            authorization.authorizationReferenceFingerprint(),
            reservationOwnerId,
            reservationClaimFence,
            authorization.authorityEvidenceBundle(),
            sharedReference);

    var redemption =
        withPeer(
            gameSessionPeerUri,
            () ->
                authorizationService.redeem(
                    new AccountStartSessionOperatorAuthorizationService.RedeemRequest(
                        preTupleBytes,
                        authorization.operatorAuthorizationReference(),
                        authorization.authorizationReferenceFingerprint(),
                        reservationOwnerId,
                        reservationClaimFence,
                        gameSessionOwnerAttemptId,
                        gameSessionOwnerFence)));
    assertThat(redemption.replay()).isFalse();

    var retained =
        accountSources.tx(
            () -> repository.findByControlPlaneRequestId(controlPlaneRequestId).orElseThrow());
    assertThat(retained.status())
        .isEqualTo(AccountStartSessionOperatorAuthorizationRepository.Status.REDEEMED);
    assertThat(retained.preAuthorizationTuple()).containsExactly(preTupleBytes);
    assertThat(retained.reservationOwnerId()).isEqualTo(reservationOwnerId);
    assertThat(retained.reservationClaimFence()).isEqualTo(reservationClaimFence);
    assertThat(retained.redemptionRedeemerWorkloadUri()).isEqualTo(gameSessionPeerUri);
    assertThat(retained.redemptionOwnerAttemptId()).isEqualTo(gameSessionOwnerAttemptId);
    assertThat(retained.redemptionOwnerFence()).isEqualTo(gameSessionOwnerFence);
    assertThat(retained.redemptionReferenceFingerprint())
        .isEqualTo(authorization.authorizationReferenceFingerprint());
    assertThat(retained.issuanceOperationId()).isNotNull();
    assertThat(retained.issuanceFence()).isPositive();
    assertThat(retained.bundleReference()).isEqualTo(authorization.bundleReference());
    assertThat(retained.authorityEvidenceBundle())
        .containsExactly(authorization.authorityEvidenceBundle());
    assertThat(retained.authorityEvidenceBundle())
        .containsExactly(originalTuple.authorityEvidenceBundleBytes());
    assertThat(retained.authorizationReferenceFingerprint())
        .isEqualTo(originalTuple.authorizationReferenceFingerprint());
    assertThat(retained.redemptionAuthorityEvidenceBundle())
        .containsExactly(authorization.authorityEvidenceBundle());
    assertPersistedCapture(
        preTuple,
        reservationOwnerId,
        reservationClaimFence,
        authorization,
        originalTuple,
        retained);

    return new OriginalStartSession(
        preTuple,
        originalTuple,
        reservationOwnerId,
        reservationClaimFence,
        gameSessionOwnerAttemptId,
        gameSessionOwnerFence,
        retained.issuanceOperationId(),
        retained.issuanceFence());
  }

  /** The real Account receiver for the retained redeemed-operation projection. */
  public BindableService grpcService() {
    return grpcService;
  }

  /** The actual Account UUID for the prepared creator used by this fixture. */
  public UUID creatorAccountId() {
    return accountSources.account.getAccountUuid();
  }

  private void assertPersistedCapture(
      StartSessionPreAuthorizationReservationTuple preTuple,
      UUID reservationOwnerId,
      long reservationClaimFence,
      AccountStartSessionOperatorAuthorizationService.AuthorizationResponse authorization,
      StartSessionPostAuthorizationExecutionTuple originalTuple,
      AccountStartSessionOperatorAuthorizationRepository.IssuanceRecord retained) {
    AccountStartSessionAuthorityCapture capture =
        withPeer(
            loggingPeerUri,
            () ->
                creator
                    .actors()
                    .withCurrent(
                        creator.compact(),
                        accountSources.tenant,
                        creator.environment(),
                        current ->
                            captureRepository.lockReadExactCurrent(
                                current,
                                preTuple,
                                loggingPeerUri,
                                reservationOwnerId,
                                reservationClaimFence)));

    assertThat(capture.controlPlaneRequestId()).isEqualTo(preTuple.controlPlaneRequestId());
    assertThat(capture.bundleReference().bundleVersion())
        .isEqualTo(originalTuple.bundleReference().bundleVersion());
    assertThat(capture.bundleReference().sourceVersion())
        .isEqualTo(originalTuple.bundleReference().sourceVersion());
    assertThat(capture.bundleReference().sourceFence())
        .isEqualTo(originalTuple.bundleReference().sourceFence());
    assertThat(capture.bundleReference().linearization())
        .isEqualTo(originalTuple.bundleReference().linearization());
    assertThat(capture.sourceVersion())
        .isEqualTo(Long.parseLong(authorization.bundleReference().sourceVersion()));
    assertThat(capture.sourceFence())
        .isEqualTo(Long.parseLong(authorization.bundleReference().sourceFence()));
    assertThat(capture.linearization()).isEqualTo(authorization.bundleReference().linearization());
    assertThat(authorization.bundleReference().sourceVersion())
        .isEqualTo(originalTuple.bundleReference().sourceVersion());
    assertThat(authorization.bundleReference().sourceFence())
        .isEqualTo(originalTuple.bundleReference().sourceFence());
    assertThat(authorization.bundleReference().linearization())
        .isEqualTo(originalTuple.bundleReference().linearization());

    var authorityBundle =
        AccountStartSessionOperatorAuthorityBundle.decode(retained.authorityEvidenceBundle());
    authorityBundle.requireCaptureBinding(
        capture,
        preTuple,
        AccountStartSessionOperatorAuthorityBundle.fromSharedReference(
            originalTuple.bundleReference()));
    assertThat(authorityBundle.issuanceOperationId()).isEqualTo(retained.issuanceOperationId());
    assertThat(authorityBundle.issuanceFence()).isEqualTo(retained.issuanceFence());
    assertThat(authorization.authorityEvidenceBundle())
        .containsExactly(authorityBundle.canonicalBytes());

    Map<String, Object> snapshot = captureSnapshot(capture);
    byte[] preTupleBytes = preTuple.canonicalJson().getBytes(StandardCharsets.UTF_8);
    assertThat(snapshot.get("schema")).isEqualTo("account-start-session-authority-snapshot/v1");
    assertThat(snapshot.get("controlPlaneRequestId")).isEqualTo(preTuple.controlPlaneRequestId());
    assertThat(snapshot.get("preAuthorizationTuple"))
        .isEqualTo(Base64.getEncoder().encodeToString(preTupleBytes));
    assertThat(snapshot.get("mutationDigest")).isEqualTo(preTuple.mutationDigest());
    assertThat(snapshot.get("accountId")).isEqualTo(preTuple.actor().accountId().toString());
    assertThat(snapshot.get("tenantId")).isEqualTo(preTuple.action().scope().tenantId().toString());
    assertThat(preTuple.action().scope().targetNamespace()).isEqualTo(namespace);
    assertThat(snapshot.get("targetOwner")).isEqualTo(GAME_SESSION_SERVICE);
    assertThat(snapshot.get("loggingWorkloadUri")).isEqualTo(loggingPeerUri);
    assertThat(snapshot.get("reservationOwnerId")).isEqualTo(reservationOwnerId.toString());
    assertThat(snapshot.get("reservationClaimFence"))
        .isEqualTo(Long.toString(reservationClaimFence));

    Record captureRow =
        accountSources.dsl.fetchOne(
            "SELECT account_uuid, tenant_uuid, target_owner, logging_workload_uri, "
                + "reservation_owner_id, reservation_claim_fence, pre_authorization_tuple, "
                + "mutation_digest, issuance_fence, issuance_fence_source_version, "
                + "canonical_snapshot_bytes, canonical_capture_bytes, source_version, "
                + "source_fence, linearization "
                + "FROM account_start_session_authority_captures "
                + "WHERE control_plane_request_id = ?",
            preTuple.controlPlaneRequestId());
    assertThat(captureRow).as("Account's genuine StartSession source capture").isNotNull();
    assertThat(capture.canonicalBytes())
        .containsExactly(captureRow.get("canonical_capture_bytes", byte[].class));
    assertThat(capture.snapshotBytes())
        .containsExactly(captureRow.get("canonical_snapshot_bytes", byte[].class));
    assertThat(captureRow.get("source_version", Long.class)).isEqualTo(capture.sourceVersion());
    assertThat(captureRow.get("source_fence", Long.class)).isEqualTo(capture.sourceFence());
    assertThat(captureRow.get("linearization", String.class)).isEqualTo(capture.linearization());
    assertThat(captureRow.get("account_uuid", UUID.class)).isEqualTo(preTuple.actor().accountId());
    assertThat(captureRow.get("tenant_uuid", UUID.class))
        .isEqualTo(preTuple.action().scope().tenantId());
    assertThat(captureRow.get("target_owner", String.class)).isEqualTo(GAME_SESSION_SERVICE);
    assertThat(captureRow.get("logging_workload_uri", String.class)).isEqualTo(loggingPeerUri);
    assertThat(captureRow.get("reservation_owner_id", UUID.class)).isEqualTo(reservationOwnerId);
    assertThat(captureRow.get("reservation_claim_fence", Long.class))
        .isEqualTo(reservationClaimFence);
    assertThat(captureRow.get("pre_authorization_tuple", byte[].class))
        .containsExactly(preTupleBytes);
    assertThat(captureRow.get("mutation_digest", String.class))
        .isEqualTo(preTuple.mutationDigest());
    assertThat(snapshot.get("issuanceFence"))
        .isEqualTo(Long.toString(captureRow.get("issuance_fence", Long.class)));
    assertThat(snapshot.get("issuanceFence")).isEqualTo(Long.toString(retained.issuanceFence()));
    assertThat(snapshot.get("issuanceFenceSourceVersion"))
        .isEqualTo(Long.toString(captureRow.get("issuance_fence_source_version", Long.class)));
    assertThat(retained.issuanceFence()).isEqualTo(authorityBundle.issuanceFence());
  }

  private static Map<String, Object> captureSnapshot(AccountStartSessionAuthorityCapture capture) {
    try {
      return JSON.readValue(capture.snapshotBytes(), new TypeReference<>() {});
    } catch (Exception malformed) {
      throw new IllegalStateException("Account source snapshot is not readable", malformed);
    }
  }

  private StartSessionReservationEvidenceClient stipulatedLoggingClaimClient() {
    var client = mock(StartSessionReservationEvidenceClient.class);
    when(client.readCurrentClaimEvidence(
            any(StartSessionPreAuthorizationReservationTuple.class),
            any(UUID.class),
            anyLong(),
            any(UUID.class),
            anyLong(),
            eq(StartSessionReservationEvidenceClient.Purpose.ISSUE)))
        .thenAnswer(
            invocation -> {
              var tuple =
                  invocation.getArgument(0, StartSessionPreAuthorizationReservationTuple.class);
              UUID reservationOwnerId = invocation.getArgument(1, UUID.class);
              long reservationClaimFence = invocation.getArgument(2, Long.class);
              UUID claimOwnerId = invocation.getArgument(3, UUID.class);
              long claimFence = invocation.getArgument(4, Long.class);
              if (!reservationOwnerId.equals(claimOwnerId) || reservationClaimFence != claimFence) {
                throw new IllegalArgumentException(
                    "Stipulated ISSUE claim must retain the original owner and fence");
              }
              return stipulatedClaim(
                  tuple, reservationOwnerId, reservationClaimFence, Instant.now());
            });
    return client;
  }

  private void requireActionBinding(StartSessionOperatorAction action) {
    if (!namespace.equals(action.scope().targetNamespace())
        || !accountSources.tenant.equals(action.scope().tenantId())
        || !accountSources.account.getAccountUuid().equals(action.target().ownerAccountId())) {
      throw new IllegalArgumentException(
          "StartSession action must bind the retained creator, tenant, and workload namespace");
    }
  }

  private static ReadCurrentClaimEvidenceResponse stipulatedClaim(
      StartSessionPreAuthorizationReservationTuple tuple,
      UUID reservationOwnerId,
      long reservationClaimFence,
      Instant now) {
    return ReadCurrentClaimEvidenceResponse.newBuilder()
        .setControlPlaneRequestId(tuple.controlPlaneRequestId())
        .setPreAuthorizationTupleJson(
            com.google.protobuf.ByteString.copyFrom(
                tuple.canonicalJson().getBytes(StandardCharsets.UTF_8)))
        .setMutationDigest(tuple.mutationDigest())
        .setReservationOwnerId(reservationOwnerId.toString())
        .setReservationClaimFence(reservationClaimFence)
        .setClaimOwnerId(reservationOwnerId.toString())
        .setClaimFence(reservationClaimFence)
        .setObservedAtEpochMillis(now.minusSeconds(1).toEpochMilli())
        .setClaimExpiresAtEpochMillis(now.plus(Duration.ofMinutes(5)).toEpochMilli())
        .setPurpose(
            StartSessionReservationEvidencePurpose.START_SESSION_RESERVATION_EVIDENCE_PURPOSE_ISSUE)
        .build();
  }

  private static AccountOperatorAuthorizationFingerprintKeyring fingerprintKeys() {
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) 0x51);
    return new AccountOperatorAuthorizationFingerprintKeyring(
        () ->
            new AccountOperatorAuthorizationFingerprintKeyring.Snapshot(
                new AccountOperatorAuthorizationFingerprintKeyring.KeyMaterial(
                    "test-active", new SecretKeySpec(key, "HmacSHA256")),
                List.of()),
        Clock.systemUTC(),
        Duration.ofMinutes(2));
  }

  private static AccountResponseEnvelopeCryptography responseCryptography(Path temporary)
      throws Exception {
    Files.createDirectories(temporary);
    Path root = Files.createTempDirectory(temporary, "account-start-session-response-envelope-");
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) 0x26);
    Files.writeString(
        root.resolve("keyring"),
        "firemud-account-response-envelope-keyring-v1\nactive test-response "
            + Base64.getUrlEncoder().withoutPadding().encodeToString(key)
            + "\n",
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE);
    return new AccountResponseEnvelopeCryptography(
        new AccountResponseEnvelopeKeyring(root.toString()), Clock.systemUTC(), new SecureRandom());
  }

  private static <T> T withPeer(String peerUri, Callable<T> action) {
    try (var ignored = AccountControlUiOwnerSourcesFixture.withPeer(peerUri)) {
      return action.call();
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalStateException("Stipulated Account owner peer action failed", failure);
    }
  }

  private static String requireNamespace(String namespace) {
    Objects.requireNonNull(namespace, "workload namespace is required");
    new StartSessionOperatorAction.Scope(
        UUID.fromString("11111111-1111-4111-8111-111111111111"), namespace);
    return namespace;
  }

  private static String peerUri(String namespace, String service) {
    return "spiffe://firemud/ns/" + namespace + "/sa/" + service;
  }

  private static void requireOwner(UUID owner, String field) {
    Objects.requireNonNull(owner, field + " is required");
    if (new UUID(0L, 0L).equals(owner)) {
      throw new IllegalArgumentException(field + " must not be nil");
    }
  }

  private static void requirePositive(long value, String field) {
    if (value <= 0L) throw new IllegalArgumentException(field + " must be positive");
  }

  /** Exact non-secret identities retained by the original Account issue/redemption. */
  public record OriginalStartSession(
      StartSessionPreAuthorizationReservationTuple preAuthorizationTuple,
      StartSessionPostAuthorizationExecutionTuple originalTuple,
      UUID reservationOwnerId,
      long reservationClaimFence,
      UUID gameSessionOwnerAttemptId,
      long gameSessionOwnerFence,
      UUID issuanceOperationId,
      long issuanceFence) {
    public OriginalStartSession {
      Objects.requireNonNull(preAuthorizationTuple, "original pre-authorization tuple required");
      Objects.requireNonNull(originalTuple, "original post-authorization tuple required");
      requireOwner(reservationOwnerId, "reservationOwnerId");
      requirePositive(reservationClaimFence, "reservationClaimFence");
      requireOwner(gameSessionOwnerAttemptId, "gameSessionOwnerAttemptId");
      requirePositive(gameSessionOwnerFence, "gameSessionOwnerFence");
      requireOwner(issuanceOperationId, "issuanceOperationId");
      requirePositive(issuanceFence, "issuanceFence");
    }

    @Override
    public String toString() {
      return "OriginalStartSession[controlPlaneRequestId="
          + preAuthorizationTuple.controlPlaneRequestId()
          + ", issuanceOperationId="
          + issuanceOperationId
          + ", issuanceFence="
          + issuanceFence
          + ", original tuple evidence redacted]";
    }
  }
}
