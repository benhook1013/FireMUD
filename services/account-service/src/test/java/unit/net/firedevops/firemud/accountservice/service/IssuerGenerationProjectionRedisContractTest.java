package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.junit.jupiter.api.Test;

class IssuerGenerationProjectionRedisContractTest {
  private static final String KEY =
      IssuerGenerationProjection.keyForIssuer(GameSessionAccountDelegationProfile.ISSUER);

  @Test
  void installedPassiveContributionRetainsIssuerOwnerAndExactExistingLua() throws Exception {
    var catalog = RedisScriptCatalog.loadInstalled(getClass().getClassLoader());
    var descriptor = catalog.require(IssuerGenerationProjectionRedisContract.SCRIPT_ID);
    assertThat(descriptor).isEqualTo(IssuerGenerationProjectionRedisContract.descriptor());
    assertThat(descriptor.owner()).isEqualTo("account-service");
    assertThat(descriptor.principal()).isEqualTo("account_coord_app");
    assertThat(descriptor.role()).isEqualTo(RedisScriptDescriptor.RedisRole.COORDINATION);
    assertThat(descriptor.keys())
        .containsExactly(
            new RedisScriptDescriptor.KeySpec(
                "issuerGenerationProjection",
                IssuerGenerationProjection.KEY_PREFIX,
                RedisScriptDescriptor.HashTagDeclaration.NOT_REQUIRED));
    assertThat(descriptor.arguments())
        .containsExactly(
            new RedisScriptDescriptor.ArgumentSpec("expectedMode"),
            new RedisScriptDescriptor.ArgumentSpec("expectedBytes"),
            new RedisScriptDescriptor.ArgumentSpec("candidateBytes"));
    assertThat(catalog.require(AccountGenerationProjectionRedisContract.SCRIPT_ID))
        .isEqualTo(AccountGenerationProjectionRedisContract.descriptor());
    try (var input = getClass().getClassLoader().getResourceAsStream(descriptor.resourcePath())) {
      assertThat(input).isNotNull();
      assertThat(
              HexFormat.of()
                  .formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())))
          .isEqualTo(descriptor.sha256());
    }
  }

  @Test
  void acceptsOnlyCanonicalIssuerAndModeSpecificArguments() {
    IssuerGenerationProjectionRedisContract.validateInvocation(KEY, "ABSENT", "", "candidate");
    IssuerGenerationProjectionRedisContract.validateInvocation(KEY, "PRESENT", "old", "candidate");
    IssuerGenerationProjectionRedisContract.validateInvocation(KEY, "VERIFY", "old", "");
    for (String wrong :
        List.of(
            AccountGenerationProjection.KEY_PREFIX + "c980fa44-619e-4ca4-8ad6-75b0538a66a3",
            "session:game:auth:issuer-generation:v1:" + GameSessionAccountDelegationProfile.ISSUER,
            IssuerGenerationProjection.KEY_PREFIX + "other",
            KEY + " ")) {
      assertThatThrownBy(
              () ->
                  IssuerGenerationProjectionRedisContract.validateInvocation(
                      wrong, "ABSENT", "", "candidate"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (String[] args :
        List.of(
            new String[] {"ABSENT", "old", "candidate"},
            new String[] {"ABSENT", "", ""},
            new String[] {"PRESENT", "", "candidate"},
            new String[] {"PRESENT", "old", ""},
            new String[] {"VERIFY", "", ""},
            new String[] {"VERIFY", "old", "candidate"},
            new String[] {"OTHER", "", "candidate"})) {
      assertThatThrownBy(
              () ->
                  IssuerGenerationProjectionRedisContract.validateInvocation(
                      KEY, args[0], args[1], args[2]))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void refusesRolePrincipalPrefixDigestAndArgumentSubstitution() {
    var original = IssuerGenerationProjectionRedisContract.descriptor();
    for (int change = 0; change < 5; change++) {
      var altered =
          new RedisScriptDescriptor(
              original.scriptId(),
              original.resourcePath(),
              change == 0 ? "0".repeat(64) : original.sha256(),
              original.owner(),
              change == 1 ? "gamesession_coord_app" : original.principal(),
              change == 2 ? RedisScriptDescriptor.RedisRole.CACHE_RATE_LIMIT : original.role(),
              change == 3
                  ? List.of(
                      new RedisScriptDescriptor.KeySpec(
                          "issuerGenerationProjection",
                          AccountGenerationProjection.KEY_PREFIX,
                          RedisScriptDescriptor.HashTagDeclaration.NOT_REQUIRED))
                  : original.keys(),
              change == 4
                  ? List.of(
                      new RedisScriptDescriptor.ArgumentSpec("candidateBytes"),
                      new RedisScriptDescriptor.ArgumentSpec("expectedBytes"),
                      new RedisScriptDescriptor.ArgumentSpec("expectedMode"))
                  : original.arguments(),
              original.category(),
              original.outcomes(),
              original.resetSensitivity(),
              original.lossClass(),
              original.tailLossBehavior(),
              original.compatibilityLevel(),
              original.supportedCoexistence(),
              original.legacyPayloadShape());
      assertThatThrownBy(
              () -> IssuerGenerationProjectionRedisContract.requireSupportedDescriptor(altered))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThatThrownBy(
            () ->
                IssuerGenerationProjectionRedisContract.requireSupportedDescriptor(
                    AccountGenerationProjectionRedisContract.descriptor()))
        .isInstanceOf(IllegalStateException.class);
  }
}
