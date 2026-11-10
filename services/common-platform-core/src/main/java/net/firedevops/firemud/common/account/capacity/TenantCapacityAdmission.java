package net.firedevops.firemud.common.account.capacity;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.authority.RuntimePlayerAdmissionEntitlementEvidence;
import net.firedevops.firemud.common.account.authority.RuntimeTenantOperationEntitlementEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Content contract only. Neither construction nor a digest authenticates Account or an owner. */
public final class TenantCapacityAdmission {
  public static final String SCHEMA_ID = "capacity-admission";
  public static final String SCHEMA_VERSION = "1";
  public static final int MAX_EVIDENCE_BYTES = 256 * 1024;

  private TenantCapacityAdmission() {}

  public enum Owner {
    GAME_SESSION("game-session-service", "maxActiveSessions"),
    WORLD_MANAGEMENT("world-management-service", "maxConcurrentGameInstances");

    private final String service;
    private final String quota;

    Owner(String service, String quota) {
      this.service = service;
      this.quota = quota;
    }

    public String service() {
      return service;
    }

    public String quota() {
      return quota;
    }
  }

  public enum Operation {
    CAPACITY_CREATING,
    NON_EXPANDING_RECOVERY
  }

  public enum State {
    RESERVED,
    FINALIZED,
    RELEASED,
    QUARANTINED_REQUIRES_AUTHORITY_REMEDIATION
  }

  public record Identity(UUID tenantId, UUID requestId, UUID admissionId) {
    public Identity {
      uuid(tenantId);
      uuid(requestId);
      uuid(admissionId);
    }
  }

  /** Complete target resource shape, not the aggregate tenant usage counter. */
  public record Shape(long activeSessions, long concurrentGameInstances, long storageBytes) {
    public Shape {
      if (activeSessions < 0 || concurrentGameInstances < 0 || storageBytes < 0) throw invalid();
    }

    public long units(Owner owner) {
      return owner == Owner.GAME_SESSION ? activeSessions : concurrentGameInstances;
    }
  }

  public sealed interface OwnerContext permits PlayerContext, WorldContext {
    Owner owner();

    UUID tenantId();

    UUID gameInstanceId();
  }

  /** The exact typed PlayerExecutionContext, validated against Account's retained evaluation. */
  public record PlayerContext(
      UUID accountId,
      UUID tenantId,
      UUID playableStateNamespaceId,
      UUID gameInstanceId,
      UUID characterId,
      UUID sessionId,
      UUID realmId,
      UUID requestId,
      String playableStateScope)
      implements OwnerContext {
    public PlayerContext {
      uuid(accountId);
      uuid(tenantId);
      uuid(playableStateNamespaceId);
      uuid(gameInstanceId);
      uuid(characterId);
      uuid(sessionId);
      uuid(realmId);
      uuid(requestId);
      if (!"SHARED".equals(playableStateScope) && !"PRIVATE".equals(playableStateScope))
        throw invalid();
    }

    @Override
    public Owner owner() {
      return Owner.GAME_SESSION;
    }
  }

  /** Separate tenant/operation lifecycle context; never inferred from a player context. */
  public record WorldContext(
      UUID tenantId,
      UUID operationId,
      UUID lifecycleId,
      UUID gameInstanceId,
      UUID ownerId,
      long ownerFence)
      implements OwnerContext {
    public WorldContext {
      uuid(tenantId);
      uuid(operationId);
      uuid(lifecycleId);
      uuid(gameInstanceId);
      uuid(ownerId);
      if (ownerFence <= 0) throw invalid();
    }

    @Override
    public Owner owner() {
      return Owner.WORLD_MANAGEMENT;
    }
  }

  /** Exact, strictly decoded existing Account bundle. Bytes are never replaced by a subset/hash. */
  public static final class Evidence {
    private final byte[] bytes;
    private final Owner owner;
    private final Map<String, Object> fields;

    private Evidence(byte[] bytes, Owner owner) {
      if (bytes == null || bytes.length == 0 || bytes.length > MAX_EVIDENCE_BYTES) throw invalid();
      this.owner = Objects.requireNonNull(owner);
      this.bytes = bytes.clone();
      fields =
          owner == Owner.GAME_SESSION
              ? RuntimePlayerAdmissionEntitlementEvidence.parseCanonical(bytes).fields()
              : RuntimeTenantOperationEntitlementEvidence.parseCanonical(bytes).fields();
    }

