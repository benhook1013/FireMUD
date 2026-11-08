package net.firedevops.firemud.gamesession.repository;

import io.grpc.BindableService;
import io.grpc.ServerInterceptors;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleClient;
import net.firedevops.firemud.gamesession.client.GameDesignPublishedRealmPolicyClient;
import net.firedevops.firemud.gamesession.security.CanonicalGameplayAdmissionDecisionReadWorkloadGuard;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.impl.CanonicalGameplayAdmissionDecisionReadGrpcService;
import org.jooq.DSLContext;

/**
 * Assembles actual current Game Session source readers over caller-owned PostgreSQL state. The
 * caller owns source provenance, authenticated clients, socket identity and cleanup. This fixture
 * creates no rows, changes no registration and does not authorize gameplay admission.
 */
public final class CanonicalPublishedAdmissionActualOwnerCompositionFixture {
  private CanonicalPublishedAdmissionActualOwnerCompositionFixture() {}

  public static BindableService receiver(
      DSLContext dsl,
      String namespace,
      GameDesignPublishedRealmPolicyClient policies,
      WorldCanonicalInstanceLifecycleClient world) {
    var catalogs = new GameSessionCanonicalRealmCatalogRepository(dsl);
    var admissions =
        new CanonicalInitialAdmissionRepository(
            dsl,
            catalogs,
            new GameSessionCanonicalAdmissionPointerRepository(
                dsl, catalogs, new GameplayAdmissionPointerEventRepository(dsl)));
    var launches = new CanonicalGameInstanceLaunchAssociationRepository(dsl, namespace);
    var routes = new CanonicalPlayerRouteReadService(namespace, catalogs, admissions, launches);
    var published =
        new CanonicalPublishedPlayerRouteReadService(
            namespace, routes, catalogs, launches, policies);
    var guard = new CanonicalGameplayAdmissionDecisionReadWorkloadGuard(namespace);
    var receiver =
        new CanonicalGameplayAdmissionDecisionReadGrpcService(
            new CanonicalGameplayBindingInventoryRepository(dsl),
            guard,
            namespace,
            null,
            routes,
            published,
            launches,
            world);
    return () -> ServerInterceptors.intercept(receiver, guard);
  }
}
