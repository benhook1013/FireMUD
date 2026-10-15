package net.firedevops.firemud.worldmanagement.service.impl;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateClient;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceClient;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.config.WorldProperties;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldFixtureEvidenceTestFixtures;
import net.firedevops.firemud.worldmanagement.data.RunOwnedCanonicalWorldLaunchEvidence;
import net.firedevops.firemud.worldmanagement.data.RunOwnedCanonicalWorldLaunchEvidenceProvider;
import net.firedevops.firemud.worldmanagement.dto.WorldInstanceLifecycleSnapshotDto;
import net.firedevops.firemud.worldmanagement.repository.RegionInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomInstanceExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldEventRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeService;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCompleteLaunchBindingRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository;
import org.jooq.DSLContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Test-only package bridge; World owners/SQL are real and only upstream reads are controlled. */
public final class RunOwnedWorldFixtureLifecycleTestFactory {
  private RunOwnedWorldFixtureLifecycleTestFactory() {}

  public static WorldLifecycleCommandServiceImpl create(
      DSLContext dsl,
      PlatformTransactionManager transactions,
      RunOwnedCanonicalWorldLaunchEvidenceProvider provider,
      AuthoredWorldSourceClient sourceClient,
      AuthoredWorldVersionStateClient versionClient,
      GameDesignClient gameDesign,
      EntityManagementClient entityManagement) {
    var sources = new WorldAuthoredSourceIntakeRepository(dsl);
    var versions = new WorldAuthoredVersionIdentityRepository(dsl);
    var association =
        new WorldCanonicalInstanceAssociationRepository(
            dsl, new WorldCompleteLaunchBindingRepository(dsl), sources, versions);
    var sourceService =
        new WorldAuthoredSourceIntakeService(sourceClient, sources, transactions, "dev");
    var versionService =
        new WorldAuthoredVersionIdentityService(
            versionClient, versions, sources, transactions, "dev");
    var write = new TransactionTemplate(transactions);
    write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var properties = new WorldProperties();
    properties.setLocalShardId(7);
    var service =
        new WorldLifecycleCommandServiceImpl(
            new WorldInstanceRepository(dsl),
            new RegionInstanceRepository(dsl),
            new ZoneRepository(dsl),
            new ZoneInstanceRepository(dsl),
            new RoomRepository(dsl),
            new RoomExitRepository(dsl),
            new RoomInstanceRepository(dsl),
            new RoomInstanceExitRepository(dsl),
            new WorldEventRepository(dsl),
            properties,
            gameDesign,
            entityManagement,
            new SimpleMeterRegistry(),
            write,
            provider,
            dsl,
            new WorldLifecycleCommandServiceImpl.RunOwnedWorldFixtureOwners(
                sourceClient,
                versionClient,
                sourceService,
                versionService,
                association,
                new WorldDesignPublicationFenceRepository(dsl, sources)));
    service.initMetrics();
    return service;
  }

  /** Owner-local preparation; callers must supply their real or explicitly controlled GS peer. */
  public static WorldInstanceLifecycleSnapshotDto prepare(
      WorldLifecycleCommandServiceImpl service, RunOwnedCanonicalWorldLaunchEvidence evidence) {
    return service.prepareRunOwnedFixture(
        RunOwnedWorldFixtureEvidenceTestFixtures.request(evidence), evidence);
  }
}
