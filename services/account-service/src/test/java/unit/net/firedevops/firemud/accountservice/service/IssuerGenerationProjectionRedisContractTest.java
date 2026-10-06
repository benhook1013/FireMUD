package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import net.firedevops.firemud.accountservice.service.IssuerGenerationProjectionRedisContract;
import net.firedevops.firemud.common.redis.contracts.RedisContractRegistry.InvocationRequest;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor.RedisRole;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class IssuerGenerationProjectionRedisContractTest {
  @Test
  void registersCanonicalAccountOwnerCoordinationDescriptorAndExactExistingResource()
      throws Exception {
    var descriptor = IssuerGenerationProjectionRedisContract.descriptor();
    assertThat(descriptor.owner()).isEqualTo("account-service");
    assertThat(descriptor.principal()).isEqualTo("account_coord_app");
    assertThat(descriptor.role()).isEqualTo(RedisRole.COORDINATION);
    assertThat(IssuerGenerationProjectionRedisContract.KEY_PREFIX)
        .isEqualTo("session:auth:generation:issuer:");
    try (var input = new ClassPathResource(descriptor.resourcePath()).getInputStream()) {
      assertThat(descriptor.sha256())
          .isEqualTo(
              HexFormat.of()
                  .formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())));
    }
    var invocation =
        IssuerGenerationProjectionRedisContract.registry()
            .prepareInvocation(
                request(
                    "account-service",
                    "account_coord_app",
                    RedisRole.COORDINATION,
                    "session:auth:generation:issuer:firemud-account-service"));
    assertThat(invocation.descriptor()).isSameAs(descriptor);
  }

  @Test
  void rejectsCacheRoleWrongPrincipalAndGameSessionDerivedKey() {
    for (InvocationRequest request :
        List.of(
            request(
                "game-session-service",
                "account_coord_app",
                RedisRole.COORDINATION,
                "session:auth:generation:issuer:firemud-account-service"),
            request(
                "account-service",
                "cache_app",
                RedisRole.COORDINATION,
                "session:auth:generation:issuer:firemud-account-service"),
            request(
                "account-service",
                "account_coord_app",
                RedisRole.CACHE_RATE_LIMIT,
                "session:auth:generation:issuer:firemud-account-service"),
            request(
                "account-service",
                "account_coord_app",
                RedisRole.COORDINATION,
                "session:game:auth:issuer-generation:v1:firemud-account-service"))) {
      assertThatThrownBy(
              () -> IssuerGenerationProjectionRedisContract.registry().prepareInvocation(request))
          .isInstanceOf(RuntimeException.class);
    }
  }

  private static InvocationRequest request(
      String owner, String principal, RedisRole role, String key) {
    var descriptor = IssuerGenerationProjectionRedisContract.descriptor();
    return new InvocationRequest(
        descriptor.scriptId(),
        descriptor.resourcePath(),
        descriptor.sha256(),
        owner,
        principal,
        role,
        List.of(key),
        List.of("ABSENT", "", "test-candidate"));
  }
}
