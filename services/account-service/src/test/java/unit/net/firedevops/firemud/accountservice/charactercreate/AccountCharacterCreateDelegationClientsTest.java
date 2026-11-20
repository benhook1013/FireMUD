package net.firedevops.firemud.accountservice.charactercreate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Status;
import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateProtectionResponse;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateOutcome;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateTerminal;
import net.firedevops.firemud.gamesession.v1.CharacterCreateTargetProtection;
import net.firedevops.firemud.gamesession.v1.CharacterCreateTargetSnapshot;
import net.firedevops.firemud.worldmanagement.v1.WorldCharacterCreateLifecycleProtection;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Controlled composition/byte-binding proof only, not genuine source/owner or mTLS qualification.
 */
class AccountCharacterCreateDelegationClientsTest {
  private static final String NAMESPACE = "creation-outbound-proof";
  private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
  private final AccountCharacterCreateIntent intent =
      AccountCharacterCreateTestActions.intent(
          NAMESPACE, UUID.randomUUID(), UUID.randomUUID(), NOW.plusSeconds(300));
  private final AccountCharacterCreateRepository.Protection protection =
      new AccountCharacterCreateRepository.Protection(
          intent, UUID.randomUUID(), 7L, NOW, new byte[] {1, 2}, new byte[] {3, 4});
  private final ReadOriginalCharacterCreateProtectionResponse account =
      AccountCharacterCreateDelegationBindings.account(NAMESPACE, intent, protection);
  private final AccountCharacterCreateGameSessionClient gameSession =
      mock(AccountCharacterCreateGameSessionClient.class);
  private final AccountCharacterCreateWorldClient world =
      mock(AccountCharacterCreateWorldClient.class);
  private final AccountCharacterCreateEntityClient entity =
      mock(AccountCharacterCreateEntityClient.class);
  private final AccountCharacterCreateOwnerClients resolver =
      AccountCharacterCreateOwnerClients.unavailable();

  @Test
  void resolverStaysUnavailableDespiteConcreteDelegationComposition() {
    var clients = clients();
    assertThatThrownBy(
            () ->
                clients.resolvePublicProduction(
                    NAMESPACE,
                    mock(AccountCharacterCreatePrincipal.class),
                    intent.connectScopeId()))
        .hasMessageContaining("UNAVAILABLE");
    assertThatThrownBy(() -> clients.requireExactOriginalSource(intent))
        .hasMessageContaining("UNAVAILABLE");
    verifyNoInteractions(gameSession, world, entity);
  }

  @Test
  void forwardsActualBootstrapCapabilityAndOpaqueScopeWithoutCallingDelegationOwners() {
    var source = mock(AccountCharacterCreateOwnerClients.class);
    var principal = mock(AccountCharacterCreatePrincipal.class);
    var resolved =
        new AccountCharacterCreateOwnerClients.ResolvedSource(
            intent.actionBytes(), intent.originalSourceReadback());
    when(source.resolvePublicProduction(NAMESPACE, principal, intent.connectScopeId()))
        .thenReturn(resolved);
    var clients =
        new AccountCharacterCreateDelegationClients(NAMESPACE, source, gameSession, world, entity);

    assertThat(clients.resolvePublicProduction(NAMESPACE, principal, intent.connectScopeId()))
        .isSameAs(resolved);

    verify(source).resolvePublicProduction(NAMESPACE, principal, intent.connectScopeId());
    verifyNoInteractions(gameSession, world, entity);
  }

  @Test
  void compositionPassesExactCommittedAccountCarrierAndOwnerReadbacks() {
    var target = target();
    var lifecycle = lifecycle();
    var terminal = terminal(target, lifecycle);
    when(gameSession.acquireAndReadOriginal(account)).thenReturn(target);
    when(world.acquireAndReadOriginal(account)).thenReturn(lifecycle);
    when(entity.commitAndReadOriginal(account, target, lifecycle)).thenReturn(terminal);

    assertThat(clients().submitProtected(intent, protection)).isEqualTo(terminal);

    var order = org.mockito.Mockito.inOrder(gameSession, world, entity);
    order.verify(gameSession).acquireAndReadOriginal(account);
    order.verify(world).acquireAndReadOriginal(account);
    order.verify(entity).commitAndReadOriginal(account, target, lifecycle);
  }

