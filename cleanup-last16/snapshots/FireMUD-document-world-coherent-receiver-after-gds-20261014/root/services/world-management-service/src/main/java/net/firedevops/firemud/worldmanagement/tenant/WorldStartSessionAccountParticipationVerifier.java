package net.firedevops.firemud.worldmanagement.tenant;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AcquireStartSessionWorldParticipationRequest;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationAcquisitionClient;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidenceGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparation.Input;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparation.OriginalStartSessionOperationInput;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationService.HeldCommitAuthority;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationService.OriginalOperationCommitAuthorityVerifier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Acquires Account's exact original participation before any World execution transaction. */
public final class WorldStartSessionAccountParticipationVerifier
    implements OriginalOperationCommitAuthorityVerifier {
  private final String trustedNamespace;
  private final AccountStartSessionWorldParticipationAcquisitionClient accountClient;

  public WorldStartSessionAccountParticipationVerifier(
      String trustedNamespace,
      AccountStartSessionWorldParticipationAcquisitionClient accountClient) {
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException(
          "World workload namespace must be one canonical DNS label");
    }
    this.trustedNamespace = trustedNamespace;
    this.accountClient = Objects.requireNonNull(accountClient, "accountClient");
  }

  @Override
  public HeldCommitAuthority verifyAndHold(
      Input input, OriginalStartSessionOperationInput operationInput) {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(operationInput, "operationInput");
    requireAuthenticatedGameSessionPeer();
    requireNoAmbientTransaction();
    operationInput.requireMatches(input, trustedNamespace);

    String exactPreparationJson = WorldCanonicalInstancePreparationRepository.inputJson(input);
    byte[] exactPreparationBytes = exactPreparationJson.getBytes(StandardCharsets.UTF_8);
    if (exactPreparationBytes.length > WorldStartSessionExecutionTerminal.MAX_CANONICAL_BYTES) {
      throw new WorldCanonicalInstancePreparationService.PreparationDeniedException(
          "Canonical World preparation input exceeds the Account acquisition bound");
    }
    String exactPreparationDigest = digestPrefixed(exactPreparationBytes);
    UUID acquisitionRequestId = independentRequestId(operationInput, input);
    AcquireStartSessionWorldParticipationRequest request =
        AcquireStartSessionWorldParticipationRequest.newBuilder()
            .setSchemaVersion(1)
            .setAcquisitionRequestId(acquisitionRequestId.toString())
            .setTargetNamespace(trustedNamespace)
            .setOriginalPostAuthorizationTuple(
                ByteString.copyFrom(operationInput.originalPostAuthorizationTuple()))
            .setGameSessionOwnerMutationId(operationInput.gameSessionOwnerMutationId().toString())
            .setGameSessionOwnerAttemptId(operationInput.gameSessionOwnerAttemptId().toString())
            .setGameSessionOwnerFence(operationInput.gameSessionOwnerFence())
            .setCanonicalGameInstanceId(input.canonicalGameInstanceId().toString())
            .setPreparationInputJson(exactPreparationJson)
            .setPreparationInputDigest(exactPreparationDigest)
            .build();

    // The Account RPC is intentionally complete before World opens any intent/claim/owner SQL.
    var response = accountClient.acquire(request);
    if (response == null || !response.hasOriginalAttemptEvidence()) {
      throw new WorldCanonicalInstancePreparationService.PreparationDeniedException(
          "Account returned no retained original Game Session attempt observation");
    }
    var observationMessage = response.getOriginalAttemptEvidence();
    var observationRequest =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromRequest(
            observationMessage.getRequest());
    var observation =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
            observationRequest, observationMessage);
    var identity =
        new WorldCanonicalInstanceExecutionIdentity(
            operationInput.originalPostAuthorizationTuple(),
            canonicalUuid(response.getAccountWorldParticipationId()),
            response.getAccountWorldParticipationFence(),
            operationInput.gameSessionOwnerMutationId(),
            operationInput.gameSessionOwnerAttemptId(),
            operationInput.gameSessionOwnerFence(),
            observation,
            input.canonicalGameInstanceId(),
            exactPreparationJson,
            observationMessage.toByteArray());
    identity.requireMatches(input, operationInput, exactPreparationJson);
    return new AcquiredHeldAuthority(identity);
  }

  private void requireAuthenticatedGameSessionPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    String expected = "spiffe://firemud/ns/" + trustedNamespace + "/sa/game-session-service";
    if (peer == null
        || !peer.isService("game-session-service")
        || !peer.isInNamespace(trustedNamespace)
        || !expected.equals(peer.uri())
        || SessionContext.hasAuthenticatedCallerContext()) {
      throw new SecurityException(
          "Only the exact same-namespace Game Session workload may acquire Account participation");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Account World participation acquisition requires no ambient World transaction");
    }
  }

  private static UUID independentRequestId(
      OriginalStartSessionOperationInput operationInput, Input input) {
    UUID candidate;
    do {
      candidate = UUID.randomUUID();
    } while (candidate.equals(operationInput.gameSessionOwnerMutationId())
        || candidate.equals(operationInput.gameSessionOwnerAttemptId())
        || candidate.equals(input.canonicalGameInstanceId())
        || candidate.equals(operationInput.decodedTuple().reservationOwnerId())
        || candidate.equals(
            operationInput.decodedTuple().preAuthorizationTuple().actor().accountId())
        || candidate.equals(
            operationInput.decodedTuple().preAuthorizationTuple().action().scope().tenantId())
        || candidate.toString().equals(operationInput.decodedTuple().controlPlaneRequestId()));
    return candidate;
  }

  private static UUID canonicalUuid(String value) {
    try {
      UUID parsed = UUID.fromString(Objects.requireNonNull(value, "Account participation ID"));
      if (parsed.equals(new UUID(0L, 0L)) || !parsed.toString().equals(value)) {
        throw new IllegalArgumentException("Account participation ID is not canonical");
      }
      return parsed;
    } catch (RuntimeException malformed) {
      throw new WorldCanonicalInstancePreparationService.PreparationDeniedException(
          "Account returned an invalid World participation identity");
    }
  }

  private static String digestPrefixed(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  /** Local handle only; close never settles/releases durable Account protection. */
  private static final class AcquiredHeldAuthority implements HeldCommitAuthority {
    private final WorldCanonicalInstanceExecutionIdentity identity;
    private boolean closed;

    private AcquiredHeldAuthority(WorldCanonicalInstanceExecutionIdentity identity) {
      this.identity = Objects.requireNonNull(identity, "identity");
    }

    @Override
    public void requireHeld() {
      if (closed || !identity.hasOriginalAttemptEvidence()) {
        throw new WorldCanonicalInstancePreparationService.PreparationDeniedException(
            "Exact Account participation handle is no longer available locally");
      }
    }

    @Override
    public WorldCanonicalInstanceExecutionIdentity executionIdentity() {
      return identity;
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
