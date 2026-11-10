package net.firedevops.firemud.common.account.capacity;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.account.v1.CommitTenantCapacityAdmissionRequest;
import net.firedevops.firemud.account.v1.CommitTenantCapacityAdmissionResponse;
import net.firedevops.firemud.account.v1.TenantCapacityAdmissionAttempt;
import net.firedevops.firemud.account.v1.TenantCapacityAdmissionCommitment;
import net.firedevops.firemud.account.v1.TenantCapacityPlayerContext;
import net.firedevops.firemud.account.v1.TenantCapacityShape;
import net.firedevops.firemud.account.v1.TenantCapacityWorldContext;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Evidence;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Identity;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Operation;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.OwnerContext;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.PlayerContext;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Result;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Shape;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.State;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.WorldContext;

/** Strict bounded content mapping. TLS identity and source authority are never codec inputs. */
public final class TenantCapacityAdmissionGrpcCodec {
  public static final int MAX_REQUEST_WIRE_BYTES = 288 * 1024;
  public static final int MAX_RESPONSE_WIRE_BYTES = 4096;
  public static final long CALL_DEADLINE_SECONDS = 5;

  private TenantCapacityAdmissionGrpcCodec() {}

  public static CommitTenantCapacityAdmissionRequest toRequest(Request value) {
    value.requireStructurallyValid();
    var attempt =
        TenantCapacityAdmissionAttempt.newBuilder()
            .addTenantId(value.identity().tenantId().toString())
            .addRequestId(value.identity().requestId().toString())
            .addAdmissionId(value.identity().admissionId().toString())
            .addAdmissionAttemptId(value.admissionAttemptId().toString())
            .addCapacityRearmFence(value.capacityRearmFence())
            .addTargetNamespace(value.targetNamespace())
            .addOperation(value.operation().name())
            .addPriorShape(shape(value.priorShape()))
            .addRequestedShape(shape(value.requestedShape()))
            .addCanonicalAccountEvidence(ByteString.copyFrom(value.evidence().canonicalBytes()))
            .addMutationDigest(value.mutationDigest());
    if (value.context() instanceof PlayerContext player) attempt.addPlayerContext(player(player));
    else attempt.addWorldContext(world((WorldContext) value.context()));
    var wire =
        CommitTenantCapacityAdmissionRequest.newBuilder()
            .addAttempt(attempt)
            .setCapacityDelta(value.capacityDelta())
            .build();
    clean(wire, MAX_REQUEST_WIRE_BYTES);
    return wire;
  }

  public static Request parseRequest(CommitTenantCapacityAdmissionRequest wire) {
    clean(wire, MAX_REQUEST_WIRE_BYTES);
    if (!wire.hasCapacityDelta() || wire.getCapacityDelta() < 0) throw invalid();
    var attempt = one(wire.getAttemptList());
    clean(attempt, MAX_REQUEST_WIRE_BYTES);
    OwnerContext context;
    if (attempt.getPlayerContextCount() == 1 && attempt.getWorldContextCount() == 0)
      context = player(one(attempt.getPlayerContextList()));
    else if (attempt.getWorldContextCount() == 1 && attempt.getPlayerContextCount() == 0)
      context = world(one(attempt.getWorldContextList()));
    else throw invalid();
    var request =
        new Request(
            new Identity(
                uuid(one(attempt.getTenantIdList())),
                uuid(one(attempt.getRequestIdList())),
                uuid(one(attempt.getAdmissionIdList()))),
            uuid(one(attempt.getAdmissionAttemptIdList())),
            one(attempt.getCapacityRearmFenceList()),
            one(attempt.getTargetNamespaceList()),
            context,
            Operation.valueOf(one(attempt.getOperationList())),
            shape(one(attempt.getPriorShapeList())),
            shape(one(attempt.getRequestedShapeList())),
            Evidence.parse(
                one(attempt.getCanonicalAccountEvidenceList()).toByteArray(), context.owner()),
            wire.getCapacityDelta(),
            one(attempt.getMutationDigestList()));
    request.requireStructurallyValid();
    return request;
  }

  public static CommitTenantCapacityAdmissionResponse toResponse(Request request, Result result) {
    result.requireBinding(request);
    var wire =
        CommitTenantCapacityAdmissionResponse.newBuilder()
            .addCommitment(
                TenantCapacityAdmissionCommitment.newBuilder()
                    .addTenantId(result.identity().tenantId().toString())
                    .addRequestId(result.identity().requestId().toString())
                    .addAdmissionId(result.identity().admissionId().toString())
                    .addAdmissionAttemptId(result.admissionAttemptId().toString())
                    .addCapacityRearmFence(result.capacityRearmFence())
                    .addMutationDigest(result.mutationDigest())
                    .addReservationId(result.reservationId().toString())
                    .addReservationFence(result.reservationFence())
                    .addExpiresAt(result.expiresAt().toString())
                    .addState(result.state().name())
                    .addSnapshotIdentity(result.snapshotIdentity())
                    .addEvaluationIdentity(result.evaluationIdentity()))
            .build();
    clean(wire, MAX_RESPONSE_WIRE_BYTES);
    return wire;
  }

