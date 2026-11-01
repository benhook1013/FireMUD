package net.firedevops.firemud.common.redis.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class RedisScriptDescriptorTest {
  @Test
  void fullKeyAnchorRequiresExactlyOneOrderedExplicitCompanion() {
    var anchor =
        new RedisScriptDescriptor.KeySpec(
            "token",
            "session:auth:token:",
            RedisScriptDescriptor.HashTagDeclaration.FULL_KEY_ANCHOR);
    var companion =
        new RedisScriptDescriptor.KeySpec(
            "receipt",
            "session:auth:creation-admission:",
            RedisScriptDescriptor.HashTagDeclaration.EXACT_FULL_KEY_ANCHOR_TAG);
    assertThat(descriptor(null, null, List.of(anchor, companion), null, null).keys())
        .containsExactly(anchor, companion);
    var ordinary =
        new RedisScriptDescriptor.KeySpec(
            "ordinary", "session:other:", RedisScriptDescriptor.HashTagDeclaration.REQUIRED);
    var secondOrdinary =
        new RedisScriptDescriptor.KeySpec(
            "other", "session:another:", RedisScriptDescriptor.HashTagDeclaration.REQUIRED);
    assertThat(descriptor(null, null, List.of(ordinary, secondOrdinary), null, null).keys())
        .containsExactly(ordinary, secondOrdinary);
    for (var invalid :
        List.of(
            List.of(anchor),
            List.of(companion),
            List.of(companion, anchor),
            List.of(anchor, ordinary),
            List.of(ordinary, companion),
            List.of(anchor, companion, ordinary))) {
      assertThatThrownBy(() -> descriptor(null, null, invalid, null, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("full-key anchor slot binding");
    }
  }

  @Test
  void acceptsValidDescriptorAndReturnsImmutableDeclarationCopies() {
    RedisScriptDescriptor descriptor = descriptor();

    assertThat(descriptor.scriptId()).isEqualTo("account.control-ui.v1");
    assertThat(descriptor.resourcePath()).isEqualTo("redis/account/control-ui.lua");
    assertThat(descriptor.keys())
        .containsExactly(
            new RedisScriptDescriptor.KeySpec(
                "record",
                "session:control:",
                RedisScriptDescriptor.HashTagDeclaration.NOT_REQUIRED));
    assertThat(descriptor.arguments())
        .containsExactly(new RedisScriptDescriptor.ArgumentSpec("requestId"));
    assertThat(descriptor.outcomes()).hasSize(2);
    assertThat(descriptor.supportedCoexistence())
        .containsExactly(new RedisScriptDescriptor.SupportedCoexistence("v1", "v1"));
  }

  @Test
  void rejectsMalformedResourceAndDigest() {
    assertThatThrownBy(() -> descriptor("account/control-ui.lua", "a".repeat(64), null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("resourcePath");
    assertThatThrownBy(
            () -> descriptor("redis/account/control-ui.lua", "A".repeat(64), null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sha256");
  }

  @Test
  void rejectsMalformedAndDuplicateKeyIdentities() {
    assertThatThrownBy(
            () ->
                descriptor(
                    null,
                    null,
                    List.of(
                        new RedisScriptDescriptor.KeySpec(
                            "1record",
                            "session:control:",
                            RedisScriptDescriptor.HashTagDeclaration.NOT_REQUIRED)),
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("KEYS name");
    assertThatThrownBy(
            () ->
                descriptor(
                    null,
                    null,
                    List.of(
                        new RedisScriptDescriptor.KeySpec(
                            "record",
                            "session/control",
                            RedisScriptDescriptor.HashTagDeclaration.NOT_REQUIRED)),
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ownedPrefix");
    var key =
        new RedisScriptDescriptor.KeySpec(
            "record", "session:control:", RedisScriptDescriptor.HashTagDeclaration.NOT_REQUIRED);
    assertThatThrownBy(() -> descriptor(null, null, List.of(key, key), null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate KEYS");

    var requiredKey =
        new RedisScriptDescriptor.KeySpec(
            "other", "session:other:", RedisScriptDescriptor.HashTagDeclaration.REQUIRED);
    assertThatThrownBy(() -> descriptorWithCoexistence(null, null, List.of(key, requiredKey)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("multi-key script must require a hash tag");
  }

  @Test
  void rejectsMalformedAndDuplicateArgumentAndOutcomeIdentities() {
    assertThatThrownBy(
            () ->
                descriptor(
                    null,
                    null,
                    null,
                    List.of(new RedisScriptDescriptor.ArgumentSpec("request-id")),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ARGV name");
    var argument = new RedisScriptDescriptor.ArgumentSpec("requestId");
    assertThatThrownBy(() -> descriptor(null, null, null, List.of(argument, argument), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate ARGV");

    assertThatThrownBy(
            () ->
                descriptor(
                    null,
                    null,
                    null,
                    null,
                    List.of(
                        new RedisScriptDescriptor.OutcomeSpec(
                            "created",
                            RedisScriptDescriptor.OutcomeCategory.SUCCESS,
                            RedisScriptDescriptor.MutationEffect.MUTATING))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("outcome code");
    var outcome =
        new RedisScriptDescriptor.OutcomeSpec(
            "OK",
            RedisScriptDescriptor.OutcomeCategory.SUCCESS,
            RedisScriptDescriptor.MutationEffect.MUTATING);
    assertThatThrownBy(() -> descriptor(null, null, null, null, List.of(outcome, outcome)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate outcome");

    assertThatThrownBy(
            () ->
                new RedisScriptDescriptor.OutcomeSpec(
                    "CONFLICT",
                    RedisScriptDescriptor.OutcomeCategory.VALIDATION_FAILURE,
                    RedisScriptDescriptor.MutationEffect.MUTATING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be non-mutating");
  }

  @Test
  void rejectsInconsistentLegacyPayloadMetadataAndDuplicateCoexistence() {
    var legacy = new RedisScriptDescriptor.SupportedCoexistence("v1", "legacy");
    var versioned = new RedisScriptDescriptor.SupportedCoexistence("v1", "v1");

    assertThatThrownBy(() -> descriptorWithCoexistence(List.of(legacy), null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("legacyPayloadShape is required");
    assertThatThrownBy(() -> descriptorWithCoexistence(List.of(versioned), "old fields", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("only valid when a legacy payload is supported");
    assertThatThrownBy(() -> descriptorWithCoexistence(List.of(versioned, versioned), null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate caller/payload coexistence");
  }

  private static RedisScriptDescriptor descriptor() {
    return descriptor(null, null, null, null, null);
  }

  private static RedisScriptDescriptor descriptor(
      String resourcePath,
      String sha256,
      List<RedisScriptDescriptor.KeySpec> keys,
      List<RedisScriptDescriptor.ArgumentSpec> arguments,
      List<RedisScriptDescriptor.OutcomeSpec> outcomes) {
    return descriptorWithCoexistence(
        List.of(new RedisScriptDescriptor.SupportedCoexistence("v1", "v1")),
        null,
        keys,
        resourcePath,
        sha256,
        arguments,
        outcomes);
  }

  private static RedisScriptDescriptor descriptorWithCoexistence(
      List<RedisScriptDescriptor.SupportedCoexistence> supportedCoexistence,
      String legacyPayloadShape,
      List<RedisScriptDescriptor.KeySpec> keys) {
    return descriptorWithCoexistence(
        supportedCoexistence, legacyPayloadShape, keys, null, null, null, null);
  }

  private static RedisScriptDescriptor descriptorWithCoexistence(
      List<RedisScriptDescriptor.SupportedCoexistence> supportedCoexistence,
      String legacyPayloadShape,
      List<RedisScriptDescriptor.KeySpec> keys,
      String resourcePath,
      String sha256,
      List<RedisScriptDescriptor.ArgumentSpec> arguments,
      List<RedisScriptDescriptor.OutcomeSpec> outcomes) {
    return new RedisScriptDescriptor(
        "account.control-ui.v1",
        resourcePath == null ? "redis/account/control-ui.lua" : resourcePath,
        sha256 == null ? "a".repeat(64) : sha256,
        "account-service",
        "account_coord_app",
        RedisScriptDescriptor.RedisRole.COORDINATION,
        keys == null
            ? List.of(
                new RedisScriptDescriptor.KeySpec(
                    "record",
                    "session:control:",
                    RedisScriptDescriptor.HashTagDeclaration.NOT_REQUIRED))
            : keys,
        arguments == null
            ? List.of(new RedisScriptDescriptor.ArgumentSpec("requestId"))
            : arguments,
        RedisScriptDescriptor.ScriptCategory.SESSION_CAS,
        outcomes == null
            ? List.of(
                new RedisScriptDescriptor.OutcomeSpec(
                    "OK",
                    RedisScriptDescriptor.OutcomeCategory.SUCCESS,
                    RedisScriptDescriptor.MutationEffect.MUTATING),
                new RedisScriptDescriptor.OutcomeSpec(
                    "CONFLICT",
                    RedisScriptDescriptor.OutcomeCategory.VALIDATION_FAILURE,
                    RedisScriptDescriptor.MutationEffect.NON_MUTATING))
            : outcomes,
        RedisScriptDescriptor.ResetSensitivity.CLUSTER,
        RedisScriptDescriptor.LossClass.SESSION_LEASE_CACHE_OR_WAKE_UP,
        "A lost projection denies the operation until owner recovery.",
        RedisScriptDescriptor.CompatibilityLevel.REQUIRES_CLUSTER_RESET,
        supportedCoexistence == null
            ? List.of(new RedisScriptDescriptor.SupportedCoexistence("v1", "v1"))
            : supportedCoexistence,
        legacyPayloadShape);
  }
}
