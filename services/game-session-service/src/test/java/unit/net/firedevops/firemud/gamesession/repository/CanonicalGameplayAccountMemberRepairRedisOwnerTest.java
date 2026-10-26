package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.lettuce.core.RedisClient;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.UUID;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountIndexMember;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountMemberRepairOperation;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingAccountIndexObligation;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRef;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayAccountMemberRepairRedisScriptContribution;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CanonicalGameplayAccountMemberRepairRedisOwnerTest {
  @Test
  void installedDescriptorDeclaresOnlyTheGlobalAccountSetAndBoundLocalAction() {
    var descriptor =
        RedisScriptCatalog.loadInstalled(getClass().getClassLoader())
            .require(CanonicalGameplayAccountMemberRepairRedisScriptContribution.SCRIPT_ID);

    assertThat(descriptor.owner()).isEqualTo("game-session-service");
    assertThat(descriptor.principal()).isEqualTo("gamesession_coord_app");
    assertThat(descriptor.role()).isEqualTo(RedisScriptDescriptor.RedisRole.COORDINATION);
    assertThat(descriptor.keys())
        .containsExactly(
            new RedisScriptDescriptor.KeySpec(
                "accountIndex",
                "session:game:index:account:",
                RedisScriptDescriptor.HashTagDeclaration.NOT_REQUIRED));
    assertThat(descriptor.category())
        .isEqualTo(RedisScriptDescriptor.ScriptCategory.SESSION_ACCOUNT_INDEX);
    assertThat(descriptor.arguments()).hasSize(12);
    assertThat(descriptor.resetSensitivity())
        .isEqualTo(RedisScriptDescriptor.ResetSensitivity.CLUSTER);
    assertThat(descriptor.outcomes())
        .extracting(RedisScriptDescriptor.OutcomeSpec::code)
        .containsExactly(
            "MEMBER_ADDED",
            "MEMBER_ALREADY_PRESENT",
            "WRONG_KEY_TYPE_OR_EXPIRY",
            "INVALID_ARGUMENTS");
  }

  @Test
  void readbackHasNoCallerAccessibleConstructorOrFactory() {
    assertThat(
            Arrays.stream(
                CanonicalGameplayAccountMemberRepairRedisOwner.Readback.class
                    .getDeclaredConstructors()))
        .allSatisfy(
            constructor -> assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue());
    assertThat(
            Arrays.stream(
                CanonicalGameplayAccountMemberRepairRedisOwner.Readback.class.getDeclaredMethods()))
        .noneMatch(
            method ->
                Modifier.isStatic(method.getModifiers())
                    && !Modifier.isPrivate(method.getModifiers()));
  }

  @Test
  void refusesToOpenRedisWhileAnAmbientSqlTransactionIsActive() {
    RedisClient client = mock(RedisClient.class);
    var owner =
        new CanonicalGameplayAccountMemberRepairRedisOwner(
            client, RedisScriptCatalog.loadInstalled(getClass().getClassLoader()));
    try {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> owner.projectAndRead(operation()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("projection is unavailable");
      verifyNoInteractions(client);
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  static CanonicalGameplayAccountMemberRepairOperation operation() {
    UUID accountId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    CanonicalGameplayBindingRef bindingRef =
        CanonicalGameplayBindingRef.of(tenantId, UUID.randomUUID(), UUID.randomUUID().toString());
    UUID accountIndexFence = UUID.randomUUID();
    UUID transitionId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID coverageFence = UUID.randomUUID();
    BigInteger bindingGeneration = BigInteger.valueOf(7);
    BigInteger snapshotRevision = BigInteger.valueOf(11);
    String member =
        new CanonicalGameplayAccountIndexMember(bindingRef, bindingGeneration, accountIndexFence)
            .value();
    var obligation =
        new CanonicalGameplayBindingAccountIndexObligation(
            transitionId,
            0,
            bindingRef,
            accountId,
            tenantId,
            CanonicalGameplayBindingAccountIndexObligation.Action.ADD_OR_RETAIN,
            bindingGeneration,
            null,
            accountIndexFence,
            CanonicalGameplayBindingAccountIndexObligation.ExecutionPhase.BEFORE_FINAL_CAS,
            CanonicalGameplayBindingAccountIndexObligation.Status.REQUIRED,
            BigInteger.valueOf(10),
            CanonicalGameplayBindingAccountIndexObligation.ProjectionState.REQUIRED,
            null,
            null);
    return new CanonicalGameplayAccountMemberRepairOperation(
        transitionId,
        operationId,
        BigInteger.valueOf(2),
        coverageFence,
        snapshotRevision,
        bindingRef,
        accountId,
        tenantId,
        bindingGeneration,
        accountIndexFence,
        obligation,
        member,
        BigInteger.valueOf(3));
  }
}
