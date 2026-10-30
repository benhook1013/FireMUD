package net.firedevops.firemud.worldmanagement.tenant;

import java.io.IOException;
import java.util.Objects;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.worldmanagement.client.EntityPlayerCreatedActorPlacementReceiptClient;
import net.firedevops.firemud.worldmanagement.client.EntityPreseededActorAssignmentReceiptClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionCanonicalInitialAdmissionOwnerClient;
import org.jooq.DSLContext;
import org.springframework.transaction.PlatformTransactionManager;

/** Opt-in manual owner composition; this factory is not a Spring component or default registrar. */
public final class CanonicalInitialPlayerLocationManualFactory {
  private CanonicalInitialPlayerLocationManualFactory() {}

  public static Components create(
      String trustedNamespace,
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      WorldCanonicalInstanceAssociationRepository associationRepository,
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory)
      throws SSLException, IOException {
    Objects.requireNonNull(dsl, "dsl");
    Objects.requireNonNull(transactionManager, "transactionManager");
    Objects.requireNonNull(associationRepository, "associationRepository");
    Objects.requireNonNull(endpoints, "endpoints");
    Objects.requireNonNull(channelFactory, "channelFactory");

    WorldCanonicalInstanceLifecycleReadRepository lifecycleRepository =
        new WorldCanonicalInstanceLifecycleReadRepository(
            dsl, transactionManager, associationRepository);
    WorldCanonicalInitialAdmissionHoldRepository holdRepository =
        new WorldCanonicalInitialAdmissionHoldRepository(
            dsl, transactionManager, associationRepository, lifecycleRepository);
    WorldCanonicalInitialPlayerLocationRepository locationRepository =
        new WorldCanonicalInitialPlayerLocationRepository(
            dsl, transactionManager, lifecycleRepository);
    EntityPreseededActorAssignmentReceiptClient entityClient =
        new EntityPreseededActorAssignmentReceiptClient(
            endpoints, tlsProperties, channelFactory, trustedNamespace);
    EntityPlayerCreatedActorPlacementReceiptClient createdClient =
        new EntityPlayerCreatedActorPlacementReceiptClient(
            endpoints, tlsProperties, channelFactory, trustedNamespace);
    GrpcGameSessionCanonicalInitialAdmissionOwnerClient gameSessionClient =
        new GrpcGameSessionCanonicalInitialAdmissionOwnerClient(
            endpoints, tlsProperties, channelFactory, trustedNamespace);
    try {
      entityClient.init();
      createdClient.init();
      gameSessionClient.init();
      CanonicalInitialPlayerLocationService service =
          new CanonicalInitialPlayerLocationService(
              trustedNamespace,
              associationRepository,
              holdRepository,
              locationRepository,
              gameSessionClient,
              entityClient,
              createdClient);
      var grpcService = new CanonicalInitialPlayerLocationGrpcService(trustedNamespace, service);
      var interceptor = new CanonicalInitialPlayerLocationPeerInterceptor(trustedNamespace);
      return new Components(
          grpcService, interceptor, entityClient, gameSessionClient, createdClient);
    } catch (IOException | RuntimeException failure) {
      try {
        createdClient.close();
      } catch (IOException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      try {
        entityClient.close();
      } catch (IOException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      try {
        gameSessionClient.close();
      } catch (IOException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  /** Concrete standalone components for a caller-owned test or opt-in server composition. */
  public record Components(
      CanonicalInitialPlayerLocationGrpcService service,
      CanonicalInitialPlayerLocationPeerInterceptor peerInterceptor,
      EntityPreseededActorAssignmentReceiptClient entityClient,
      GrpcGameSessionCanonicalInitialAdmissionOwnerClient gameSessionClient,
      EntityPlayerCreatedActorPlacementReceiptClient createdClient)
      implements AutoCloseable {
    public Components {
      Objects.requireNonNull(service, "service");
      Objects.requireNonNull(peerInterceptor, "peerInterceptor");
      Objects.requireNonNull(entityClient, "entityClient");
      Objects.requireNonNull(gameSessionClient, "gameSessionClient");
      Objects.requireNonNull(createdClient, "createdClient");
    }

    @Override
    public void close() throws IOException {
      IOException failure = null;
      try {
        entityClient.close();
      } catch (IOException closeFailure) {
        failure = closeFailure;
      }
      try {
        gameSessionClient.close();
      } catch (IOException closeFailure) {
        if (failure == null) failure = closeFailure;
        else failure.addSuppressed(closeFailure);
      }
      try {
        createdClient.close();
      } catch (IOException closeFailure) {
        if (failure == null) failure = closeFailure;
        else failure.addSuppressed(closeFailure);
      }
      if (failure != null) throw failure;
    }
  }
}
