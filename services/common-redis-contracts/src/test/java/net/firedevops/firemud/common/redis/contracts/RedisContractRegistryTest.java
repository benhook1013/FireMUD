package net.firedevops.firemud.common.redis.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RedisContractRegistryTest {

  private static final String ISSUER_PREFIX = "session:game:auth:issuer-generation:v1:";
  private static final String ISSUER_KEY = ISSUER_PREFIX + "current";
  private static final RedisScriptDescriptor ISSUER_DESCRIPTOR = issuerDescriptor();

  @Test
  void preparesRegisteredSingleKeyInvocation() {
    RedisInvocationContract invocation = issuerRegistry().prepareInvocation(issuerRequest());

    assertEquals(ISSUER_DESCRIPTOR, invocation.descriptor());
    assertEquals(List.of(ISSUER_KEY), invocation.keys());
    assertEquals(List.of("12", "13"), invocation.arguments());
    assertTrue(invocation.clusterSlot().isEmpty());
  }

  @Test
  void preparesTaggedMultiKeyInvocationAndReportsClusterSlot() {
    RedisScriptDescriptor descriptor = tickDescriptor();
    RedisContractRegistry registry =
        new RedisContractRegistry(
            List.of(prefix("game-session", "gamesession_coord_app", "tick:")), List.of(descriptor));
    RedisContractRegistry.InvocationRequest request =
        new RedisContractRegistry.InvocationRequest(
            descriptor.scriptId(),
            descriptor.resourcePath(),
            descriptor.sha256(),
            descriptor.owner(),
            descriptor.principal(),
            descriptor.role(),
            List.of("tick:{region-one}:lease", "tick:{region-one}:meta"),
            List.of("41"));

    RedisInvocationContract invocation = registry.prepareInvocation(request);

    assertEquals(
        RedisContractRegistry.clusterSlot("tick:{region-one}:lease"),
        invocation.clusterSlot().orElseThrow());
    assertEquals(
        invocation.clusterSlot(),
        java.util.OptionalInt.of(RedisContractRegistry.clusterSlot("tick:{region-one}:meta")));
  }

  @Test
  void clusterSlotUsesRedisCrc16XmodemKnownVector() {
    assertEquals(12739, RedisContractRegistry.clusterSlot("key{123456789}"));
  }

  @Test
  void rejectsWrongRedisRole() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            issuerRegistry()
                .prepareInvocation(
                    request(
                        "game-session",
                        "gamesession_coord_app",
                        RedisScriptDescriptor.RedisRole.CACHE_RATE_LIMIT,
                        ISSUER_DESCRIPTOR.sha256(),
                        ISSUER_KEY,
                        List.of("12", "13"))));
  }

  @Test
  void rejectsWrongOwner() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            issuerRegistry()
                .prepareInvocation(
                    request(
                        "account",
                        "gamesession_coord_app",
                        RedisScriptDescriptor.RedisRole.COORDINATION,
                        ISSUER_DESCRIPTOR.sha256(),
                        ISSUER_KEY,
                        List.of("12", "13"))));
  }

  @Test
  void rejectsWrongPrincipal() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            issuerRegistry()
                .prepareInvocation(
                    request(
                        "game-session",
                        "account_coord_app",
                        RedisScriptDescriptor.RedisRole.COORDINATION,
                        ISSUER_DESCRIPTOR.sha256(),
                        ISSUER_KEY,
                        List.of("12", "13"))));
  }

  @Test
  void rejectsWrongDigestAndResourcePath() {
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          RedisContractRegistry.InvocationRequest valid = issuerRequest();
          issuerRegistry()
              .prepareInvocation(
                  new RedisContractRegistry.InvocationRequest(
                      valid.scriptId(),
                      valid.resourcePath(),
                      "b".repeat(64),
                      valid.owner(),
                      valid.principal(),
                      valid.role(),
                      valid.keys(),
                      valid.arguments()));
        });
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          RedisContractRegistry.InvocationRequest valid = issuerRequest();
          issuerRegistry()
              .prepareInvocation(
                  new RedisContractRegistry.InvocationRequest(
                      valid.scriptId(),
                      "redis/auth/other.lua",
                      valid.sha256(),
                      valid.owner(),
                      valid.principal(),
                      valid.role(),
                      valid.keys(),
                      valid.arguments()));
        });
  }

  @Test
  void rejectsUnknownScriptIdentity() {
    RedisContractRegistry.InvocationRequest valid = issuerRequest();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            issuerRegistry()
                .prepareInvocation(
                    new RedisContractRegistry.InvocationRequest(
                        "game-session.issuer-generation.v2",
                        valid.resourcePath(),
                        valid.sha256(),
                        valid.owner(),
                        valid.principal(),
                        valid.role(),
                        valid.keys(),
                        valid.arguments())));
  }

  @Test
  void rejectsUnownedKeyPrefix() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            issuerRegistry()
                .prepareInvocation(
                    request(
                        "game-session",
                        "gamesession_coord_app",
                        RedisScriptDescriptor.RedisRole.COORDINATION,
                        ISSUER_DESCRIPTOR.sha256(),
                        "session:game:auth:another-family:key",
                        List.of("12", "13"))));
  }

  @Test
  void rejectsMissingAndMalformedRequiredHashTags() {
    RedisContractRegistry registry = tickRegistry();
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.prepareInvocation(tickRequest("tick:lease", "tick:meta")));
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.prepareInvocation(tickRequest("tick:{}:lease", "tick:{}:meta")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            registry.prepareInvocation(
                tickRequest("tick:{region-one:lease", "tick:{region-one:meta")));
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.prepareInvocation(tickRequest("tick:{{scope}:lease", "tick:{{scope}:meta")));
  }

  @Test
  void rejectsMultiKeyCrossSlotInvocation() {
    RedisContractRegistry registry = tickRegistry();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            registry.prepareInvocation(
                tickRequest("tick:{region-one}:lease", "tick:{region-two}:meta")));
  }

  @Test
  void rejectsKeysAndArgumentsArityMismatch() {
    RedisContractRegistry registry = issuerRegistry();
    RedisContractRegistry.InvocationRequest valid = issuerRequest();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            registry.prepareInvocation(
                new RedisContractRegistry.InvocationRequest(
                    valid.scriptId(),
                    valid.resourcePath(),
                    valid.sha256(),
                    valid.owner(),
                    valid.principal(),
                    valid.role(),
                    List.of(),
                    valid.arguments())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            registry.prepareInvocation(
                new RedisContractRegistry.InvocationRequest(
                    valid.scriptId(),
                    valid.resourcePath(),
                    valid.sha256(),
                    valid.owner(),
                    valid.principal(),
                    valid.role(),
                    valid.keys(),
                    List.of("12"))));
  }

  @Test
  void rejectsDuplicateAndConflictingPrefixOwnership() {
    RedisContractRegistry.PrefixDeclaration declaration =
        prefix("game-session", "gamesession_coord_app", ISSUER_PREFIX);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RedisContractRegistry(
                List.of(declaration, declaration), List.of(ISSUER_DESCRIPTOR)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RedisContractRegistry(
                List.of(declaration, prefix("account", "account_coord_app", "session:game:auth:")),
                List.of(ISSUER_DESCRIPTOR)));
  }

  @Test
  void rejectsDescriptorWhoseOwnerPrincipalOrRoleDoesNotOwnItsPrefix() {
    RedisScriptDescriptor wrongOwner =
        descriptor(
            "game-session.issuer-generation.v1",
            "redis/auth/issuer_generation.lua",
            "a".repeat(64),
            "account",
            "account_coord_app",
            RedisScriptDescriptor.RedisRole.COORDINATION,
            List.of(
                new RedisScriptDescriptor.KeySpec(
                    "issuer",
                    ISSUER_PREFIX,
                    RedisScriptDescriptor.HashTagDeclaration.NOT_REQUIRED)),
            List.of("expectedGeneration", "nextGeneration"),
            "tail-loss behavior is explicit");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RedisContractRegistry(
                List.of(prefix("game-session", "gamesession_coord_app", ISSUER_PREFIX)),
                List.of(wrongOwner)));
  }

  @Test
  void defensivelyCopiesInvocationInputs() {
    RedisContractRegistry registry = issuerRegistry();
    List<String> keys = new ArrayList<>(List.of(ISSUER_KEY));
    List<String> arguments = new ArrayList<>(List.of("12", "13"));
    RedisContractRegistry.InvocationRequest request =
        new RedisContractRegistry.InvocationRequest(
            ISSUER_DESCRIPTOR.scriptId(),
            ISSUER_DESCRIPTOR.resourcePath(),
            ISSUER_DESCRIPTOR.sha256(),
            ISSUER_DESCRIPTOR.owner(),
            ISSUER_DESCRIPTOR.principal(),
            ISSUER_DESCRIPTOR.role(),
            keys,
            arguments);
    keys.clear();
    arguments.clear();

    assertEquals(List.of(ISSUER_KEY), request.keys());
    assertEquals(List.of("12", "13"), request.arguments());
    assertThrows(UnsupportedOperationException.class, () -> request.keys().add("other"));
    assertThrows(UnsupportedOperationException.class, () -> request.arguments().add("14"));

    RedisInvocationContract invocation = registry.prepareInvocation(request);

    assertEquals(List.of(ISSUER_KEY), invocation.keys());
    assertEquals(List.of("12", "13"), invocation.arguments());
    assertThrows(UnsupportedOperationException.class, () -> invocation.keys().add("other"));
    assertFalse(invocation.clusterSlot().isPresent());
  }

  private static RedisContractRegistry issuerRegistry() {
    return new RedisContractRegistry(
        List.of(prefix("game-session", "gamesession_coord_app", ISSUER_PREFIX)),
        List.of(ISSUER_DESCRIPTOR));
  }

  private static RedisContractRegistry tickRegistry() {
    return new RedisContractRegistry(
        List.of(prefix("game-session", "gamesession_coord_app", "tick:")),
        List.of(tickDescriptor()));
  }

  private static RedisContractRegistry.InvocationRequest issuerRequest() {
    return request(
        "game-session",
        "gamesession_coord_app",
        RedisScriptDescriptor.RedisRole.COORDINATION,
        ISSUER_DESCRIPTOR.sha256(),
        ISSUER_KEY,
        List.of("12", "13"));
  }

  private static RedisContractRegistry.InvocationRequest request(
      String owner,
      String principal,
      RedisScriptDescriptor.RedisRole role,
      String digest,
      String key,
      List<String> arguments) {
    return new RedisContractRegistry.InvocationRequest(
        ISSUER_DESCRIPTOR.scriptId(),
        ISSUER_DESCRIPTOR.resourcePath(),
        digest,
        owner,
        principal,
        role,
        List.of(key),
        arguments);
  }

  private static RedisContractRegistry.InvocationRequest tickRequest(
      String firstKey, String secondKey) {
    RedisScriptDescriptor descriptor = tickDescriptor();
    return new RedisContractRegistry.InvocationRequest(
        descriptor.scriptId(),
        descriptor.resourcePath(),
        descriptor.sha256(),
        descriptor.owner(),
        descriptor.principal(),
        descriptor.role(),
        List.of(firstKey, secondKey),
        List.of("41"));
  }

  private static RedisContractRegistry.PrefixDeclaration prefix(
      String owner, String principal, String prefix) {
    return new RedisContractRegistry.PrefixDeclaration(
        owner, RedisScriptDescriptor.RedisRole.COORDINATION, principal, prefix);
  }

  private static RedisScriptDescriptor issuerDescriptor() {
    return descriptor(
        "game-session.issuer-generation.v1",
        "redis/auth/issuer_generation_cas.lua",
        "a".repeat(64),
        "game-session",
        "gamesession_coord_app",
        RedisScriptDescriptor.RedisRole.COORDINATION,
        List.of(
            new RedisScriptDescriptor.KeySpec(
                "issuerGeneration",
                ISSUER_PREFIX,
                RedisScriptDescriptor.HashTagDeclaration.NOT_REQUIRED)),
        List.of("expectedGeneration", "nextGeneration"),
        "loss closes the issuer scope until authenticated Account reconciliation");
  }

  private static RedisScriptDescriptor tickDescriptor() {
    return descriptor(
        "game-session.tick-stage.v1",
        "redis/tick_stage.lua",
        "c".repeat(64),
        "game-session",
        "gamesession_coord_app",
        RedisScriptDescriptor.RedisRole.COORDINATION,
        List.of(
            new RedisScriptDescriptor.KeySpec(
                "lease", "tick:", RedisScriptDescriptor.HashTagDeclaration.REQUIRED),
            new RedisScriptDescriptor.KeySpec(
                "metadata", "tick:", RedisScriptDescriptor.HashTagDeclaration.REQUIRED)),
        List.of("expectedEpoch"),
        "lost staged work remains subject to its durable ledger and owner reconciliation");
  }

  private static RedisScriptDescriptor descriptor(
      String scriptId,
      String resourcePath,
      String digest,
      String owner,
      String principal,
      RedisScriptDescriptor.RedisRole role,
      List<RedisScriptDescriptor.KeySpec> keys,
      List<String> argumentNames,
      String tailLossBehavior) {
    return new RedisScriptDescriptor(
        scriptId,
        resourcePath,
        digest,
        owner,
        principal,
        role,
        keys,
        argumentNames.stream().map(RedisScriptDescriptor.ArgumentSpec::new).toList(),
        RedisScriptDescriptor.ScriptCategory.SESSION_CAS,
        List.of(
            new RedisScriptDescriptor.OutcomeSpec(
                "UPDATED",
                RedisScriptDescriptor.OutcomeCategory.SUCCESS,
                RedisScriptDescriptor.MutationEffect.MUTATING),
            new RedisScriptDescriptor.OutcomeSpec(
                "STALE_GENERATION",
                RedisScriptDescriptor.OutcomeCategory.STALE,
                RedisScriptDescriptor.MutationEffect.NON_MUTATING)),
        RedisScriptDescriptor.ResetSensitivity.CLUSTER,
        RedisScriptDescriptor.LossClass.SESSION_LEASE_CACHE_OR_WAKE_UP,
        tailLossBehavior,
        RedisScriptDescriptor.CompatibilityLevel.COMPATIBLE,
        List.of(new RedisScriptDescriptor.SupportedCoexistence("v1", "none")),
        null);
  }
}
