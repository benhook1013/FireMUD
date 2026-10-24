package unit.net.firedevops.firemud.entitymanagement.charactercreate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.firedevops.firemud.common.world.CanonicalPlayerCreatedRosterEvidence;
import net.firedevops.firemud.entitymanagement.charactercreate.EntityPlayerCreatedRosterService;
import net.firedevops.firemud.entitymanagement.charactercreate.EntityPlayerCreatedRosterSnapshotRepository;
import net.firedevops.firemud.entitymanagement.charactercreate.EntityPlayerCreatedRosterSourceClient;
import net.firedevops.firemud.entitymanagement.client.GameSessionCanonicalGameplayRosterOwnerReadClient;
import net.firedevops.firemud.entitymanagement.repository.CanonicalGameplayRosterRetentionRepository;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterSourceIdentity;
import net.firedevops.firemud.entitymanagement.v1.CanonicalSelectedPlayerCreatedCharacterRequest;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/** These unit cases cover closed-wire rejection only; they establish no owner or custody proof. */
class EntityPlayerCreatedRosterServiceTest {
  @Test
  void rejectsMalformedRosterBeforeAdmissionOrOwnerTransport() {
    var source = mock(EntityPlayerCreatedRosterSourceClient.class);
    var owner = mock(GameSessionCanonicalGameplayRosterOwnerReadClient.class);
    var snapshots = mock(EntityPlayerCreatedRosterSnapshotRepository.class);
    var retention = mock(CanonicalGameplayRosterRetentionRepository.class);
    var transactions = mock(TransactionOperations.class);
    var service =
        new EntityPlayerCreatedRosterService(
            "gameplay", source, owner, snapshots, retention, transactions);

    assertThatThrownBy(() -> service.list(CanonicalPlayerCreatedRosterRequest.getDefaultInstance()))
        .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(source, owner, snapshots, retention, transactions);
  }

  @Test
  void rejectsMalformedSelectedRequestBeforeEitherOwnerTransport() {
    var source = mock(EntityPlayerCreatedRosterSourceClient.class);
    var owner = mock(GameSessionCanonicalGameplayRosterOwnerReadClient.class);
    var snapshots = mock(EntityPlayerCreatedRosterSnapshotRepository.class);
    var retention = mock(CanonicalGameplayRosterRetentionRepository.class);
    var transactions = mock(TransactionOperations.class);
    var service =
        new EntityPlayerCreatedRosterService(
            "gameplay", source, owner, snapshots, retention, transactions);

    assertThatThrownBy(
            () ->
                service.selected(
                    CanonicalSelectedPlayerCreatedCharacterRequest.getDefaultInstance()))
        .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(source, owner, snapshots, retention, transactions);
  }

  @Test
  void selectedSourceFailureStopsBeforeOriginalOwnerOrLocalStorage() {
    var source = mock(EntityPlayerCreatedRosterSourceClient.class);
    var owner = mock(GameSessionCanonicalGameplayRosterOwnerReadClient.class);
    var snapshots = mock(EntityPlayerCreatedRosterSnapshotRepository.class);
    var retention = mock(CanonicalGameplayRosterRetentionRepository.class);
    var transactions = mock(TransactionOperations.class);
    var service =
        new EntityPlayerCreatedRosterService(
            "gameplay", source, owner, snapshots, retention, transactions);
    when(source.read(any())).thenThrow(new IllegalStateException("controlled source unavailable"));

    assertThatThrownBy(() -> service.selected(selectedRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("controlled source unavailable");

    verify(source).read(any());
    verifyNoInteractions(owner, snapshots, retention, transactions);
  }

  private static CanonicalSelectedPlayerCreatedCharacterRequest selectedRequest() {
    UUID operation = UUID.randomUUID();
    UUID account = UUID.randomUUID();
    UUID character = UUID.randomUUID();
    var target =
        CanonicalGameplayRosterTarget.newBuilder()
            .setTenantUuid(UUID.randomUUID().toString())
            .setRealmUuid(UUID.randomUUID().toString())
            .setWorldSlug("world")
            .setRealmSlug("realm")
            .setGameInstanceUuid(UUID.randomUUID().toString())
            .setCatalogRevision(3)
            .setCanonicalVersionUuid(UUID.randomUUID().toString())
            .setPointerVersion(5)
            .setActiveWorldEpoch(7)
            .setPublishedPolicyDigest("1".repeat(64))
            .setPublishedReleaseBundleRef("release:test")
            .setAdmissionPointerSnapshotDigest("2".repeat(64))
            .setPublishedOwnerProofDigest("3".repeat(64))
            .setPlayableStateNamespaceUuid(UUID.randomUUID().toString())
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setEntryPolicy(
                net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy
                    .CANONICAL_ROSTER_ENTRY_POLICY_PLAYER_CREATED)
            .build();
    var source =
        CanonicalPlayerCreatedRosterEvidence.requireSourceIdentity(
            CanonicalPlayerCreatedRosterSourceIdentity.newBuilder()
                .setSchemaVersion(1)
                .setDescriptorUuid(UUID.randomUUID().toString())
                .setDescriptorRevisionUuid(UUID.randomUUID().toString())
                .setDescriptorDigest("sha256:" + "a".repeat(64))
                .setAuthoredBindingUuid(UUID.randomUUID().toString())
                .setAuthoredBindingDigest("sha256:" + "b".repeat(64))
                .build());
    return CanonicalPlayerCreatedRosterEvidence.requireSelectedRequest(
        CanonicalSelectedPlayerCreatedCharacterRequest.newBuilder()
            .setRequestUuid(operation.toString())
            .setCanonicalAccountUuid(account.toString())
            .setSelectedCharacterUuid(character.toString())
            .setExpectedTarget(target)
            .setExpectedSnapshot(
                net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSnapshotReference
                    .newBuilder()
                    .setSnapshotUuid(UUID.randomUUID().toString())
                    .setSnapshotDigest("c".repeat(64)))
            .setExpectedSource(source)
            .setPlayerExecutionContext(
                PlayerExecutionContext.newBuilder()
                    .setRequestId(operation.toString())
                    .setAccountId(account.toString())
                    .setSessionId(UUID.randomUUID().toString())
                    .setTenantId(target.getTenantUuid())
                    .setRealmId(target.getRealmUuid())
                    .setPlayableStateNamespaceId(target.getPlayableStateNamespaceUuid())
                    .setGameInstanceId(target.getGameInstanceUuid())
                    .setPlayableStateScope("SHARED")
                    .setCharacterId(character.toString()))
            .build());
  }
}