  public static Result requireResponse(
      Request request, CommitTenantCapacityAdmissionResponse wire) {
    clean(wire, MAX_RESPONSE_WIRE_BYTES);
    var result = one(wire.getCommitmentList());
    clean(result, MAX_RESPONSE_WIRE_BYTES);
    String expires = one(result.getExpiresAtList());
    Instant instant = Instant.parse(expires);
    if (!instant.toString().equals(expires)) throw invalid();
    var parsed =
        new Result(
            new Identity(
                uuid(one(result.getTenantIdList())),
                uuid(one(result.getRequestIdList())),
                uuid(one(result.getAdmissionIdList()))),
            uuid(one(result.getAdmissionAttemptIdList())),
            one(result.getCapacityRearmFenceList()),
            one(result.getMutationDigestList()),
            uuid(one(result.getReservationIdList())),
            one(result.getReservationFenceList()),
            instant,
            State.valueOf(one(result.getStateList())),
            one(result.getSnapshotIdentityList()),
            one(result.getEvaluationIdentityList()));
    parsed.requireBinding(request);
    return parsed;
  }

  private static TenantCapacityShape shape(Shape value) {
    return TenantCapacityShape.newBuilder()
        .addActiveSessions(value.activeSessions())
        .addConcurrentGameInstances(value.concurrentGameInstances())
        .addStorageBytes(value.storageBytes())
        .build();
  }

  private static Shape shape(TenantCapacityShape wire) {
    clean(wire, 128);
    return new Shape(
        one(wire.getActiveSessionsList()),
        one(wire.getConcurrentGameInstancesList()),
        one(wire.getStorageBytesList()));
  }

  private static TenantCapacityPlayerContext player(PlayerContext player) {
    return TenantCapacityPlayerContext.newBuilder()
        .addAccountId(player.accountId().toString())
        .addTenantId(player.tenantId().toString())
        .addPlayableStateNamespaceId(player.playableStateNamespaceId().toString())
        .addGameInstanceId(player.gameInstanceId().toString())
        .addCharacterId(player.characterId().toString())
        .addSessionId(player.sessionId().toString())
        .addRealmId(player.realmId().toString())
        .addRequestId(player.requestId().toString())
        .addPlayableStateScope(player.playableStateScope())
        .build();
  }

  private static PlayerContext player(TenantCapacityPlayerContext wire) {
    clean(wire, 1024);
    return new PlayerContext(
        uuid(one(wire.getAccountIdList())),
        uuid(one(wire.getTenantIdList())),
        uuid(one(wire.getPlayableStateNamespaceIdList())),
        uuid(one(wire.getGameInstanceIdList())),
        uuid(one(wire.getCharacterIdList())),
        uuid(one(wire.getSessionIdList())),
        uuid(one(wire.getRealmIdList())),
        uuid(one(wire.getRequestIdList())),
        one(wire.getPlayableStateScopeList()));
  }

  private static TenantCapacityWorldContext world(WorldContext value) {
    return TenantCapacityWorldContext.newBuilder()
        .addTenantId(value.tenantId().toString())
        .addOperationId(value.operationId().toString())
        .addLifecycleId(value.lifecycleId().toString())
        .addGameInstanceId(value.gameInstanceId().toString())
        .addOwnerId(value.ownerId().toString())
        .addOwnerFence(value.ownerFence())
        .build();
  }

  private static WorldContext world(TenantCapacityWorldContext wire) {
    clean(wire, 1024);
    return new WorldContext(
        uuid(one(wire.getTenantIdList())),
        uuid(one(wire.getOperationIdList())),
        uuid(one(wire.getLifecycleIdList())),
        uuid(one(wire.getGameInstanceIdList())),
        uuid(one(wire.getOwnerIdList())),
        one(wire.getOwnerFenceList()));
  }

  private static UUID uuid(String raw) {
    UUID parsed = UUID.fromString(raw);
    if (!parsed.toString().equals(raw)) throw invalid();
    return TenantCapacityAdmission.uuid(parsed);
  }

  private static <T> T one(List<T> values) {
    if (values.size() != 1) throw invalid();
    return values.getFirst();
  }

  private static void clean(Message message, int maximum) {
    if (message == null
        || message.getSerializedSize() > maximum
        || !message.getUnknownFields().asMap().isEmpty()) throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Exact bounded capacity protobuf required");
  }
}
