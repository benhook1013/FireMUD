package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldOriginalAttemptEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldOriginalAttemptEvidenceRepository.StoredEvidence;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.Candidate;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.StoredParticipation;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptClient;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Request;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Result;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidenceGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unregistered Account composition that acquires one exact World receiving participation from the
 * original Game Session owner attempt and retains its current-attempt observation atomically with
 * the participation.
 *
 * <p>The Game Session response is a point-in-time observation only. Account revalidates the
 * original issuer, redemption, authority and locked source capture around the local write; this
 * component does not renew authorization or establish World execution admission. No external
 * service or runtime transport is registered here.
 */
public final class AccountStartSessionWorldParticipationAcquisitionService {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final AccountStartSessionOperatorAuthorizationService issuer;
  private final AccountStartSessionWorldParticipationRepository participationRepository;
  private final AccountStartSessionWorldOriginalAttemptEvidenceRepository attemptEvidenceRepository;
  private final OriginalStartSessionCurrentAttemptClient gameSessionClient;
  private final TransactionTemplate readbackTransaction;
  private final String workloadNamespace;

  public AccountStartSessionWorldParticipationAcquisitionService(
      AccountStartSessionOperatorAuthorizationService issuer,
      AccountStartSessionWorldParticipationRepository participationRepository,
      AccountStartSessionWorldOriginalAttemptEvidenceRepository attemptEvidenceRepository,
      OriginalStartSessionCurrentAttemptClient gameSessionClient,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.issuer =
        Objects.requireNonNull(issuer, "original Account StartSession issuer is required");
    this.participationRepository =
        Objects.requireNonNull(
            participationRepository, "World participation repository is required");
    this.attemptEvidenceRepository =
        Objects.requireNonNull(
            attemptEvidenceRepository, "original-attempt evidence repository is required");
    this.gameSessionClient =
        Objects.requireNonNull(
            gameSessionClient, "Game Session current-attempt client is required");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    readbackTransaction =
        new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "Account transaction manager is required"));
    readbackTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    readbackTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    readbackTransaction.setReadOnly(false);
  }

  /**
   * Acquires and reads back the exact immutable Account participation and retained observation. An
   * unknown commit is propagated for a later retry with this same original request; no reference is
   * renewed and no retry write is attempted during readback.
   */
  public AcquiredParticipation acquire(AcquisitionRequest request) {
    requireWorldPeer();
    requireNoAmbientTransaction();
    Objects.requireNonNull(request, "World participation acquisition request is required");

    byte[] originalTupleBytes = request.originalPostAuthorizationTuple();
    StartSessionPostAuthorizationExecutionTuple originalTuple =
        decodeOriginalTuple(originalTupleBytes);
    if (!"StartSession".equals(originalTuple.preAuthorizationTuple().actionFamily())
        || !"game-session-service".equals(originalTuple.preAuthorizationTuple().targetOwner())
        || !workloadNamespace.equals(
            originalTuple.preAuthorizationTuple().action().scope().targetNamespace())) {
      throw denied("Exact original human StartSession tuple for this World namespace required");
    }
    requireNonnil(request.gameSessionOwnerMutationId(), "Game Session owner mutation ID");
    requireNonnil(request.gameSessionOwnerAttemptId(), "Game Session owner attempt ID");
    requirePositive(request.gameSessionOwnerFence(), "Game Session owner fence");
    requireNonnil(request.canonicalGameInstanceId(), "canonical Game Instance ID");
    requirePreparationInput(request.preparationInputJson(), request.preparationInputDigest());

    Request currentAttemptRequest =
        new Request(
            freshReadId(originalTuple, request),
            workloadNamespace,
            originalTupleBytes,
            request.gameSessionOwnerAttemptId(),
            request.gameSessionOwnerMutationId(),
            request.gameSessionOwnerFence());

    // Game Session is always read before opening Account SQL. A fresh point-in-time read is
    // required for every original-request retry; the Account callback below remains the authority.
    requireNoAmbientTransaction();
    Result observed = gameSessionClient.read(currentAttemptRequest);
    requireNoAmbientTransaction();
    Result exactObservation = exactObservation(currentAttemptRequest, observed, originalTuple);

    Commit commit =
        issuer.withCurrentWorldReceivingParticipationCurrentness(
            originalTupleBytes,
            request.gameSessionOwnerAttemptId(),
            request.gameSessionOwnerFence(),
            capture -> {
              Candidate candidate =
                  new Candidate(
                      originalTuple,
                      capture,
                      request.canonicalGameInstanceId(),
                      request.gameSessionOwnerAttemptId(),
                      request.gameSessionOwnerFence(),
                      request.preparationInputJson(),
                      request.preparationInputDigest());
              StoredParticipation participation =
                  participationRepository.createOrReadExact(candidate);
              StoredEvidence evidence =
                  attemptEvidenceRepository.retainCurrentExact(participation, exactObservation);
              requireStoredEvidenceBinding(
                  participation, currentAttemptRequest, originalTuple, evidence);
              return new Commit(candidate, participation, evidence, currentAttemptRequest);
            });

    requireNoAmbientTransaction();
    Readback readback =
        Objects.requireNonNull(
            readbackTransaction.execute(
                ignored -> {
                  Optional<StoredParticipation> participation =
                      Objects.requireNonNull(
                          participationRepository.findCurrentExact(commit.candidate()),
                          "Current participation lookup returned no optional");
                  if (participation.isEmpty()) {
                    throw denied("Committed exact World participation is not readable as current");
                  }
                  StoredParticipation actualParticipation = participation.orElseThrow();
                  StoredEvidence actualEvidence =
                      attemptEvidenceRepository
                          .findCurrentExact(actualParticipation, commit.request())
                          .orElseThrow(
                              () ->
                                  denied(
                                      "Committed original Game Session attempt evidence is not readable"));
                  return new Readback(actualParticipation, actualEvidence);
                }),
            "Account World participation readback transaction returned no result");

    if (!sameParticipation(commit.participation(), readback.participation())
        || !sameStoredEvidence(commit.evidence(), readback.evidence())) {
      throw denied("Committed World participation or original-attempt proof readback differs");
    }
    requireStoredEvidenceBinding(
        readback.participation(), commit.request(), originalTuple, readback.evidence());
    return new AcquiredParticipation(readback.participation(), readback.evidence());
  }

  private Result exactObservation(
      Request expected,
      Result observed,
      StartSessionPostAuthorizationExecutionTuple originalTuple) {
    if (observed == null) {
      throw denied("Exact current original Game Session attempt evidence is unavailable");
    }
    try {
      var canonicalResponse =
          OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(observed);
      Result exact =
          OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
              expected, canonicalResponse);
      if (!OriginalStartSessionCurrentAttemptEvidence.PENDING_PHASE.equals(exact.phaseState())
          || !sameRequest(expected, exact.request(), false)
          || !MessageDigest.isEqual(
              StartSessionAccountRedemptionProjection.fromOriginalTuple(originalTuple),
              exact.accountRedemptionProjection())) {
        throw denied(
            "Current Game Session attempt or full Account redemption projection differs from the original issuer");
      }
      return exact;
    } catch (IllegalArgumentException malformed) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Exact current original Game Session attempt evidence is malformed")
          .withCause(malformed)
          .asRuntimeException();
    }
  }

  private void requireStoredEvidenceBinding(
      StoredParticipation participation,
      Request expectedRequest,
      StartSessionPostAuthorizationExecutionTuple originalTuple,
      StoredEvidence evidence) {
    if (evidence == null
        || !participation.participationId().equals(evidence.participationId())
        || !expectedRequest.expectedOwnerMutationId().equals(evidence.gameSessionOwnerMutationId())
        || !sameRequest(expectedRequest, evidence.result().request(), false)
        || !OriginalStartSessionCurrentAttemptEvidence.PENDING_PHASE.equals(
            evidence.result().phaseState())
        || !MessageDigest.isEqual(
            StartSessionAccountRedemptionProjection.fromOriginalTuple(originalTuple),
            evidence.result().accountRedemptionProjection())
        || !evidence.originalLeaseExpiresAt().equals(evidence.result().originalLeaseExpiresAt())) {
      throw denied("Retained original Game Session attempt proof differs from the exact request");
    }
    try {
      var response =
          net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionCurrentAttemptResponse
              .parseFrom(evidence.originalResponseBytes());
      Result decoded =
          OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
              evidence.result().request(), response);
      if (!sameResult(evidence.result(), decoded)
          || !Arrays.equals(
              evidence.originalResponseBytes(),
              OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(decoded)
                  .toByteArray())) {
        throw denied("Retained original Game Session response bytes are not canonical readback");
      }
    } catch (com.google.protobuf.InvalidProtocolBufferException malformed) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Retained original Game Session response is not a complete wire value")
          .withCause(malformed)
          .asRuntimeException();
    } catch (IllegalArgumentException malformed) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Retained original Game Session response is malformed")
          .withCause(malformed)
          .asRuntimeException();
    }
  }

  private static boolean sameParticipation(
      StoredParticipation expected, StoredParticipation actual) {
    if (!expected.participationId().equals(actual.participationId())
        || expected.participationFence() != actual.participationFence()
        || !expected.controlPlaneRequestId().equals(actual.controlPlaneRequestId())
        || !Arrays.equals(
            expected.originalPostAuthorizationTuple(), actual.originalPostAuthorizationTuple())
        || !expected.targetNamespace().equals(actual.targetNamespace())
        || !expected.canonicalTenantId().equals(actual.canonicalTenantId())
        || !expected.canonicalGameInstanceId().equals(actual.canonicalGameInstanceId())
        || !expected.gameSessionOwnerAttemptId().equals(actual.gameSessionOwnerAttemptId())
        || expected.gameSessionOwnerFence() != actual.gameSessionOwnerFence()
        || !expected.preparationInputJson().equals(actual.preparationInputJson())
        || !expected.preparationInputDigest().equals(actual.preparationInputDigest())
        || expected.producerXid() != actual.producerXid()
        || !expected.createdAt().equals(actual.createdAt())) {
      return false;
    }
    List<SourceEvidence> expectedSources =
        expected.sources().stream()
            .sorted(java.util.Comparator.comparing(SourceEvidence::key))
            .toList();
    List<SourceEvidence> actualSources =
        actual.sources().stream()
            .sorted(java.util.Comparator.comparing(SourceEvidence::key))
            .toList();
    if (expectedSources.size() != actualSources.size()) return false;
    for (int index = 0; index < expectedSources.size(); index++) {
      var left = expectedSources.get(index);
      var right = actualSources.get(index);
      if (!left.key().equals(right.key())
          || !Arrays.equals(left.canonicalBytes(), right.canonicalBytes())) return false;
    }
    return true;
  }

  private static boolean sameStoredEvidence(StoredEvidence expected, StoredEvidence actual) {
    return expected.participationId().equals(actual.participationId())
        && expected.gameSessionOwnerMutationId().equals(actual.gameSessionOwnerMutationId())
        && expected.originalLeaseExpiresAt().equals(actual.originalLeaseExpiresAt())
        && Arrays.equals(expected.originalResponseBytes(), actual.originalResponseBytes())
        && expected.originalResponseDigest().equals(actual.originalResponseDigest())
        && sameResult(expected.result(), actual.result());
  }

  private static boolean sameResult(Result expected, Result actual) {
    return expected.originalLeaseExpiresAt().equals(actual.originalLeaseExpiresAt())
        && sameRequest(expected.request(), actual.request(), true)
        && Arrays.equals(
            expected.accountRedemptionProjection(), actual.accountRedemptionProjection());
  }

  private static boolean sameRequest(Request expected, Request actual, boolean compareReadId) {
    return (!compareReadId || expected.readRequestId().equals(actual.readRequestId()))
        && expected.targetNamespace().equals(actual.targetNamespace())
        && Arrays.equals(
            expected.canonicalPostAuthorizationTuple(), actual.canonicalPostAuthorizationTuple())
        && expected.expectedOwnerAttemptId().equals(actual.expectedOwnerAttemptId())
        && expected.expectedOwnerMutationId().equals(actual.expectedOwnerMutationId())
        && expected.expectedOwnerFence() == actual.expectedOwnerFence();
  }

  private static StartSessionPostAuthorizationExecutionTuple decodeOriginalTuple(
      byte[] tupleBytes) {
    if (tupleBytes == null) throw denied("Complete original StartSession tuple is required");
    try {
      StartSessionPostAuthorizationExecutionTuple tuple =
          StartSessionPostAuthorizationExecutionTuple.decode(tupleBytes);
      if (!Arrays.equals(tupleBytes, tuple.canonicalBytes())) {
        throw denied("Original StartSession tuple is not exact canonical bytes");
      }
      return tuple;
    } catch (IllegalArgumentException malformed) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Complete canonical original StartSession tuple required")
          .withCause(malformed)
          .asRuntimeException();
    }
  }

  private static UUID freshReadId(
      StartSessionPostAuthorizationExecutionTuple tuple, AcquisitionRequest request) {
    Set<UUID> occupied = new HashSet<>();
    occupied.addAll(
        List.of(
            tuple.reservationOwnerId(),
            tuple.preAuthorizationTuple().actor().accountId(),
            tuple.preAuthorizationTuple().action().scope().tenantId(),
            request.gameSessionOwnerMutationId(),
            request.gameSessionOwnerAttemptId(),
            request.canonicalGameInstanceId(),
            StartSessionAuthorityEvidenceBundle.decode(tuple.authorityEvidenceBundleBytes())
                .issuanceOperationId()));
    UUID result;
    do {
      result = UUID.randomUUID();
    } while (occupied.contains(result));
    return result;
  }

  private static void requirePreparationInput(String json, String digest) {
    Objects.requireNonNull(json, "exact preparation input JSON is required");
    Objects.requireNonNull(digest, "preparation input digest is required");
    if (json.isBlank()
        || !StandardCharsets.UTF_8.newEncoder().canEncode(json)
        || json.getBytes(StandardCharsets.UTF_8).length
            > WorldStartSessionExecutionTerminal.MAX_CANONICAL_BYTES
        || !SHA256.matcher(digest).matches()
        || !digest.equals(digestPrefixed(json.getBytes(StandardCharsets.UTF_8)))) {
      throw denied("Exact bounded World preparation input and digest required");
    }
  }

  private void requireWorldPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified World workload identity required")
          .asRuntimeException();
    }
    String expectedUri =
        "spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service";
    if (SessionContext.hasAuthenticatedCallerContext()
        || !workloadNamespace.equals(peer.namespace())
        || !expectedUri.equals(peer.uri())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace World workload without end-user context required")
          .asRuntimeException();
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription(
              "World participation acquisition requires remote currentness outside Account SQL")
          .asRuntimeException();
    }
  }

  private static void requireNonnil(UUID value, String name) {
    if (value == null || NIL_UUID.equals(value)) {
      throw Status.FAILED_PRECONDITION
          .withDescription(name + " must be a canonical non-nil UUID")
          .asRuntimeException();
    }
  }

  private static void requirePositive(long value, String name) {
    if (value <= 0L) {
      throw Status.FAILED_PRECONDITION
          .withDescription(name + " must be positive")
          .asRuntimeException();
    }
  }

  private static String digestPrefixed(byte[] value) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static StatusRuntimeException denied(String description) {
    return Status.FAILED_PRECONDITION.withDescription(description).asRuntimeException();
  }

  /** Raw bounded DTO; validation and canonical decoding occur only after exact peer auth. */
  public record AcquisitionRequest(
      byte[] originalPostAuthorizationTuple,
      UUID gameSessionOwnerMutationId,
      UUID gameSessionOwnerAttemptId,
      long gameSessionOwnerFence,
      UUID canonicalGameInstanceId,
      String preparationInputJson,
      String preparationInputDigest) {
    public AcquisitionRequest {
      originalPostAuthorizationTuple =
          originalPostAuthorizationTuple == null ? null : originalPostAuthorizationTuple.clone();
    }

    @Override
    public byte[] originalPostAuthorizationTuple() {
      return originalPostAuthorizationTuple == null ? null : originalPostAuthorizationTuple.clone();
    }
  }

  /** Exact participation plus retained observation; neither value is World admission authority. */
  public record AcquiredParticipation(
      StoredParticipation participation, StoredEvidence originalAttemptEvidence) {
    public AcquiredParticipation {
      Objects.requireNonNull(participation);
      Objects.requireNonNull(originalAttemptEvidence);
    }
  }

  private record Commit(
      Candidate candidate,
      StoredParticipation participation,
      StoredEvidence evidence,
      Request request) {}

  private record Readback(StoredParticipation participation, StoredEvidence evidence) {}
}
