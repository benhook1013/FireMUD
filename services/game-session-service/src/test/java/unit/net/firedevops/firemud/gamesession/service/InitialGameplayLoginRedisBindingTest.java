package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginRedisBinding.AcknowledgementPolicy;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginRedisBinding.Profile;
import org.junit.jupiter.api.Test;

class InitialGameplayLoginRedisBindingTest {
  @Test
  void explicitProfileRequiresLocalAofAndOnlyQualifiedSingleNodeMayUseZeroReplicas() {
    assertThat(
            new AcknowledgementPolicy(Profile.SINGLE_NODE_NO_FAILOVER, 1, 0, 1)
                .requiredReplicaAofCount())
        .isZero();
    assertThat(
            new AcknowledgementPolicy(Profile.REPLICATED, 1, 1, 60_000).requiredReplicaAofCount())
        .isEqualTo(1);
    assertThatThrownBy(() -> new AcknowledgementPolicy(Profile.REPLICATED, 1, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AcknowledgementPolicy(Profile.SINGLE_NODE_NO_FAILOVER, 1, 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AcknowledgementPolicy(Profile.REPLICATED, 0, 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AcknowledgementPolicy(Profile.REPLICATED, 1, 1, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AcknowledgementPolicy(Profile.REPLICATED, 1, 1, 60_001))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            new AcknowledgementPolicy(Profile.REPLICATED, 1, Integer.MAX_VALUE, 1)
                .requiredReplicaAofCount())
        .isEqualTo(Integer.MAX_VALUE);
  }

  @Test
  void contextKeyUsesCanonicalFullUuidWithoutTenantOrFixedHashTag() {
    UUID context = UUID.randomUUID();
    assertThat(InitialGameplayLoginContextRedisKeys.contextKey(context))
        .isEqualTo("session:game:initial-context:v1:" + context)
        .doesNotContain("{", "}");
    assertThatThrownBy(() -> InitialGameplayLoginContextRedisKeys.contextKey(new UUID(0, 0)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ownerContributionLoadsWithExactRoleSingleKeyAndPrincipal() {
    var catalog =
        RedisScriptCatalog.fromContributions(
            List.of(new InitialGameplayLoginRedisScriptContribution()));
    var descriptor = catalog.require(InitialGameplayLoginRedisScriptContribution.SCRIPT_ID);
    assertThat(descriptor.owner()).isEqualTo("game-session-service");
    assertThat(descriptor.principal()).isEqualTo(InitialGameplayLoginRedisBinding.PRINCIPAL);
    assertThat(descriptor.role()).isEqualTo(RedisScriptDescriptor.RedisRole.COORDINATION);
    assertThat(descriptor.keys())
        .containsExactly(
            new RedisScriptDescriptor.KeySpec(
                "originalContext",
                InitialGameplayLoginContextRedisKeys.FAMILY_SCOPE,
                RedisScriptDescriptor.HashTagDeclaration.NOT_REQUIRED));
    assertThat(
            RedisScriptCatalog.loadInstalled(getClass().getClassLoader())
                .require(descriptor.scriptId()))
        .isEqualTo(descriptor);
  }

  @Test
  void missingPolicyBindingOrQualificationCannotAcquirePhysicalMutationAuthority() {
    var authority = mock(InitialGameplayLoginCoordinationQualification.class);
    var client = new InitialGameplayLoginContextRedisClient(null, null, authority);
    assertThat(client.acquireQualification()).isEmpty();
    verifyNoInteractions(authority);
    assertThatThrownBy(() -> new InitialGameplayLoginRedisBinding("binding", () -> null, null))
        .isInstanceOf(NullPointerException.class);
  }
}
