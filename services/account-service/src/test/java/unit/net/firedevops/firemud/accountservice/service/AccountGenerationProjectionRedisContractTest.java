package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import org.junit.jupiter.api.Test;

class AccountGenerationProjectionRedisContractTest {
  @Test
  void registeredLuaBytesMatchTheOwnerPinnedSha256() throws Exception {
    RedisScriptDescriptor descriptor = AccountGenerationProjectionRedisContract.descriptor();
    byte[] source;
    try (var input = getClass().getClassLoader().getResourceAsStream(descriptor.resourcePath())) {
      assertThat(input).isNotNull();
      source = input.readAllBytes();
    }

    assertThat(sha256(source)).isEqualTo(descriptor.sha256());
  }

  @Test
  void installedOwnerContributionDeclaresExactAccountCasContract() {
    RedisScriptCatalog catalog = RedisScriptCatalog.loadInstalled(getClass().getClassLoader());
    RedisScriptDescriptor descriptor =
        catalog.require(AccountGenerationProjectionRedisContract.SCRIPT_ID);

    assertThat(descriptor).isEqualTo(AccountGenerationProjectionRedisContract.descriptor());
    assertThat(descriptor.owner()).isEqualTo("account-service");
    assertThat(descriptor.scriptId()).isEqualTo("account.account-generation-projection.v1");
    assertThat(descriptor.resourcePath()).isEqualTo("redis/account_generation_projection_cas.lua");
    assertThat(descriptor.sha256())
        .isEqualTo(AccountGenerationProjectionRedisContract.RESOURCE_SHA256);
    assertThat(descriptor.principal()).isEqualTo("account_coord_app");
    assertThat(descriptor.role()).isEqualTo(RedisScriptDescriptor.RedisRole.COORDINATION);
    assertThat(descriptor.category()).isEqualTo(RedisScriptDescriptor.ScriptCategory.SESSION_CAS);
    assertThat(descriptor.keys())
        .containsExactly(
            new RedisScriptDescriptor.KeySpec(
                "accountGenerationProjection",
                AccountGenerationProjectionRedisContract.KEY_PREFIX,
                RedisScriptDescriptor.HashTagDeclaration.NOT_REQUIRED));
    assertThat(descriptor.arguments())
        .containsExactly(
            new RedisScriptDescriptor.ArgumentSpec("expectedMode"),
            new RedisScriptDescriptor.ArgumentSpec("expectedBytes"),
            new RedisScriptDescriptor.ArgumentSpec("candidateBytes"));
    assertThat(descriptor.outcomes())
        .containsExactly(
            outcome(
                "APPLIED",
                RedisScriptDescriptor.OutcomeCategory.SUCCESS,
                RedisScriptDescriptor.MutationEffect.MUTATING),
            outcome(
                "REPLAY",
                RedisScriptDescriptor.OutcomeCategory.REPLAY,
                RedisScriptDescriptor.MutationEffect.NON_MUTATING),
            outcome(
                "STALE",
                RedisScriptDescriptor.OutcomeCategory.STALE,
                RedisScriptDescriptor.MutationEffect.NON_MUTATING),
            outcome(
                "INVALID",
                RedisScriptDescriptor.OutcomeCategory.VALIDATION_FAILURE,
                RedisScriptDescriptor.MutationEffect.NON_MUTATING),
            outcome(
                "TTL_PRESENT",
                RedisScriptDescriptor.OutcomeCategory.VALIDATION_FAILURE,
                RedisScriptDescriptor.MutationEffect.NON_MUTATING));
    assertThat(descriptor.resetSensitivity())
        .isEqualTo(RedisScriptDescriptor.ResetSensitivity.CLUSTER);
    assertThat(descriptor.lossClass())
        .isEqualTo(RedisScriptDescriptor.LossClass.SESSION_LEASE_CACHE_OR_WAKE_UP);
    assertThat(descriptor.compatibilityLevel())
        .isEqualTo(RedisScriptDescriptor.CompatibilityLevel.REQUIRES_CLUSTER_RESET);
    assertThat(descriptor.supportedCoexistence())
        .containsExactly(new RedisScriptDescriptor.SupportedCoexistence("v1", "v1"));
  }

  @Test
  void onlyCanonicalAccountKeysAndExactModeSpecificArgumentsAreAccepted() {
    String key =
        AccountGenerationProjectionRedisContract.KEY_PREFIX
            + "c980fa44-619e-4ca4-8ad6-75b0538a66a3";
    AccountGenerationProjectionRedisContract.validateInvocation(key, "ABSENT", "", "candidate");
    AccountGenerationProjectionRedisContract.validateInvocation(
        key, "PRESENT", "expected", "candidate");
    AccountGenerationProjectionRedisContract.validateInvocation(key, "VERIFY", "expected", "");

    assertThatThrownBy(
            () ->
                AccountGenerationProjectionRedisContract.validateInvocation(
                    "session:auth:generation:issuer:c980fa44-619e-4ca4-8ad6-75b0538a66a3",
                    "ABSENT",
                    "",
                    "candidate"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountGenerationProjectionRedisContract.validateInvocation(
                    key, "VERIFY", "expected", "candidate"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountGenerationProjectionRedisContract.validateInvocation(
                    key, "ABSENT", "not-empty", "candidate"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void descriptorCannotSubstituteDifferentKeyPrefixOrArgumentOrder() {
    RedisScriptDescriptor original = AccountGenerationProjectionRedisContract.descriptor();
    RedisScriptDescriptor wrongKeys =
        new RedisScriptDescriptor(
            original.scriptId(),
            original.resourcePath(),
            original.sha256(),
            original.owner(),
            original.principal(),
            original.role(),
            List.of(
                new RedisScriptDescriptor.KeySpec(
                    "otherKey",
                    "session:auth:token:",
                    RedisScriptDescriptor.HashTagDeclaration.NOT_REQUIRED)),
            original.arguments(),
            original.category(),
            original.outcomes(),
            original.resetSensitivity(),
            original.lossClass(),
            original.tailLossBehavior(),
            original.compatibilityLevel(),
            original.supportedCoexistence(),
            original.legacyPayloadShape());

    assertThatThrownBy(
            () -> AccountGenerationProjectionRedisContract.requireSupportedDescriptor(wrongKeys))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void numericLuaOutcomesDecodeToExistingStoreTokensAndRejectUnexpectedReplies() {
    assertThat(CurrentGenerationProjectionRedisSupport.decodeScriptResult(1L)).isEqualTo("APPLIED");
    assertThat(CurrentGenerationProjectionRedisSupport.decodeScriptResult("0")).isEqualTo("REPLAY");
    assertThat(
            CurrentGenerationProjectionRedisSupport.decodeScriptResult(
                "-3".getBytes(StandardCharsets.US_ASCII)))
        .isEqualTo("TTL_PRESENT");

    assertThatThrownBy(() -> CurrentGenerationProjectionRedisSupport.decodeScriptResult("APPLIED"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> CurrentGenerationProjectionRedisSupport.decodeScriptResult("1.0"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> CurrentGenerationProjectionRedisSupport.decodeScriptResult("9"))
        .isInstanceOf(IllegalStateException.class);
  }

  private static RedisScriptDescriptor.OutcomeSpec outcome(
      String code,
      RedisScriptDescriptor.OutcomeCategory category,
      RedisScriptDescriptor.MutationEffect effect) {
    return new RedisScriptDescriptor.OutcomeSpec(code, category, effect);
  }

  private static String sha256(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }
}
