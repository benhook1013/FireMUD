package unit.net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import net.firedevops.firemud.common.redis.contracts.RedisContractRegistry.InvocationRequest;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor.HashTagDeclaration;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor.LossClass;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor.RedisRole;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor.ResetSensitivity;
import net.firedevops.firemud.gamesession.service.IssuerAuthorityProjectionRedisContract;
import org.junit.jupiter.api.Test;

class IssuerAuthorityProjectionRedisContractTest {
  private static final String ISSUER_ID = "https://accounts.example.test/issuer";

  @Test
  void descriptorPinsOwnerPrincipalRoleResourceKeyAndArguments() {
    var descriptor = IssuerAuthorityProjectionRedisContract.descriptor();

    assertThat(descriptor.scriptId()).isEqualTo("game-session.issuer-authority-projection.v1");
    assertThat(descriptor.resourcePath()).isEqualTo("redis/issuer_authority_projection_cas.lua");
    assertThat(descriptor.owner()).isEqualTo("game-session-service");
    assertThat(descriptor.principal()).isEqualTo("gamesession_coord_app");
    assertThat(descriptor.role()).isEqualTo(RedisRole.COORDINATION);
    assertThat(descriptor.resetSensitivity()).isEqualTo(ResetSensitivity.CLUSTER);
    assertThat(descriptor.lossClass()).isEqualTo(LossClass.SESSION_LEASE_CACHE_OR_WAKE_UP);
    assertThat(descriptor.tailLossBehavior())
        .contains("quarantine this issuer scope")
        .contains("never admit");
    assertThat(IssuerAuthorityProjectionRedisContract.PROJECTION_SCHEMA_VERSION)
        .isEqualTo("game-session-auth-issuer-projection/v1");
    assertThat(descriptor.keys())
        .singleElement()
        .satisfies(
            key -> {
              assertThat(key.ownedPrefix()).isEqualTo("session:game:auth:issuer-generation:v1:");
              assertThat(key.hashTagDeclaration()).isEqualTo(HashTagDeclaration.NOT_REQUIRED);
            });
    assertThat(descriptor.arguments())
        .extracting(argument -> argument.name())
        .containsExactly("expectedMode", "expectedBytes", "candidateBytes");
    assertThat(descriptor.outcomes())
        .extracting(outcome -> outcome.code())
        .containsExactly("APPLIED", "REPLAY", "STALE", "INVALID", "TTL_PRESENT");
    assertThat(IssuerAuthorityProjectionRedisContract.keyForIssuer(ISSUER_ID))
        .isEqualTo("session:game:auth:issuer-generation:v1:" + ISSUER_ID);
  }

  @Test
  void registryAcceptsOnlyTheRegisteredOwnerInvocation() {
    var descriptor = IssuerAuthorityProjectionRedisContract.descriptor();
    String key = IssuerAuthorityProjectionRedisContract.keyForIssuer(ISSUER_ID);
    InvocationRequest valid =
        new InvocationRequest(
            descriptor.scriptId(),
            descriptor.resourcePath(),
            descriptor.sha256(),
            descriptor.owner(),
            descriptor.principal(),
            descriptor.role(),
            List.of(key),
            List.of("ABSENT", "", "{}"));

    assertThat(IssuerAuthorityProjectionRedisContract.registry().prepareInvocation(valid).keys())
        .containsExactly(key);
    assertThatThrownBy(
            () ->
                IssuerAuthorityProjectionRedisContract.registry()
                    .prepareInvocation(
                        new InvocationRequest(
                            valid.scriptId(),
                            valid.resourcePath(),
                            valid.sha256(),
                            valid.owner(),
                            valid.principal(),
                            RedisRole.CACHE_RATE_LIMIT,
                            valid.keys(),
                            valid.arguments())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Redis role");
    assertThatThrownBy(
            () ->
                IssuerAuthorityProjectionRedisContract.registry()
                    .prepareInvocation(
                        new InvocationRequest(
                            valid.scriptId(),
                            "redis/unregistered.lua",
                            valid.sha256(),
                            valid.owner(),
                            valid.principal(),
                            valid.role(),
                            valid.keys(),
                            valid.arguments())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("script resource path");
  }

  @Test
  void ownerKeyBuilderRejectsMissingOrControlCharacterIssuer() {
    assertThatThrownBy(() -> IssuerAuthorityProjectionRedisContract.keyForIssuer(" "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> IssuerAuthorityProjectionRedisContract.keyForIssuer("issuer\nother"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