  @Test
  void partialAcquisitionTimeoutRetriesExactAccountSelectorWithoutInvokingEntity() {
    var target = target();
    var lifecycle = lifecycle();
    var terminal = terminal(target, lifecycle);
    when(gameSession.acquireAndReadOriginal(account)).thenReturn(target);
    when(world.acquireAndReadOriginal(account))
        .thenThrow(Status.DEADLINE_EXCEEDED.asRuntimeException())
        .thenReturn(lifecycle);
    when(entity.commitAndReadOriginal(account, target, lifecycle)).thenReturn(terminal);

    assertThatThrownBy(() -> clients().submitProtected(intent, protection))
        .hasMessageContaining("DEADLINE_EXCEEDED");
    verifyNoInteractions(entity);
    assertThat(clients().submitProtected(intent, protection)).isEqualTo(terminal);
    var observed = ArgumentCaptor.forClass(ReadOriginalCharacterCreateProtectionResponse.class);
    verify(gameSession, times(2)).acquireAndReadOriginal(observed.capture());
    assertThat(observed.getAllValues()).containsExactly(account, account);
    verify(world, times(2)).acquireAndReadOriginal(account);
  }

  @Test
  void mismatchedOriginalHoldAndAmbientSqlCannotReachOwners() {
    var changed =
        new AccountCharacterCreateIntent(
            UUID.randomUUID(),
            intent.createCharacterRequestId(),
            intent.connectScopeId(),
            intent.accountId(),
            intent.actionBytes(),
            intent.mutationDigest(),
            intent.originalSourceReadback());
    assertThatThrownBy(() -> clients().submitProtected(changed, protection))
        .hasMessageContaining("original CHARACTER_CREATE");
    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThatThrownBy(() -> clients().submitProtected(intent, protection))
          .hasMessageContaining("no ambient Account transaction");
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    verifyNoInteractions(gameSession, world, entity);
  }

  @Test
  void terminalMustRetainEveryOriginalOwnerByteAndReference() {
    var target = target();
    var lifecycle = lifecycle();
    var terminal = terminal(target, lifecycle);
    var request = AccountCharacterCreateDelegationBindings.request(account, target, lifecycle);
    AccountCharacterCreateDelegationBindings.terminal(
        request, account, target, lifecycle, terminal);
    for (var changed :
        java.util.List.of(
            terminal.toBuilder()
                .setAccountOriginal(
                    account.getOriginal().toBuilder()
                        .setOriginalSourceReadback(ByteString.copyFromUtf8("changed")))
                .build(),
            terminal.toBuilder()
                .setOriginalAccountProtection(
                    account.toBuilder().setAccountProtectionFence(8L).build().toByteString())
                .build(),
            terminal.toBuilder()
                .setOriginalGameSessionProtection(
                    target.toBuilder()
                        .setAcquiredAt(NOW.plusNanos(1).toString())
                        .build()
                        .toByteString())
                .build(),
            terminal.toBuilder()
                .setOriginalWorldProtection(
                    lifecycle.toBuilder()
                        .setOriginalLifecycleEvidence(ByteString.copyFromUtf8("changed"))
                        .build()
                        .toByteString())
                .build(),
            terminal.toBuilder()
                .setOriginalRequest(
                    request.toBuilder()
                        .setOriginal(
                            request.getOriginal().toBuilder()
                                .setOperationId(UUID.randomUUID().toString())))
                .build(),
            terminal.toBuilder().setUnknownFields(unknown()).build())) {
      assertThatThrownBy(
              () ->
                  AccountCharacterCreateDelegationBindings.terminal(
                      request, account, target, lifecycle, changed))
          .hasMessageContaining("original CHARACTER_CREATE");
    }
  }

  @Test
  void unspecifiedPendingLikeOutcomeOrMalformedAbortNeverBecomesTerminal() {
    var target = target();
    var lifecycle = lifecycle();
    var terminal = terminal(target, lifecycle);
    var request = AccountCharacterCreateDelegationBindings.request(account, target, lifecycle);
    assertThatThrownBy(
            () ->
                AccountCharacterCreateDelegationBindings.terminal(
                    request,
                    account,
                    target,
                    lifecycle,
                    terminal.toBuilder()
                        .setOutcome(
                            EntityCharacterCreateOutcome
                                .ENTITY_CHARACTER_CREATE_OUTCOME_UNSPECIFIED)
                        .build()))
        .hasMessageContaining("original CHARACTER_CREATE");
    assertThatThrownBy(
            () ->
                AccountCharacterCreateDelegationBindings.terminal(
                    request,
                    account,
                    target,
                    lifecycle,
                    terminal.toBuilder()
                        .clearCharacterId()
                        .setOutcome(
                            EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_ABORTED)
                        .build()))
        .hasMessageContaining("original CHARACTER_CREATE");
    // A genuine definitive abort may be returned by Entity after an independently fenced race;
    // recognizing its exact terminal here does not release any hold or invoke Abort RPC.
    AccountCharacterCreateDelegationBindings.terminal(
        request,
        account,
        target,
        lifecycle,
        terminal.toBuilder()
            .clearCharacterId()
            .setOutcome(EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_ABORTED)
            .setAbortRequestId(UUID.randomUUID().toString())
            .build());
  }

