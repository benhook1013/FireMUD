package net.firedevops.firemud.common.redis.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class RedisScriptDescriptorTest {
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
        List.of(new RedisScriptDescriptor.SupportedCoexistence("v1", "v1")),
        null);
  }
}