    public static Evidence player(RuntimePlayerAdmissionEntitlementEvidence value) {
      return new Evidence(Objects.requireNonNull(value).canonicalBytes(), Owner.GAME_SESSION);
    }

    public static Evidence world(RuntimeTenantOperationEntitlementEvidence value) {
      return new Evidence(Objects.requireNonNull(value).canonicalBytes(), Owner.WORLD_MANAGEMENT);
    }

    public static Evidence parse(byte[] bytes, Owner owner) {
      return new Evidence(bytes, owner);
    }

    public byte[] canonicalBytes() {
      return bytes.clone();
    }

    public Map<String, Object> fields() {
      return owner == Owner.GAME_SESSION
          ? RuntimePlayerAdmissionEntitlementEvidence.parseCanonical(bytes).fields()
          : RuntimeTenantOperationEntitlementEvidence.parseCanonical(bytes).fields();
    }

    public Owner owner() {
      return owner;
    }

    public UUID tenantId() {
      return UUID.fromString(text("tenantId"));
    }

    public String snapshotIdentity() {
      return text("snapshotIdentity");
    }

    public String evaluationIdentity() {
      return text("evaluationIdentity");
    }

    public String callerWorkload() {
      return text("callerWorkload");
    }

    public Instant evaluatedAt() {
      return Instant.parse(text("evaluatedAt"));
    }

    public Instant expiresAt() {
      Instant freshness = evaluatedAt().plusSeconds(15);
      if (owner == Owner.GAME_SESSION) {
        Instant validity = Instant.parse(text("validUntil"));
        if (validity.isBefore(freshness)) return validity;
      }
      return freshness;
    }

    public void requireCurrent(Instant now) {
      if (now.isBefore(evaluatedAt()) || !now.isBefore(expiresAt())) throw invalid();
    }

    public long quota() {
      Object raw = fields.get("quotas");
      if (!(raw instanceof Map<?, ?> quotas)
          || !(quotas.get(owner.quota()) instanceof String value)) throw invalid();
      return decimal(value);
    }

    public boolean permitsCreation() {
      return Boolean.TRUE.equals(fields.get("gameplayAvailable"))
          && Boolean.TRUE.equals(
              fields.get(
                  owner == Owner.GAME_SESSION
                      ? "allowNewGameplayBindings"
                      : "allowNewInstanceStarts"));
    }

    private String text(String field) {
      if (!(fields.get(field) instanceof String value)) throw invalid();
      return value;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Evidence value
          && owner == value.owner
          && Arrays.equals(bytes, value.bytes);
    }

    @Override
    public int hashCode() {
      return 31 * owner.hashCode() + Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
      return "CapacityEvidence[redacted]";
    }
  }

