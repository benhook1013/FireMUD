package net.firedevops.firemud.entitymanagement.client;

import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateProtectionRequest;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateProtectionResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.entitymanagement.charactercreate.EntityCharacterCreateBindings;
import net.firedevops.firemud.entitymanagement.charactercreate.GameSessionCharacterCreateProtectionReader;
import net.firedevops.firemud.entitymanagement.v1.CharacterCreateProtectionReference;
import net.firedevops.firemud.gamesession.v1.CharacterCreateTargetServiceGrpc;
import net.firedevops.firemud.gamesession.v1.CharacterCreateTargetSnapshot;
import net.firedevops.firemud.gamesession.v1.ReadOriginalCharacterCreateTargetProtectionRequest;

/**
 * Direct authenticated creation-specific Game Session target-protection read, never roster
 * evidence.
 */
public final class GameSessionCharacterCreateTargetClient
    extends AbstractReloadingBlockingGrpcClient<
        CharacterCreateTargetServiceGrpc.CharacterCreateTargetServiceBlockingStub>
    implements GameSessionCharacterCreateProtectionReader {
  private final String namespace;

  public GameSessionCharacterCreateTargetClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace) {
    super(
        endpoints,
        EntityManagementSourceClientValidation.requireEntityManagementMtls(
            tls, "CHARACTER_CREATE target reads"),
        channels,
        GameSessionCharacterCreateTargetClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Namespace required");
    this.namespace = namespace;
  }

  public void init() throws SSLException, IOException {
    initOnce(
        "Game Session creation target client is closed",
        "Game Session creation target client is not initialized");
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameSessionService();
  }

  @Override
  protected String defaultTarget() {
    return "game-session-service:6565";
  }

  @Override
  protected CharacterCreateTargetServiceGrpc.CharacterCreateTargetServiceBlockingStub buildStub(
      ManagedChannel channel) {
    return CharacterCreateTargetServiceGrpc.newBlockingStub(channel)
        .withInterceptors(
            new GrpcServerPeerIdentityClientInterceptor(
                "spiffe://firemud/ns/" + namespace + "/sa/game-session-service"));
  }

  @Override
  public Readback readOriginal(
      ReadOriginalCharacterCreateProtectionResponse account,
      CharacterCreateProtectionReference reference) {
    requireNoAmbientSql("Independent Game Session creation protection read required");
    EntityCharacterCreateBindings.exact(account);
    EntityCharacterCreateBindings.requireReference(reference);
    if (!account.hasOriginal() || !namespace.equals(account.getOriginal().getTargetNamespace()))
      throw EntityCharacterCreateBindings.invalid();
    var original =
        net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateRequest.newBuilder()
            .setTargetNamespace(namespace)
            .setOperationId(account.getOriginal().getOperationId())
            .setMutationDigest(account.getOriginal().getMutationDigest())
            .build();
    var action = EntityCharacterCreateBindings.requireOriginal(original, account.getOriginal());
    var request =
        ReadOriginalCharacterCreateTargetProtectionRequest.newBuilder()
            .setAccountProtection(
                ReadOriginalCharacterCreateProtectionRequest.newBuilder()
                    .setOriginal(original)
                    .setAccountProtectionId(account.getAccountProtectionId())
                    .setAccountProtectionFence(account.getAccountProtectionFence()))
            .setGameSessionProtectionId(reference.getProtectionId())
            .setGameSessionProtectionFence(reference.getProtectionFence())
            .build();
    var reply =
        requireInitializedStub("Game Session creation target client is not initialized")
            .withMaxInboundMessageSize(
                EntityCharacterCreateBindings.MAX_GAME_SESSION_RESPONSE_BYTES)
            .withDeadlineAfter(5, TimeUnit.SECONDS)
            .readOriginalCharacterCreateTargetProtection(request);
    EntityCharacterCreateBindings.requireBound(
        reply, EntityCharacterCreateBindings.MAX_GAME_SESSION_RESPONSE_BYTES);
    if (!reply.hasAccountProtection()
        || !account.equals(reply.getAccountProtection())
        || !reference.getProtectionId().equals(reply.getGameSessionProtectionId())
        || reference.getProtectionFence() != reply.getGameSessionProtectionFence()
        || reply.getOriginalTargetEvidence().isEmpty())
      throw EntityCharacterCreateBindings.conflict();
    try {
      var snapshot = CharacterCreateTargetSnapshot.parseFrom(reply.getOriginalTargetEvidence());
      EntityCharacterCreateBindings.exact(snapshot);
      if (!Arrays.equals(snapshot.toByteArray(), reply.getOriginalTargetEvidence().toByteArray())
          || snapshot.getSchemaVersion() != 1
          || !account.getOriginal().getOriginalAction().equals(snapshot.getOriginalAction())
          || !namespace.equals(snapshot.getTargetNamespace())
          || !original.getOperationId().equals(snapshot.getOperationId())
          || !original.getMutationDigest().equals(snapshot.getMutationDigest())
          || !action.scope().tenantId().equals(snapshot.getCanonicalTenantId())
          || !action.target().realmId().equals(snapshot.getRealmId())
          || !action.target().gameInstanceId().equals(snapshot.getCanonicalGameInstanceId())
          || !action.target().versionId().equals(snapshot.getCanonicalVersionId())
          || !action
              .scope()
              .playableStateNamespaceId()
              .equals(snapshot.getPlayableStateNamespaceId())
          || !action
              .expectedVersion()
              .catalogRevision()
              .equals(Long.toString(snapshot.getCatalogRevision()))
          || !action
              .expectedVersion()
              .pointerVersion()
              .equals(Long.toString(snapshot.getPointerVersion()))
          || !action
              .expectedVersion()
              .activeLifecycleEpoch()
              .equals(Long.toString(snapshot.getActiveWorldEpoch()))
          || !snapshot.getCatalogReceiptDigest().matches("sha256:[0-9a-f]{64}")
          || !snapshot.getInitialAdmissionOwnerProofDigest().matches("sha256:[0-9a-f]{64}")
          || !snapshot.getAdmissionPointerSnapshotDigest().matches("[0-9a-f]{64}"))
        throw EntityCharacterCreateBindings.conflict();
      // This owner request identity is opaque; do not impose UUID shape on its native contract.
      if (snapshot.getInitialAdmissionRequestId().isBlank()
          || !snapshot
              .getInitialAdmissionRequestId()
              .equals(snapshot.getInitialAdmissionRequestId().trim()))
        throw EntityCharacterCreateBindings.invalid();
    } catch (InvalidProtocolBufferException invalid) {
      throw EntityCharacterCreateBindings.invalid();
    }
    return new Readback(account, reference, reply.getAcquiredAt(), reply.toByteString());
  }
}
