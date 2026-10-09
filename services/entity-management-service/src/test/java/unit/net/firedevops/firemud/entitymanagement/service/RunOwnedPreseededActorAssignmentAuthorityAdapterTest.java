package net.firedevops.firemud.entitymanagement.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence;
import net.firedevops.firemud.common.account.RuntimeAccountIdentityEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunOwnedPreseededActorAssignmentAuthorityAdapterTest {
  private static final String RUN_ID = "compose-smoke-9123";
  private static final String PROJECT_NAME = "firemud-smoke-compose-smoke-9123";
  private static final String NAMESPACE = "dev";
  private static final String URI = "spiffe://firemud/ns/dev/sa/game-session-service";
  private static final String TRUST_ROOT_SHA256 = "b".repeat(64);
  private static final UUID ASSIGNMENT_UUID =
      UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final UUID ACCOUNT_UUID = UUID.fromString("20000000-0000-4000-8000-000000000002");
  private static final UUID TENANT_UUID = UUID.fromString("30000000-0000-4000-8000-000000000003");
  private static final UUID REALM_UUID = UUID.fromString("40000000-0000-4000-8000-000000000004");
  private static final UUID NAMESPACE_UUID =
      UUID.fromString("50000000-0000-4000-8000-000000000005");

  @BeforeEach
  void clearSessionContextBeforeTest() {
    SessionContext.clear();
  }

  @AfterEach
  void clearSessionContextAfterTest() {
    SessionContext.clear();
  }

  @Test
  void validatesExactGrantThenRechecksItAgainstIndependentOwnerTarget(@TempDir Path directory)
      throws Exception {
    Path capabilityFile = writeGrant(directory, request());
    RunOwnedPreseededActorAssignmentAuthorityAdapter authority =
        authority(capabilityFile.toString());

    withGameSessionPeer(
        () -> {
          assertThatCode(
                  () ->
                      authority.requireAuthorized(
                          request(),
                          RunOwnedPreseededAssignmentAuthority.Action.PRESEEDED_ACTOR_ASSIGNMENT))
              .doesNotThrowAnyException();
          assertThatCode(
                  () ->
                      authority.requireTargetBoundAuthorized(
                          request(),
                          ownerEvidence(),
                          RunOwnedPreseededAssignmentAuthority.Action.PRESEEDED_ACTOR_ASSIGNMENT))
              .doesNotThrowAnyException();
          assertThatThrownBy(
                  () ->
                      authority.requireTargetBoundAuthorized(
                          request(),
                          ownerEvidenceWithNamespace(
                              UUID.fromString("60000000-0000-4000-8000-000000000006")),
                          RunOwnedPreseededAssignmentAuthority.Action.PRESEEDED_ACTOR_ASSIGNMENT))
              .isInstanceOf(AdminAuthorizationException.class);
        });
  }

  @Test
  void changedOrAbsentGrantDeniesBeforeOwnerReadsAndRepositoryMutation(@TempDir Path directory)
      throws Exception {
    PreseededActorAssignmentRequest request = request();
    PreseededActorAssignmentRequest otherIntent =
        new PreseededActorAssignmentRequest(
            UUID.fromString("10000000-0000-4000-8000-000000000009"),
            ACCOUNT_UUID,
            request.corePayload(),
            request.expectedTarget());
    Path capabilityFile = writeGrant(directory, otherIntent);
    RunOwnedPreseededActorAssignmentAuthorityAdapter authority =
        authority(capabilityFile.toString());
    PreseededActorAccountIdentityPort accountIdentityPort =
        mock(PreseededActorAccountIdentityPort.class);
    PreseededActorAssignmentOwnerEvidencePort ownerEvidencePort =
        mock(PreseededActorAssignmentOwnerEvidencePort.class);
    CharacterRepository characterRepository = mock(CharacterRepository.class);
    PreseededActorAssignmentService service =
        new PreseededActorAssignmentService(
            authority, accountIdentityPort, stagingPort(), ownerEvidencePort, characterRepository);

    withGameSessionPeer(
        () ->
            assertThatThrownBy(() -> service.assign(request))
                .isInstanceOf(AdminAuthorizationException.class));
    verifyNoInteractions(accountIdentityPort, ownerEvidencePort, characterRepository);

    RunOwnedPreseededActorAssignmentAuthorityAdapter missingCapability = authority("");
    withGameSessionPeer(
        () ->
            assertThatThrownBy(
                    () ->
                        missingCapability.requireAuthorized(
                            request,
                            RunOwnedPreseededAssignmentAuthority.Action.PRESEEDED_ACTOR_ASSIGNMENT))
                .isInstanceOf(AdminAuthorizationException.class));
  }

  @Test
  void authenticatedUriMustBeExactAndUserContextRemainsDenied(@TempDir Path directory)
      throws Exception {
    Path capabilityFile = writeGrant(directory, request());
    RunOwnedPreseededActorAssignmentAuthorityAdapter authority =
        authority(capabilityFile.toString());

    assertThatThrownBy(
            () ->
                authority.requireAuthorized(
                    request(),
                    RunOwnedPreseededAssignmentAuthority.Action.PRESEEDED_ACTOR_ASSIGNMENT))
        .isInstanceOf(AdminAuthorizationException.class);
    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            new GrpcPeerIdentity(
                "spiffe://firemud/ns/other/sa/game-session-service",
                "other",
                "game-session-service"))
        .run(
            () ->
                assertThatThrownBy(
                        () ->
                            authority.requireAuthorized(
                                request(),
                                RunOwnedPreseededAssignmentAuthority.Action
                                    .PRESEEDED_ACTOR_ASSIGNMENT))
                    .isInstanceOf(AdminAuthorizationException.class));

    SessionContext.setContext(ACCOUNT_UUID.toString(), List.of(), java.util.Map.of());
    try {
      withGameSessionPeer(
          () ->
              assertThatThrownBy(
                      () ->
                          authority.requireAuthorized(
                              request(),
                              RunOwnedPreseededAssignmentAuthority.Action
                                  .PRESEEDED_ACTOR_ASSIGNMENT))
                  .isInstanceOf(AdminAuthorizationException.class));
    } finally {
      SessionContext.clear();
    }
  }

  @Test
  void grantReplacementBetweenOwnerReadsAndPersistenceFailsClosed(@TempDir Path directory)
      throws Exception {
    PreseededActorAssignmentRequest request = request();
    Path capabilityFile = writeGrant(directory, request);
    RunOwnedPreseededActorAssignmentAuthorityAdapter authority =
        authority(capabilityFile.toString());
    RuntimeAccountIdentityEvidence accountProof =
        new RuntimeAccountIdentityEvidence(
            1, "dev", ASSIGNMENT_UUID, ACCOUNT_UUID, 23L, "ACCOUNT_DATABASE_INSERT");
    PreseededActorAccountIdentityPort accountIdentityPort =
        mock(PreseededActorAccountIdentityPort.class);
    PreseededActorAssignmentOwnerEvidencePort ownerEvidencePort =
        mock(PreseededActorAssignmentOwnerEvidencePort.class);
    CharacterRepository characterRepository = mock(CharacterRepository.class);
    PreseededActorAssignmentService service =
        new PreseededActorAssignmentService(
            authority, accountIdentityPort, stagingPort(), ownerEvidencePort, characterRepository);
    when(accountIdentityPort.resolveRuntimeAccountIdentity(
            ACCOUNT_UUID.toString(), ASSIGNMENT_UUID.toString()))
        .thenAnswer(
            invocation -> {
              PreseededActorAssignmentRequest replacement =
                  new PreseededActorAssignmentRequest(
                      ASSIGNMENT_UUID,
                      ACCOUNT_UUID,
                      new PreseededActorCorePayload(
                          PreseededActorCorePayload.ActorKind.PLAYER, "Changed Actor"),
                      request.expectedTarget());
              replaceGrant(capabilityFile, replacement);
              return accountProof;
            });
    when(ownerEvidencePort.resolveCurrentEligibleTarget(request, accountProof, stagingEvidence()))
        .thenReturn(ownerEvidence());

    withGameSessionPeer(
        () ->
            assertThatThrownBy(() -> service.assign(request))
                .isInstanceOf(AdminAuthorizationException.class));

    verifyNoInteractions(characterRepository);
  }

  @Test
  void activeServerTrustRootPinIsReevaluatedAtTheOwnerBoundBoundary(@TempDir Path directory)
      throws Exception {
    Path capabilityFile = writeGrant(directory, request());
    AtomicReference<String> activeTrustRoot = new AtomicReference<>(TRUST_ROOT_SHA256);
    RunOwnedPreseededActorAssignmentAuthorityAdapter authority =
        new RunOwnedPreseededActorAssignmentAuthorityAdapter(
            capabilityFile.toString(), RUN_ID, PROJECT_NAME, NAMESPACE, activeTrustRoot::get);

    withGameSessionPeer(
        () -> {
          authority.requireAuthorized(
              request(), RunOwnedPreseededAssignmentAuthority.Action.PRESEEDED_ACTOR_ASSIGNMENT);
          activeTrustRoot.set("c".repeat(64));
          assertThatThrownBy(
                  () ->
                      authority.requireTargetBoundAuthorized(
                          request(),
                          ownerEvidence(),
                          RunOwnedPreseededAssignmentAuthority.Action.PRESEEDED_ACTOR_ASSIGNMENT))
              .isInstanceOf(AdminAuthorizationException.class);
        });
  }

  private static RunOwnedPreseededActorAssignmentAuthorityAdapter authority(String path) {
    return new RunOwnedPreseededActorAssignmentAuthorityAdapter(
        path, RUN_ID, PROJECT_NAME, NAMESPACE, () -> TRUST_ROOT_SHA256);
  }

  private static void withGameSessionPeer(Runnable action) {
    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            new GrpcPeerIdentity(URI, NAMESPACE, "game-session-service"))
        .run(action);
  }

  private static Path writeGrant(Path directory, PreseededActorAssignmentRequest request)
      throws Exception {
    Files.setPosixFilePermissions(
        directory,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));
    Path file = directory.resolve("run-owned-assignment-grant.json");
    Files.writeString(file, capabilityJson(request), StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(
        file,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));
    return file;
  }

  private static void replaceGrant(Path file, PreseededActorAssignmentRequest request)
      throws Exception {
    Files.setPosixFilePermissions(
        file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    Files.writeString(file, capabilityJson(request), StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(
        file,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));
  }

  private static String capabilityJson(PreseededActorAssignmentRequest request) {
    PreseededActorAssignmentExpectedTarget target = request.expectedTarget();
    return String.join(
            "%n",
            "{",
            "  \"schema\": \"%s\",",
            "  \"runId\": \"%s\",",
            "  \"composeProjectName\": \"%s\",",
            "  \"action\": \"%s\",",
            "  \"trustedGameSessionUriSan\": \"%s\",",
            "  \"trustedGameSessionNamespace\": \"%s\",",
            "  \"assignmentUuid\": \"%s\",",
            "  \"canonicalAccountUuid\": \"%s\",",
            "  \"expectedTarget\": {",
            "    \"canonicalTenantUuid\": \"%s\",",
            "    \"realmUuid\": \"%s\",",
            "    \"worldSlug\": \"%s\",",
            "    \"realmSlug\": \"%s\",",
            "    \"gameInstanceId\": \"%s\",",
            "    \"catalogRevision\": %d,",
            "    \"canonicalVersionUuid\": \"%s\",",
            "    \"frozenPolicyDigest\": \"%s\",",
            "    \"playableStateNamespaceUuid\": \"%s\",",
            "    \"publishedReleaseBundleRef\": \"%s\",",
            "    \"playableStateScope\": \"%s\"",
            "  },",
            "  \"corePayload\": {",
            "    \"actorKind\": \"%s\",",
            "    \"displayName\": \"%s\"",
            "  },",
            "  \"intentDigest\": \"%s\",",
            "  \"entityServerTrustRootSha256\": \"%s\"",
            "}")
        .formatted(
            RunOwnedPreseededActorAssignmentCapability.CAPABILITY_SCHEMA,
            RUN_ID,
            PROJECT_NAME,
            RunOwnedPreseededActorAssignmentCapability.ACTION,
            URI,
            NAMESPACE,
            request.assignmentUuid(),
            request.canonicalAccountUuid(),
            target.canonicalTenantUuid(),
            target.realmUuid(),
            target.worldSlug(),
            target.realmSlug(),
            target.gameInstanceId(),
            target.catalogRevision(),
            target.canonicalVersionUuid(),
            target.frozenPolicyDigest(),
            target.playableStateNamespaceId(),
            target.publishedReleaseBundleRef(),
            target.playableStateScope().name(),
            request.corePayload().actorKind().name(),
            request.corePayload().displayName(),
            PreseededActorAssignmentGrantIntentDigest.compute(request),
            TRUST_ROOT_SHA256);
  }

  private static PreseededActorStagingEligibilityPort stagingPort() {
    return (accountUuid, tenantUuid, assignmentUuid) -> {
      if (!ACCOUNT_UUID.toString().equals(accountUuid)
          || !TENANT_UUID.toString().equals(tenantUuid)
          || !ASSIGNMENT_UUID.toString().equals(assignmentUuid)) {
        throw new IllegalStateException("unexpected Account staging evidence request");
      }
      return stagingEvidence();
    };
  }

  private static AccountActorStagingEligibilityEvidence stagingEvidence() {
    return AccountActorStagingEligibilityEvidence.seal(
        NAMESPACE,
        ASSIGNMENT_UUID,
        ACCOUNT_UUID,
        TENANT_UUID,
        AccountActorStagingEligibilityEvidence.Purpose.PUBLIC_PRODUCTION_STAGING_ONLY,
        Instant.parse("2026-10-04T00:00:00Z"),
        "ACCOUNT_DATABASE_INSERT",
        "ACTIVE",
        "ACTIVE",
        true,
        "EXPLICIT_JOIN",
        17L,
        9L,
        "FRESH_GAME_DESIGN",
        UUID.fromString("60000000-0000-4000-8000-000000000006"),
        "sha256:" + "a".repeat(64),
        23L,
        UUID.fromString("70000000-0000-4000-8000-000000000007"),
        "sha256:" + "b".repeat(64),
        false);
  }

  private static PreseededActorAssignmentRequest request() {
    return new PreseededActorAssignmentRequest(
        ASSIGNMENT_UUID,
        ACCOUNT_UUID,
        new PreseededActorCorePayload(PreseededActorCorePayload.ActorKind.PLAYER, "Assigned Actor"),
        new PreseededActorAssignmentExpectedTarget(
            TENANT_UUID,
            REALM_UUID,
            "world",
            "realm",
            "game-instance",
            4L,
            UUID.fromString("17000000-0000-4000-8000-000000000017"),
            "2".repeat(64),
            NAMESPACE_UUID,
            "release-bundle/test",
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));
  }

  private static PreseededActorAssignmentOwnerEvidence ownerEvidence() {
    return ownerEvidenceWithNamespace(NAMESPACE_UUID);
  }

  private static PreseededActorAssignmentOwnerEvidence ownerEvidenceWithNamespace(
      UUID namespaceUuid) {
    return new PreseededActorAssignmentOwnerEvidence(
        ASSIGNMENT_UUID,
        ACCOUNT_UUID,
        PreseededActorAssignmentOwnerEvidence.Eligibility.ELIGIBLE,
        stagingEvidence().observedAt(),
        stagingEvidence().membershipAuthorityGeneration(),
        stagingEvidence().eligibilityDecisionDigest(),
        TENANT_UUID,
        REALM_UUID,
        "world",
        "realm",
        "game-instance",
        4L,
        UUID.fromString("17000000-0000-4000-8000-000000000017"),
        "2".repeat(64),
        namespaceUuid,
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        PreseededActorAssignmentOwnerEvidence.PublishedEntryPolicy.PRESEEDED_ONLY,
        PreseededActorAssignmentOwnerEvidence.AccountPurpose.PRESEEDED_ACTOR_STAGING,
        PreseededActorAssignmentOwnerEvidence.AccountCurrentness.CURRENT_AT_REVALIDATION,
        stagingEvidence().authoritySnapshotDigest(),
        "4".repeat(64),
        "release-bundle/test");
  }
}
