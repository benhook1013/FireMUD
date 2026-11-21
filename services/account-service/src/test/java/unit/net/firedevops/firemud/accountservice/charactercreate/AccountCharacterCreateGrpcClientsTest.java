package net.firedevops.firemud.accountservice.charactercreate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateProtectionResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.v1.AcquireCharacterCreateTargetProtectionRequest;
import net.firedevops.firemud.gamesession.v1.CharacterCreateTargetProtection;
import net.firedevops.firemud.gamesession.v1.CharacterCreateTargetServiceGrpc;
import net.firedevops.firemud.gamesession.v1.CharacterCreateTargetSnapshot;
import net.firedevops.firemud.gamesession.v1.ReadOriginalCharacterCreateTargetProtectionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled stub proof; readable placeholder paths here do not qualify any TLS connection. */
class AccountCharacterCreateGrpcClientsTest {
  private static final String NAMESPACE = "creation-client-proof";
  @TempDir Path directory;
  private final GrpcChannelFactory channels = mock(GrpcChannelFactory.class);

  @Test
  void allClientsRequireFileBackedMutualTlsBeforeChannelConstruction() throws Exception {
    var plaintext = tls();
    plaintext.setPlaintext(true);
    for (var invalid : java.util.Arrays.asList(plaintext, null, new CommonGrpcClientProperties())) {
      assertThatThrownBy(
              () ->
                  new AccountCharacterCreateGameSessionClient(
                      new ServiceEndpointsProperties(), invalid, channels, NAMESPACE))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  new AccountCharacterCreateWorldClient(
                      new ServiceEndpointsProperties(), invalid, channels, NAMESPACE))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  new AccountCharacterCreateEntityClient(
                      new ServiceEndpointsProperties(), invalid, channels, NAMESPACE))
          .isInstanceOf(IllegalArgumentException.class);
    }
    verifyNoInteractions(channels);
  }

  @Test
  void targetAcquisitionReadsExactCommittedOriginalWithFiniteBoundedCalls() throws Exception {
    var stub =
        mock(
            CharacterCreateTargetServiceGrpc.CharacterCreateTargetServiceBlockingStub.class,
            RETURNS_SELF);
    var account = account();
    var target = target(account);
    when(stub.acquireCharacterCreateTargetProtection(any())).thenReturn(target);
    when(stub.readOriginalCharacterCreateTargetProtection(any())).thenReturn(target);
    var client = targetClient(stub);

    assertThat(client.acquireAndReadOriginal(account)).isEqualTo(target);
    assertThat(client.acquireAndReadOriginal(account)).isEqualTo(target);

    var acquisitions = ArgumentCaptor.forClass(AcquireCharacterCreateTargetProtectionRequest.class);
    verify(stub, times(2)).acquireCharacterCreateTargetProtection(acquisitions.capture());
    assertThat(acquisitions.getAllValues())
        .allSatisfy(
            request ->
                assertThat(request.getAccountProtection())
                    .isEqualTo(AccountCharacterCreateDelegationBindings.selector(account)));
    var reads = ArgumentCaptor.forClass(ReadOriginalCharacterCreateTargetProtectionRequest.class);
    verify(stub, times(2)).readOriginalCharacterCreateTargetProtection(reads.capture());
    assertThat(reads.getAllValues())
        .allSatisfy(
            request -> {
              assertThat(request.getAccountProtection())
                  .isEqualTo(AccountCharacterCreateDelegationBindings.selector(account));
              assertThat(request.getGameSessionProtectionId())
                  .isEqualTo(target.getGameSessionProtectionId());
              assertThat(request.getGameSessionProtectionFence())
                  .isEqualTo(target.getGameSessionProtectionFence());
            });
    verify(stub, times(4)).withDeadlineAfter(5, TimeUnit.SECONDS);
    verify(stub, times(4))
        .withMaxInboundMessageSize(AccountCharacterCreateDelegationBindings.MAX_PROTECTION_BYTES);
  }

  @Test
  void changedOwnerReadbackNeverBecomesAcceptedAcquisition() throws Exception {
    var stub =
        mock(
            CharacterCreateTargetServiceGrpc.CharacterCreateTargetServiceBlockingStub.class,
            RETURNS_SELF);
    var account = account();
    var target = target(account);
    when(stub.acquireCharacterCreateTargetProtection(any())).thenReturn(target);
    when(stub.readOriginalCharacterCreateTargetProtection(any()))
        .thenReturn(target.toBuilder().setAcquiredAt("2026-10-24T00:00:00.000000001Z").build());

    assertThatThrownBy(() -> targetClient(stub).acquireAndReadOriginal(account))
        .hasMessageContaining("original CHARACTER_CREATE");
  }

  @Test
  void ambientSqlIsRejectedBeforeAnyOwnerRpcOrInitialization() throws Exception {
    var target =
        new AccountCharacterCreateGameSessionClient(
            new ServiceEndpointsProperties(), tls(), channels, NAMESPACE);
    var world =
        new AccountCharacterCreateWorldClient(
            new ServiceEndpointsProperties(), tls(), channels, NAMESPACE);
    var entity =
        new AccountCharacterCreateEntityClient(
            new ServiceEndpointsProperties(), tls(), channels, NAMESPACE);
    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThatThrownBy(() -> target.acquireAndReadOriginal(account()))
          .hasMessageContaining("no ambient Account transaction");
      assertThatThrownBy(() -> world.acquireAndReadOriginal(account()))
          .hasMessageContaining("no ambient Account transaction");
      assertThatThrownBy(() -> entity.commitAndReadOriginal(account(), null, null))
          .hasMessageContaining("no ambient Account transaction");
      assertThatThrownBy(() -> entity.readOriginalTerminal(account(), null, null))
          .hasMessageContaining("no ambient Account transaction");
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    verifyNoInteractions(channels);
  }

  private AccountCharacterCreateGameSessionClient targetClient(
      CharacterCreateTargetServiceGrpc.CharacterCreateTargetServiceBlockingStub stub)
      throws Exception {
    var client =
        new AccountCharacterCreateGameSessionClient(
            new ServiceEndpointsProperties(), tls(), channels, NAMESPACE);
    Field current = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    current.setAccessible(true);
    current.set(client, stub);
    Field initialized =
        AbstractReloadingBlockingGrpcClient.class.getDeclaredField("explicitlyInitialized");
    initialized.setAccessible(true);
    initialized.set(client, true);
    return client;
  }

  private CommonGrpcClientProperties tls() throws Exception {
    var tls = new CommonGrpcClientProperties();
    tls.setCertChain(
        Files.writeString(directory.resolve("client.crt"), "controlled-placeholder").toString());
    tls.setPrivateKey(
        Files.writeString(directory.resolve("client.key"), "controlled-placeholder").toString());
    tls.setCaCert(
        Files.writeString(directory.resolve("ca.crt"), "controlled-placeholder").toString());
    return tls;
  }

  private static ReadOriginalCharacterCreateProtectionResponse account() {
    var intent =
        AccountCharacterCreateTestActions.intent(
            NAMESPACE, UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2099-01-01T00:00:00Z"));
    return AccountCharacterCreateDelegationBindings.account(
        NAMESPACE,
        intent,
        new AccountCharacterCreateRepository.Protection(
            intent,
            UUID.randomUUID(),
            7L,
            Instant.parse("2026-10-24T00:00:00Z"),
            new byte[] {1},
            new byte[] {2}));
  }

  private static CharacterCreateTargetProtection target(
      ReadOriginalCharacterCreateProtectionResponse account) {
    var action =
        net.firedevops.firemud.common.operator.CharacterCreateOperatorActionCodec.decode(
            account.getOriginal().getOriginalAction().toByteArray());
    var snapshot =
        CharacterCreateTargetSnapshot.newBuilder()
            .setSchemaVersion(1)
            .setOriginalAction(account.getOriginal().getOriginalAction())
            .setTargetNamespace(NAMESPACE)
            .setOperationId(account.getOriginal().getOperationId())
            .setMutationDigest(account.getOriginal().getMutationDigest())
            .setCanonicalTenantId(action.scope().tenantId())
            .setRealmId(action.target().realmId())
            .setCanonicalGameInstanceId(action.target().gameInstanceId())
            .setCanonicalVersionId(action.target().versionId())
            .setPlayableStateNamespaceId(action.scope().playableStateNamespaceId())
            .setCatalogRevision(Long.parseLong(action.expectedVersion().catalogRevision()))
            .setPointerVersion(Long.parseLong(action.expectedVersion().pointerVersion()))
            .setActiveWorldEpoch(Long.parseLong(action.expectedVersion().activeLifecycleEpoch()))
            .setCatalogReceiptDigest("sha256:" + "a".repeat(64))
            .setInitialAdmissionRequestId("native-owner-request")
            .setInitialAdmissionOwnerProofDigest("sha256:" + "b".repeat(64))
            .setAdmissionPointerSnapshotDigest("c".repeat(64))
            .build();
    return CharacterCreateTargetProtection.newBuilder()
        .setAccountProtection(account)
        .setGameSessionProtectionId(UUID.randomUUID().toString())
        .setGameSessionProtectionFence(17L)
        .setAcquiredAt("2026-10-24T00:00:00Z")
        .setOriginalTargetEvidence(ByteString.copyFrom(snapshot.toByteArray()))
        .build();
  }
}
