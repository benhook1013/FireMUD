package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.BindableService;
import java.util.Objects;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeWorldClosureAuthorizationReadClient;
import org.jooq.DSLContext;

/** Test-only composition of the actual recipient-specific World inventory reader and receiver. */
public final class WorldSelectedOwnerInventoryReadProofFixture {
  private WorldSelectedOwnerInventoryReadProofFixture() {}

  public static BindableService receiver(
      String workloadNamespace,
      DSLContext worldDsl,
      WorldDesignPublicationFenceRepository worldFence,
      SelectedOwnerIntakeWorldClosureAuthorizationReadClient accountReadClient) {
    Objects.requireNonNull(worldDsl, "worldDsl");
    Objects.requireNonNull(worldFence, "worldFence");
    Objects.requireNonNull(accountReadClient, "accountReadClient");

    var authorizationRepository =
        new WorldSelectedDraftPublicationAuthorizationRepository(worldDsl);
    var inventoryRepository = new WorldSelectedPublicationArtifactInventoryRepository(worldDsl);
    var inventoryReadService =
        new WorldSelectedPublicationArtifactInventoryReadService(
            workloadNamespace, worldFence, authorizationRepository, inventoryRepository);
    var readService =
        new WorldSelectedOwnerInventoryReadService(
            workloadNamespace, inventoryReadService, accountReadClient);
    return new WorldSelectedOwnerInventoryReadGrpcService(workloadNamespace, readService);
  }
}