  public record Request(
      Identity identity,
      UUID admissionAttemptId,
      long capacityRearmFence,
      String targetNamespace,
      OwnerContext context,
      Operation operation,
      Shape priorShape,
      Shape requestedShape,
      Evidence evidence,
      Long capacityDelta,
      String mutationDigest) {
    public Request {
      Objects.requireNonNull(identity);
      uuid(admissionAttemptId);
      if (capacityRearmFence <= 0 || !GrpcPeerIdentity.isValidNamespace(targetNamespace))
        throw invalid();
      Objects.requireNonNull(context);
      Objects.requireNonNull(operation);
      Objects.requireNonNull(priorShape);
      Objects.requireNonNull(requestedShape);
      Objects.requireNonNull(evidence);
      if (!identity.tenantId().equals(context.tenantId())
          || !identity.tenantId().equals(evidence.tenantId())
          || context.owner() != evidence.owner()
          || !expectedWorkload(targetNamespace, context.owner()).equals(evidence.callerWorkload()))
        throw invalid();
      if (capacityDelta != null && capacityDelta < 0) throw invalid();
      if (mutationDigest != null && !mutationDigest.matches("[0-9a-f]{64}")) throw invalid();
    }

    public Request withDigest() {
      return new Request(
          identity,
          admissionAttemptId,
          capacityRearmFence,
          targetNamespace,
          context,
          operation,
          priorShape,
          requestedShape,
          evidence,
          capacityDelta,
          CapacityAdmissionDigest.digest(this));
    }

    /** Strong guard for rearm and selected-owner execution; content alone grants no authority. */
    public void requireCommittable() {
      requireStructurallyValid();
      if (operation == Operation.CAPACITY_CREATING && !evidence.permitsCreation()) throw invalid();
    }

    /** Account resolves exact replay/conflict and current policy after this content check. */
    public void requireStructurallyValid() {
      if (capacityDelta == null
          || mutationDigest == null
          || !mutationDigest.equals(CapacityAdmissionDigest.digest(this))
          || !identity.requestId().toString().equals(evidence.fields().get("requestId")))
        throw invalid();
      long net =
          Math.subtractExact(
              requestedShape.units(context.owner()), priorShape.units(context.owner()));
      if (operation == Operation.CAPACITY_CREATING) {
        if (capacityDelta <= 0 || net <= 0 || capacityDelta > net) throw invalid();
        // A scalar commitment cannot hide expansion in another resource dimension.
        if (context.owner() == Owner.GAME_SESSION
            ? requestedShape.concurrentGameInstances() > priorShape.concurrentGameInstances()
            : requestedShape.activeSessions() > priorShape.activeSessions()) throw invalid();
        if (requestedShape.storageBytes() > priorShape.storageBytes()) throw invalid();
      } else if (capacityDelta != 0
          || requestedShape.activeSessions() > priorShape.activeSessions()
          || requestedShape.concurrentGameInstances() > priorShape.concurrentGameInstances()
          || requestedShape.storageBytes() > priorShape.storageBytes()) throw invalid();
      if (context instanceof PlayerContext player) {
        if (!identity.requestId().equals(player.requestId())) throw invalid();
        var fields = evidence.fields();
        if (!player.accountId().toString().equals(fields.get("callerAccountId"))
            || !(fields.get("target") instanceof Map<?, ?> target)
            || !targetNamespace.equals(target.get("targetNamespace"))
            || !player.realmId().toString().equals(target.get("realmId"))
            || !player
                .playableStateNamespaceId()
                .toString()
                .equals(target.get("playableStateNamespaceId"))
            || !player.gameInstanceId().toString().equals(target.get("gameInstanceId"))
            || !player.playableStateScope().equals(target.get("playableStateScope")))
          throw invalid();
      } else if (!identity.requestId().equals(((WorldContext) context).operationId()))
        throw invalid();
    }

    @Override
    public String toString() {
      return "TenantCapacityRequest[redacted]";
    }
  }

  public record Result(
      Identity identity,
      UUID admissionAttemptId,
      long capacityRearmFence,
      String mutationDigest,
      UUID reservationId,
      long reservationFence,
      Instant expiresAt,
      State state,
      String snapshotIdentity,
      String evaluationIdentity) {
    public Result {
      Objects.requireNonNull(identity);
      uuid(admissionAttemptId);
      uuid(reservationId);
      if (capacityRearmFence <= 0
          || reservationFence <= 0
          || mutationDigest == null
          || !mutationDigest.matches("[0-9a-f]{64}")) throw invalid();
      Objects.requireNonNull(expiresAt);
      Objects.requireNonNull(state);
      if (snapshotIdentity == null
          || !snapshotIdentity.matches("sha256:[0-9a-f]{64}")
          || evaluationIdentity == null
          || !evaluationIdentity.matches("sha256:[0-9a-f]{64}")) throw invalid();
    }

    public void requireBinding(Request request) {
      if (!identity.equals(request.identity())
          || !admissionAttemptId.equals(request.admissionAttemptId())
          || capacityRearmFence != request.capacityRearmFence()
          || !mutationDigest.equals(request.mutationDigest())
          || !snapshotIdentity.equals(request.evidence().snapshotIdentity())
          || !evaluationIdentity.equals(request.evidence().evaluationIdentity())
          || !expiresAt.isAfter(request.evidence().evaluatedAt())
          || expiresAt.isAfter(request.evidence().expiresAt())) throw invalid();
    }
  }

  public static long decimal(String value) {
    if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw invalid();
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException overflow) {
      throw invalid();
    }
  }

  public static String expectedWorkload(String namespace, Owner owner) {
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) throw invalid();
    return "spiffe://firemud/ns/" + namespace + "/sa/" + owner.service();
  }

  public static UUID uuid(UUID value) {
    if (value == null || value.equals(new UUID(0, 0))) throw invalid();
    return value;
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Exact bounded tenant capacity admission required");
  }
}
