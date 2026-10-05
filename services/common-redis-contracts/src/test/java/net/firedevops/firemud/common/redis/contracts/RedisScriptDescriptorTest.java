package net.firedevops.firemud.common.redis.contracts;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class RedisScriptDescriptorTest {

  @Test
  void requiresScriptIdentityResourceDigestAndAllMetadata() {
    assertThrows(
        IllegalArgumentException.class,
        () -> descriptor("game-session.issuer.v1", "../issuer.lua", "a".repeat(64)));
    assertThrows(
        IllegalArgumentException.class,
        () -> descriptor("issuer", "redis/issuer.lua", "a".repeat(64)));
    assertThrows(
        IllegalArgumentException.class,
        () -> descriptor("game-session.issuer.v1", "redis/issuer.lua", "bad-digest"));
    assertThrows(
        IllegalArgumentException.class,
        () -> descriptor("game-session.issuer.v1", "redis/issuer.lua", "a".repeat(64), " "));
  }

  @Test
  void rejectsDuplicateKeyAndArgumentDeclarations() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RedisScriptDescriptor(
                "game-session.issuer.v1",
                "redis/issuer.lua",
                "a".repeat(64),
                "game-session",
                "gamesession_coord_app",
                RedisScriptDescriptor.RedisRole.COORDINATION,
                List.of(
                    new RedisScriptDescriptor.KeySpec(
                        "issuer",
                        "session:auth:",
                        RedisScriptDescriptor.HashTagDeclaration.NOT_REQUIRED),
                    new RedisScriptDescriptor.KeySpec(
                        "issuer",
                        "session:auth:",
                        RedisScriptDescriptor.HashTagDeclaration.NOT_REQUIRED)),
                List.of(new RedisScriptDescriptor.ArgumentSpec("expected")),
                category(),
                outcomes(),
                RedisScriptDescriptor.ResetSensitivity.CLUSTER,
                lossClass(),
                "loss behavior is explicit",
                RedisScriptDescriptor.CompatibilityLevel.COMPATIBLE,
                coexistence(),
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RedisScriptDescriptor(
                "game-session.issuer.v1",
                "redis/issuer.lua",
                "a".repeat(64),
                "game-session",
                "gamesession_coord_app",
                RedisScriptDescriptor.RedisRole.COORDINATION,
                List.of(
                    new RedisScriptDescriptor.KeySpec(
                        "issuer",
                        "session:auth:",
                        RedisScriptDescriptor.HashTagDeclaration.NOT_REQUIRED)),
                List.of(
                    new RedisScriptDescriptor.ArgumentSpec("expected"),
                    new RedisScriptDescriptor.ArgumentSpec("expected")),
                category(),
                outcomes(),
                RedisScriptDescriptor.ResetSensitivity.CLUSTER,
                lossClass(),
                "loss behavior is explicit",
                RedisScriptDescriptor.CompatibilityLevel.COMPATIBLE,
                coexistence(),
                null));
  }

  @Test
  void requiresOutcomesAndCallerPayloadCoexistence() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RedisScriptDescriptor(
                "game-session.issuer.v1",
                "redis/issuer.lua",
                "a".repeat(64),
                "game-session",
                "gamesession_coord_app",
                RedisScriptDescriptor.RedisRole.COORDINATION,
                List.of(
                    new RedisScriptDescriptor.KeySpec(
                        "issuer",
                        "session:auth:",
                        RedisScriptDescriptor.HashTagDeclaration.NOT_REQUIRED)),
                List.of(),
                category(),
                List.of(),
                RedisScriptDescriptor.ResetSensitivity.CLUSTER,
                lossClass(),
                "loss behavior is explicit",
                RedisScriptDescriptor.CompatibilityLevel.COMPATIBLE,
                coexistence(),
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RedisScriptDescriptor(
                "game-session.issuer.v1",
                "redis/issuer.lua",
                "a".repeat(64),
                "game-session",
                "gamesession_coord_app",
                RedisScriptDescriptor.RedisRole.COORDINATION,
                List.of(
                    new RedisScriptDescriptor.KeySpec(
                        "issuer",
                        "session:auth:",
                        RedisScriptDescriptor.HashTagDeclaration.NOT_REQUIRED)),
                List.of(),
                category(),
                outcomes(),
                RedisScriptDescriptor.ResetSensitivity.CLUSTER,
                lossClass(),
                "loss behavior is explicit",
                RedisScriptDescriptor.CompatibilityLevel.COMPATIBLE,
                List.of(),
                null));
  }

  @Test
  void requiresExplicitLegacyPayloadShapeAndNonMutatingFailureOutcomes() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RedisScriptDescriptor(
                "game-session.issuer.v1",
                "redis/issuer.lua",
                "a".repeat(64),
                "game-session",
                "gamesession_coord_app",
                RedisScriptDescriptor.RedisRole.COORDINATION,
                List.of(
                    new RedisScriptDescriptor.KeySpec(
                        "issuer",
                        "session:auth:",
                        RedisScriptDescriptor.HashTagDeclaration.NOT_REQUIRED)),
                List.of(),
                category(),
                outcomes(),
                RedisScriptDescriptor.ResetSensitivity.CLUSTER,
                lossClass(),
                "loss behavior is explicit",
                RedisScriptDescriptor.CompatibilityLevel.COMPATIBLE,
                List.of(new RedisScriptDescriptor.SupportedCoexistence("v1", "legacy")),
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RedisScriptDescriptor.OutcomeSpec(
                "INVALID_ARGS",
                RedisScriptDescriptor.OutcomeCategory.VALIDATION_FAILURE,
                RedisScriptDescriptor.MutationEffect.MUTATING));
  }

  @Test
  void descriptorListAccessorsRemainImmutable() {
    RedisScriptDescriptor descriptor =
        descriptor("game-session.issuer.v1", "redis/issuer.lua", "a".repeat(64));

    assertThrows(UnsupportedOperationException.class, () -> descriptor.keys().clear());
    assertThrows(UnsupportedOperationException.class, () -> descriptor.arguments().clear());
    assertThrows(UnsupportedOperationException.class, () -> descriptor.outcomes().clear());
    assertThrows(
        UnsupportedOperationException.class, () -> descriptor.supportedCoexistence().clear());
  }

  private static RedisScriptDescriptor descriptor(String id, String path, String digest) {
    return descriptor(id, path, digest, "loss behavior is explicit");
  }

  private static RedisScriptDescriptor descriptor(
      String id, String path, String digest, String tailLoss) {
    return new RedisScriptDescriptor(
        id,
        path,
        digest,
        "game-session",
        "gamesession_coord_app",
        RedisScriptDescriptor.RedisRole.COORDINATION,
        List.of(
            new RedisScriptDescriptor.KeySpec(
                "issuer", "session:auth:", RedisScriptDescriptor.HashTagDeclaration.NOT_REQUIRED)),
        List.of(),
        category(),
        outcomes(),
        RedisScriptDescriptor.ResetSensitivity.CLUSTER,
        lossClass(),
        tailLoss,
        RedisScriptDescriptor.CompatibilityLevel.COMPATIBLE,
        coexistence(),
        null);
  }

  private static RedisScriptDescriptor.ScriptCategory category() {
    return RedisScriptDescriptor.ScriptCategory.SESSION_CAS;
  }

  private static List<RedisScriptDescriptor.OutcomeSpec> outcomes() {
    return List.of(
        new RedisScriptDescriptor.OutcomeSpec(
            "UPDATED",
            RedisScriptDescriptor.OutcomeCategory.SUCCESS,
            RedisScriptDescriptor.MutationEffect.MUTATING));
  }

  private static RedisScriptDescriptor.LossClass lossClass() {
    return RedisScriptDescriptor.LossClass.SESSION_LEASE_CACHE_OR_WAKE_UP;
  }

  private static List<RedisScriptDescriptor.SupportedCoexistence> coexistence() {
    return List.of(new RedisScriptDescriptor.SupportedCoexistence("v1", "none"));
  }
}
