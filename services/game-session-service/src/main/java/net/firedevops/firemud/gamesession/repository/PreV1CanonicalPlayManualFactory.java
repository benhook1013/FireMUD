package net.firedevops.firemud.gamesession.repository;

import io.lettuce.core.RedisClient;
import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.common.prev1.PreV1GateWaivers;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayAccountInitialBarrierRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayAccountMemberRepairRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayCapacityOriginCustodyRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerAdmissionBarrierRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBindingLocalRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBindingLocalReleaseRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBindingLocalRetirementRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBootstrapPartitionRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayOriginalNewCandidateClosureRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayOriginalNewCandidateRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayOriginalNewOriginFinalAdmissionRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRegionBindingBridgeRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRegionLeaseRedisScriptContribution;
import org.jooq.DSLContext;

/** Public construction path for the pre-v1 canonical PLAY stand-ins (ADR 0185 waiver). */
public final class PreV1CanonicalPlayManualFactory {
  private PreV1CanonicalPlayManualFactory() {}

  /** Every canonical gameplay Coordination script the issuer and PLAY composition fixtures use. */
  public static RedisScriptCatalog mergedScriptCatalog() {
    return RedisScriptCatalog.fromContributions(
        List.of(
            new CanonicalGameplayIssuerBootstrapPartitionRedisScriptContribution(),
            new CanonicalGameplayIssuerBindingLocalRedisScriptContribution(),
            new CanonicalGameplayIssuerBindingLocalReleaseRedisScriptContribution(),
            new CanonicalGameplayIssuerBindingLocalRetirementRedisScriptContribution(),
            new CanonicalGameplayOriginalNewCandidateRedisScriptContribution(),
            new CanonicalGameplayOriginalNewCandidateClosureRedisScriptContribution(),
            new CanonicalGameplayAccountInitialBarrierRedisScriptContribution(),
            new CanonicalGameplayAccountMemberRepairRedisScriptContribution(),
            new CanonicalGameplayIssuerAdmissionBarrierRedisScriptContribution(),
            new CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution(),
            new CanonicalGameplayRegionLeaseRedisScriptContribution(),
            new CanonicalGameplayRegionBindingBridgeRedisScriptContribution(),
            new CanonicalGameplayCapacityOriginCustodyRedisScriptContribution(),
            new CanonicalGameplayOriginalNewOriginFinalAdmissionRedisScriptContribution()));
  }

  /**
   * Composes the stand-ins. Refuses unless the pre-v1 gate waivers are enabled; {@link
   * PreV1GateWaivers#from} already refuses them under the prod profile. Nothing touches Redis or
   * SQL until a stand-in is used.
   */
  public static PreV1CanonicalPlayStandIns create(
      PreV1GateWaivers waivers,
      DSLContext dsl,
      RedisClient coordination,
      String workloadNamespace) {
    if (waivers == null || !waivers.enabled()) {
      throw new IllegalStateException("Pre-v1 canonical PLAY stand-ins require the gate waivers");
    }
    Objects.requireNonNull(dsl, "dsl");
    Objects.requireNonNull(coordination, "coordination");
    var scripts = mergedScriptCatalog();
    var tags =
        new CanonicalGameplayTenantGameplayTagRepository(
            dsl, workloadNamespace, PreV1CanonicalPlayStandIns.TAG_ALLOCATION_VERSION);
    var observer = new PreV1CoordinationStorageObserver(dsl, coordination);
    var issuerState = new PreV1CurrentIssuerStateSource(observer::redisRunId);
    return new PreV1CanonicalPlayStandIns(
        coordination,
        scripts,
        tags,
        issuerState,
        new PreV1LocalIncarnationAttestor(observer::redisRunId),
        new PreV1RegionIncarnationSource(observer::redisRunId),
        new PreV1CanonicalGameplayStoreBootstrap(dsl, coordination, scripts, observer, issuerState),
        new PreV1CanonicalPlayBudgets(
            dsl, new GameSessionCanonicalRealmCatalogRepository(dsl), tags, workloadNamespace));
  }
}
