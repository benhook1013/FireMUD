package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.lettuce.core.RedisClient;
import java.math.BigInteger;
import java.time.Clock;
import java.util.UUID;
import net.firedevops.firemud.common.prev1.PreV1GateWaivers;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

class PreV1CanonicalPlayStandInsTest {
  private static final String RUN_ID = "0123456789abcdef0123456789abcdef01234567";
  private static final String OTHER_RUN_ID = "fedcba9876543210fedcba9876543210fedcba98";
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void factoryRefusesWithoutTheGateWaivers() {
    DSLContext dsl = mock(DSLContext.class);
    RedisClient redis = mock(RedisClient.class);

    assertThatThrownBy(() -> PreV1CanonicalPlayManualFactory.create(null, dsl, redis, "test"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("gate waivers");
    assertThatThrownBy(
            () ->
                PreV1CanonicalPlayManualFactory.create(
                    PreV1GateWaivers.disabled(), dsl, redis, "test"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("gate waivers");
    verifyNoInteractions(dsl, redis);
  }

  @Test
  void factoryComposesWithoutTouchingRedisOrSql() {
    DSLContext dsl = mock(DSLContext.class);
    RedisClient redis = mock(RedisClient.class);

    var standIns =
        PreV1CanonicalPlayManualFactory.create(
            PreV1GateWaivers.enabledForTest(), dsl, redis, "test");

    assertThat(standIns.storeBootstrap()).isNotNull();
    assertThat(standIns.budgets()).isNotNull();
    assertThat(standIns.coordination()).isSameAs(redis);
    verifyNoInteractions(dsl, redis);
  }

  @Test
  void mergedCatalogCarriesTheIssuerAndPlayScripts() {
    RedisScriptCatalog catalog = PreV1CanonicalPlayManualFactory.mergedScriptCatalog();

    assertThat(catalog.descriptors()).hasSizeGreaterThanOrEqualTo(14);
  }

  @Test
  void issuerStateIsTheInitialGenerationWithARunIdIncarnation() {
    var source = new PreV1CurrentIssuerStateSource(() -> RUN_ID);

    var state = source.readFreshCurrentState();
    var trusted = source.trustedCheckpoint();

    assertThat(state.accountCheckpoint().issuerId())
        .isEqualTo(PreV1CurrentIssuerStateSource.ISSUER);
    assertThat(state.accountCheckpoint().issuerAuthGeneration()).isEqualTo(BigInteger.ONE);
    assertThat(state.accountCheckpoint().latestIssuerEventBytes()).isNull();
    assertThat(state.accountCheckpoint().exactCheckpointBytes())
        .isEqualTo(trusted.checkpointBytes());
    assertThat(trusted.latestEventBytes()).isNull();
    assertThat(state.incarnation())
        .isEqualTo(PreV1CoordinationIncarnation.forRedisRunId(RUN_ID).issuerIncarnation());
    assertThat(new PreV1CurrentIssuerStateSource(() -> OTHER_RUN_ID).readFreshCurrentState())
        .extracting(value -> value.incarnation())
        .isNotEqualTo(state.incarnation());
  }

  @Test
  void malformedOrMissingRunIdIsRefused() {
    assertThatThrownBy(() -> new PreV1CurrentIssuerStateSource(() -> "abc").readFreshCurrentState())
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new PreV1CurrentIssuerStateSource(() -> null).readFreshCurrentState())
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                new PreV1LocalIncarnationAttestor(() -> RUN_ID.toUpperCase())
                    .trustedConfiguration())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void regionIncarnationIsStableEvidenceForTheExactScopeAndRunId() {
    UUID tenantId = UUID.randomUUID();
    UUID gameInstanceId = UUID.randomUUID();
    UUID regionId = UUID.randomUUID();
    var scope =
        new CanonicalGameplayRegionIncarnationSource.RegionScope(
            tenantId,
            gameInstanceId,
            regionId,
            CanonicalGameplayRegionRedisKeys.tenantRegionTag(tenantId, gameInstanceId, regionId));
    var source = new PreV1RegionIncarnationSource(() -> RUN_ID);

    var evidence = source.readCurrent(scope);

    assertThat(evidence.scope()).isEqualTo(scope);
    assertThat(evidence.qualifiedNonResetProfile()).isTrue();
    assertThat(evidence.attestorBinding()).isEqualTo(PreV1CoordinationIncarnation.ATTESTOR_BINDING);
    assertThat(evidence.targetDeploymentEvidence())
        .isEqualTo(PreV1CoordinationIncarnation.forRedisRunId(RUN_ID).deployment());
    assertThat(evidence.timelineInitializationQualification().qualifiedNonResetProfileId())
        .isEqualTo(PreV1RegionIncarnationSource.NON_RESET_PROFILE_ID);
    assertThat(source.readCurrent(scope).digest()).isEqualTo(evidence.digest());
    assertThat(new PreV1RegionIncarnationSource(() -> OTHER_RUN_ID).readCurrent(scope).digest())
        .isNotEqualTo(evidence.digest());
  }

  @Test
  void localAttestorRoundTripsThroughTheActualReceiver() {
    var fixture = new InitialContext();
    var attestor = new PreV1LocalIncarnationAttestor(() -> RUN_ID);
    var receiver =
        attestor.initialBarrierReceiver(fixture.repository, fixture.tags, Clock.systemUTC());

    var verified = receiver.observeForInitialAccountBarrier(fixture.requestId);

    assertThat(verified.ownerKind())
        .isEqualTo(CanonicalGameplayProtectedIncarnationReceiver.OwnerKind.INITIAL_ACCOUNT_BARRIER);
    assertThat(verified.operationId()).isEqualTo(fixture.initializerId);
    assertThat(verified.requestId()).isEqualTo(fixture.requestId);
    assertThat(verified.acceptedAttestationDigest())
        .isEqualTo(PreV1CoordinationIncarnation.forRedisRunId(RUN_ID).acceptedAttestationDigest());
    var restartedProcess = new PreV1LocalIncarnationAttestor(() -> RUN_ID);
    assertThat(
            restartedProcess
                .initialBarrierReceiver(fixture.repository, fixture.tags, Clock.systemUTC())
                .observeForInitialAccountBarrier(fixture.requestId)
                .incarnationDigest())
        .as("the per-process key is not part of the incarnation")
        .isEqualTo(verified.incarnationDigest());
  }

  @Test
  void receiverRejectsAnotherProcessesAttestorKey() {
    var fixture = new InitialContext();
    var trusted = new PreV1LocalIncarnationAttestor(() -> RUN_ID);
    var other = new PreV1LocalIncarnationAttestor(() -> RUN_ID);
    var receiver =
        new CanonicalGameplayProtectedIncarnationReceiver(
            fixture.repository,
            fixture.tags,
            other,
            trusted.trustedConfiguration(),
            Clock.systemUTC());

    assertThatThrownBy(() -> receiver.observeForInitialAccountBarrier(fixture.requestId))
        .isInstanceOf(RuntimeException.class);
  }

  private static final class InitialContext {
    final UUID requestId = UUID.randomUUID();
    final UUID initializerId = UUID.randomUUID();
    final CanonicalGameplayAccountInitialBarrierInitializerRepository repository =
        mock(CanonicalGameplayAccountInitialBarrierInitializerRepository.class);
    final CanonicalGameplayTenantGameplayTagRepository tags =
        mock(CanonicalGameplayTenantGameplayTagRepository.class);

    InitialContext() {
      UUID callerContextId = UUID.randomUUID();
      UUID accountId = UUID.randomUUID();
      UUID tenantId = UUID.randomUUID();
      UUID namespaceId = UUID.randomUUID();
      when(repository.readCurrentObservationContext(requestId))
          .thenReturn(
              new CanonicalGameplayAccountInitialBarrierInitializerRepository
                  .InitialObservationContext(
                  initializerId,
                  requestId,
                  callerContextId,
                  17L,
                  accountId,
                  tenantId,
                  namespaceId,
                  "NO_ACTIVE_ACCOUNT_WIDE_FLOW",
                  BigInteger.valueOf(8),
                  BigInteger.valueOf(4),
                  DIGEST,
                  DIGEST,
                  DIGEST));
      var assignment = mock(CanonicalGameplayTenantGameplayTagRepository.Assignment.class);
      when(tags.assignmentForInitialBarrierTarget(requestId)).thenReturn(assignment);
      when(assignment.isInitialBarrierTarget()).thenReturn(true);
      when(assignment.initialBarrierInitializerId()).thenReturn(initializerId);
      when(assignment.initialBarrierOriginalRequestId()).thenReturn(requestId);
      when(assignment.accountId()).thenReturn(accountId);
      when(assignment.tenantId()).thenReturn(tenantId);
      when(assignment.playableStateNamespaceId()).thenReturn(namespaceId);
      when(assignment.tenantGameplayTag()).thenReturn("g1-" + tenantId.toString().replace("-", ""));
      when(assignment.workloadNamespace()).thenReturn("test");
      when(assignment.allocationVersion()).thenReturn(1);
    }
  }
}