  @Test
  void targetSnapshotCannotSubstituteAccountSourceLifecycleOrPointerVersion() {
    var target = target();
    AccountCharacterCreateDelegationBindings.gameSession(account, target);
    assertThatThrownBy(
            () ->
                AccountCharacterCreateDelegationBindings.gameSession(
                    account,
                    target.toBuilder()
                        .setAccountProtection(
                            account.toBuilder()
                                .setOriginalMembershipSnapshot(ByteString.copyFromUtf8("changed")))
                        .build()))
        .hasMessageContaining("original CHARACTER_CREATE");
    try {
      var snapshot = CharacterCreateTargetSnapshot.parseFrom(target.getOriginalTargetEvidence());
      assertThatThrownBy(
              () ->
                  AccountCharacterCreateDelegationBindings.gameSession(
                      account,
                      target.toBuilder()
                          .setOriginalTargetEvidence(
                              snapshot.toBuilder()
                                  .setPointerVersion(snapshot.getPointerVersion() + 1)
                                  .build()
                                  .toByteString())
                          .build()))
          .hasMessageContaining("original CHARACTER_CREATE");
    } catch (com.google.protobuf.InvalidProtocolBufferException malformed) {
      throw new AssertionError(malformed);
    }
    assertThatThrownBy(() -> AccountCharacterCreateDelegationBindings.world(account, lifecycle()))
        .isInstanceOf(RuntimeException.class);
  }

  private AccountCharacterCreateDelegationClients clients() {
    return new AccountCharacterCreateDelegationClients(
        NAMESPACE, resolver, gameSession, world, entity);
  }

  private CharacterCreateTargetProtection target() {
    var action = intent.action();
    var snapshot =
        CharacterCreateTargetSnapshot.newBuilder()
            .setSchemaVersion(1)
            .setOriginalAction(account.getOriginal().getOriginalAction())
            .setTargetNamespace(NAMESPACE)
            .setOperationId(intent.operationId().toString())
            .setMutationDigest(intent.mutationDigest())
            .setCanonicalTenantId(action.scope().tenantId())
            .setRealmId(action.target().realmId())
            .setCanonicalGameInstanceId(action.target().gameInstanceId())
            .setCanonicalVersionId(action.target().versionId())
            .setPlayableStateNamespaceId(action.scope().playableStateNamespaceId())
            .setCatalogRevision(Long.parseLong(action.expectedVersion().catalogRevision()))
            .setPointerVersion(Long.parseLong(action.expectedVersion().pointerVersion()))
            .setActiveWorldEpoch(Long.parseLong(action.expectedVersion().activeLifecycleEpoch()))
            .setCatalogReceiptDigest("sha256:" + "a".repeat(64))
            .setInitialAdmissionRequestId("owner-native-request")
            .setInitialAdmissionOwnerProofDigest("sha256:" + "b".repeat(64))
            .setAdmissionPointerSnapshotDigest("c".repeat(64))
            .build();
    return CharacterCreateTargetProtection.newBuilder()
        .setAccountProtection(account)
        .setGameSessionProtectionId(UUID.randomUUID().toString())
        .setGameSessionProtectionFence(11L)
        .setAcquiredAt(NOW.toString())
        .setOriginalTargetEvidence(snapshot.toByteString())
        .build();
  }

  private WorldCharacterCreateLifecycleProtection lifecycle() {
    return WorldCharacterCreateLifecycleProtection.newBuilder()
        .setAccountProtection(account)
        .setWorldProtectionId(UUID.randomUUID().toString())
        .setWorldProtectionFence(13L)
        .setAcquiredAt(NOW.toString())
        .setOriginalLifecycleEvidence(
            ByteString.copyFromUtf8("controlled-invalid-lifecycle-not-authority"))
        .build();
  }

  private EntityCharacterCreateTerminal terminal(
      CharacterCreateTargetProtection target, WorldCharacterCreateLifecycleProtection lifecycle) {
    return EntityCharacterCreateTerminal.newBuilder()
        .setOriginalRequest(
            AccountCharacterCreateDelegationBindings.request(account, target, lifecycle))
        .setAccountOriginal(account.getOriginal())
        .setOriginalAccountProtection(account.toByteString())
        .setOriginalGameSessionProtection(target.toByteString())
        .setOriginalWorldProtection(lifecycle.toByteString())
        .setEntityIntakeRequestId(UUID.randomUUID().toString())
        .setEntitySourceReceiptDigest("sha256:" + "d".repeat(64))
        .setOutcome(EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_COMMITTED)
        .setCharacterId(UUID.randomUUID().toString())
        .setTerminalId(UUID.randomUUID().toString())
        .setTerminalFence(1L)
        .setCompletedAt(NOW.toString())
        .build();
  }

  private static UnknownFieldSet unknown() {
    return UnknownFieldSet.newBuilder()
        .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
        .build();
  }
}
