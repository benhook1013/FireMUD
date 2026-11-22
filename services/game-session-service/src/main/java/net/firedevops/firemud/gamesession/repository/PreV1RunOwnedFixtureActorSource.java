package net.firedevops.firemud.gamesession.repository;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.prev1.PreV1GateWaivers;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRuntimeActorReference;
import net.firedevops.firemud.gamesession.client.PreV1RunOwnedFixtureActorReference;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;

/**
 * Pre-v1 gate waiver (ADR 0185): the run-owned fixture's actor, standing in for Entity's preseeded
 * assignment. Each account gets one deterministic character, {@code characterId = UUIDv5(NAMESPACE,
 * accountId)}, with a derived assignment id, intent digest and runtime entity id. Nothing is read
 * from Entity; a real fixture assignment is owed before v1.
 */
final class PreV1RunOwnedFixtureActorSource
    implements CanonicalGameplayRegionBindingBridgeOwner.ActorSource {
  static final UUID NAMESPACE = UUID.fromString("6902fca9-8a6b-4f58-883e-452bf96b4b0f");
  private static final long MAX_RUNTIME_ENTITY_ID = (1L << 53) - 1L;

  private final PreV1GateWaivers waivers;
  private final String workloadNamespace;

  PreV1RunOwnedFixtureActorSource(PreV1GateWaivers waivers, String workloadNamespace) {
    if (waivers == null || !waivers.enabled()) {
      throw new IllegalStateException("Run-owned fixture actor stand-in requires the waivers");
    }
    if (workloadNamespace == null || workloadNamespace.isBlank()) {
      throw new IllegalArgumentException("Exact Game Session workload namespace required");
    }
    this.waivers = waivers;
    this.workloadNamespace = workloadNamespace;
  }

  /** The deterministic actor identities the PLAY composition puts in its World request. */
  record Identity(UUID characterId, UUID assignmentId, String intentDigest, long runtimeEntityId) {}

  static Identity identity(UUID accountId, PreseededActorAssignmentExpectedTarget target) {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(target, "target");
    UUID characterId = characterId(accountId);
    UUID assignmentId = uuidV5(NAMESPACE, "assignment|" + accountId);
    String intentDigest =
        sha256Hex(
            "pre-v1-run-owned-fixture-actor-intent/v1".getBytes(StandardCharsets.US_ASCII),
            accountId.toString().getBytes(StandardCharsets.US_ASCII),
            characterId.toString().getBytes(StandardCharsets.US_ASCII),
            target.toByteArray());
    UUID entity =
        uuidV5(NAMESPACE, "runtime-entity|" + accountId + "|" + target.getGameInstanceId());
    long runtimeEntityId =
        (entity.getMostSignificantBits() ^ entity.getLeastSignificantBits())
            & MAX_RUNTIME_ENTITY_ID;
    return new Identity(
        characterId, assignmentId, intentDigest, runtimeEntityId == 0L ? 1L : runtimeEntityId);
  }

  static UUID characterId(UUID accountId) {
    return uuidV5(NAMESPACE, Objects.requireNonNull(accountId, "accountId").toString());
  }

  @Override
  public CanonicalGameplayRuntimeActorReference read(
      CanonicalInitialPlayerLocationRequest request) {
    Objects.requireNonNull(request, "request");
    final Identity expected;
    try {
      expected =
          identity(UUID.fromString(request.getCanonicalAccountUuid()), request.getExpectedTarget());
    } catch (IllegalArgumentException malformed) {
      throw unavailable();
    }
    if (request.getSchemaVersion() != 1
        || request.hasPlayerCreatedOrigin()
        || !request.hasExpectedTarget()
        || !expected.characterId().toString().equals(request.getCharacterUuid())
        || !expected.assignmentId().toString().equals(request.getAssignmentUuid())
        || !expected.intentDigest().equals(request.getAssignmentIntentDigest())) {
      throw unavailable();
    }
    return PreV1RunOwnedFixtureActorReference.standIn(
        waivers, workloadNamespace, request, expected.runtimeEntityId());
  }

  static UUID uuidV5(UUID namespace, String name) {
    try {
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      sha1.update(
          ByteBuffer.allocate(16)
              .putLong(namespace.getMostSignificantBits())
              .putLong(namespace.getLeastSignificantBits())
              .array());
      byte[] value = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
      value[6] = (byte) ((value[6] & 0x0f) | 0x50);
      value[8] = (byte) ((value[8] & 0x3f) | 0x80);
      ByteBuffer bytes = ByteBuffer.wrap(value);
      return new UUID(bytes.getLong(), bytes.getLong());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JVM does not provide SHA-1", impossible);
    }
  }

  private static String sha256Hex(byte[]... parts) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (byte[] part : parts) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(part.length).array());
        digest.update(part);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JVM does not provide SHA-256", impossible);
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException(
        "Run-owned fixture actor stand-in requires its exact derived World request");
  }
}
