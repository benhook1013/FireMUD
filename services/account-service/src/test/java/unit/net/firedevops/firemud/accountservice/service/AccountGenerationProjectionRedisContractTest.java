package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import org.junit.jupiter.api.Test;

class AccountGenerationProjectionRedisContractTest {
  @Test
  void installedOwnerContributionDeclaresExactAccountCasContract() {
    RedisScriptCatalog catalog = RedisScriptCatalog.loadInstalled(getClass().getClassLoader());
    RedisScriptDescriptor descriptor =
        catalog.require(AccountGenerationProjectionRedisContract.SCRIPT_ID);

    assertThat(descriptor).isEqualTo(AccountGenerationProjectionRedisContract.descriptor());
    assertThat(descriptor.owner()).isEqualTo("account-service");
    assertThat(descriptor.scriptResource())
        .isEqualTo("redis/account_generation_projection_cas.lua");
    assertThat(descriptor.sourceSha256())
        .isEqualTo(AccountGenerationProjectionRedisContract.RESOURCE_SHA256);
    assertThat(AccountGenerationProjectionRedisContract.PRINCIPAL).isEqualTo("account_coord_app");
    assertThat(descriptor.redisRole()).isEqualTo(RedisScriptDescriptor.RedisRole.COORDINATION);
    assertThat(descriptor.keys())
        .containsExactly(
            new RedisScriptDescriptor.KeyDeclaration(
                "accountGenerationProjection",
                AccountGenerationProjectionRedisContract.KEY_PREFIX,
                ""));
    assertThat(descriptor.arguments())
        .containsExactly("expectedMode", "expectedBytes", "candidateBytes");
    assertThat(descriptor.outcomes())
        .isEqualTo(
            Map.of(1, "applied", 0, "replay", -1, "stale", -2, "invalid", -3, "ttl_present"));
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
            original.id(),
            original.owner(),
            original.scriptResource(),
            original.sourceSha256(),
            original.redisRole(),
            original.category(),
            List.of(
                new RedisScriptDescriptor.KeyDeclaration("otherKey", "session:auth:token:", "")),
            original.arguments(),
            original.resetSensitivity(),
            original.lossOutcomeClass(),
            original.tailLossBehavior(),
            original.outcomes());

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
                "-3".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
        .isEqualTo("TTL_PRESENT");

    assertThatThrownBy(() -> CurrentGenerationProjectionRedisSupport.decodeScriptResult("APPLIED"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> CurrentGenerationProjectionRedisSupport.decodeScriptResult("1.0"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> CurrentGenerationProjectionRedisSupport.decodeScriptResult("9"))
        .isInstanceOf(IllegalStateException.class);
  }
}
