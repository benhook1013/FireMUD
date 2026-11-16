package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import net.firedevops.firemud.accountservice.service.AccountGenerationProjectionRedisScriptContribution;
import net.firedevops.firemud.accountservice.service.session.AccountCoordinationPinnedConnectionProvider;
import net.firedevops.firemud.accountservice.service.session.AccountCoordinationPinnedConnectionProvider.AcknowledgementRequirements;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationAuthorityProjection;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisScriptContribution;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptContribution;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor.KeySpec;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor.RedisRole;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class IssuerGenerationProjectionRedisContractTest {
  @Test
  void registersCanonicalAccountOwnerCoordinationDescriptorAndExactResource() throws Exception {
    var descriptor = descriptor();
    assertThat(descriptor.owner()).isEqualTo("account-service");
    assertThat(descriptor.principal()).isEqualTo("account_coord_app");
    assertThat(descriptor.role()).isEqualTo(RedisRole.COORDINATION);
    assertThat(descriptor.keys().getFirst().ownedPrefix())
        .isEqualTo("session:auth:generation:issuer:");
    try (var input = new ClassPathResource(descriptor.resourcePath()).getInputStream()) {
      assertThat(descriptor.sha256())
          .isEqualTo(
              HexFormat.of()
                  .formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())));
    }
    var provider = mock(AccountCoordinationPinnedConnectionProvider.class);
    var source =
        AccountGameplayDelegationAuthorityProjectionTest.DonorOwnerReadRegressions.snapshot(
            1L, 11L);
    var pair = AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(source);
    // Valid descriptor and valid owner bytes reach the private provider. This does not claim Redis
    // success.
    assertThatThrownBy(
            () ->
                client(descriptor, provider)
                    .publishAuthorityProjections(
                        source.account().scope().accountId(), pair[0], pair[1]))
        .isInstanceOf(AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
    verify(provider).openPinnedConnection();
  }

  @Test
  void rejectsWrongOwnerPrincipalCacheRoleAndForeignKeyBeforeOpeningConnection() {
    var source =
        AccountGameplayDelegationAuthorityProjectionTest.DonorOwnerReadRegressions.snapshot(
            1L, 11L);
    var pair = AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(source);
    for (var invalid :
        List.of(
            altered(
                "game-session-service",
                "account_coord_app",
                RedisRole.COORDINATION,
                "session:auth:generation:issuer:"),
            altered(
                "account-service",
                "cache_app",
                RedisRole.COORDINATION,
                "session:auth:generation:issuer:"),
            altered(
                "account-service",
                "account_coord_app",
                RedisRole.CACHE_RATE_LIMIT,
                "session:auth:generation:issuer:"),
            altered(
                "account-service",
                "account_coord_app",
                RedisRole.COORDINATION,
                "session:game:auth:issuer-generation:v1:"))) {
      var provider = mock(AccountCoordinationPinnedConnectionProvider.class);
      var client = client(invalid, provider);
      assertThatThrownBy(
              () ->
                  client.publishAuthorityProjections(
                      source.account().scope().accountId(), pair[0], pair[1]))
          .isInstanceOf(
              AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
      verifyNoInteractions(provider);
    }
  }

  private static AccountGameplayDelegationRedisClient client(
      RedisScriptDescriptor descriptor, AccountCoordinationPinnedConnectionProvider provider) {
    RedisScriptContribution contribution =
        new RedisScriptContribution() {
          public String ownerId() {
            return descriptor.owner();
          }

          public List<RedisScriptDescriptor> descriptors() {
            return List.of(descriptor);
          }
        };
    return new AccountGameplayDelegationRedisClient(
        provider,
        RedisScriptCatalog.fromContributions(
            List.of(contribution, new AccountGenerationProjectionRedisScriptContribution())),
        new AcknowledgementRequirements(1, 1, 1000),
        Clock.systemUTC(),
        GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES,
        IssuerGenerationProjectionRedisContractTest.class.getClassLoader());
  }

  private static RedisScriptDescriptor altered(
      String owner, String principal, RedisRole role, String prefix) {
    var d = descriptor();
    var k = d.keys().getFirst();
    return new RedisScriptDescriptor(
        d.scriptId(),
        d.resourcePath(),
        d.sha256(),
        owner,
        principal,
        role,
        List.of(new KeySpec(k.name(), prefix, k.hashTagDeclaration())),
        d.arguments(),
        d.category(),
        d.outcomes(),
        d.resetSensitivity(),
        d.lossClass(),
        d.tailLossBehavior(),
        d.compatibilityLevel(),
        d.supportedCoexistence(),
        d.legacyPayloadShape());
  }

  private static RedisScriptDescriptor descriptor() {
    return new AccountGameplayDelegationRedisScriptContribution()
        .descriptors().stream()
            .filter(
                d ->
                    d.scriptId()
                        .equals(AccountGameplayDelegationRedisClient.ISSUER_PROJECTION_SCRIPT_ID))
            .findFirst()
            .orElseThrow();
  }
}
