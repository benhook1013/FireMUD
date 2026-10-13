package net.firedevops.firemud.entitymanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.cert.CertificateExpiredException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunOwnedPreseededActorAssignmentCapabilityTest {
  private static final String RUN_ID = "compose-smoke-9123";
  private static final String PROJECT_NAME = "firemud-smoke-compose-smoke-9123";
  private static final String NAMESPACE = "dev";
  private static final String GAME_SESSION_URI = "spiffe://firemud/ns/dev/sa/game-session-service";
  private static final String ENTITY_TRUST_ROOT_SHA256 = "b".repeat(64);
  private static final UUID ASSIGNMENT_UUID =
      UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final UUID ACCOUNT_UUID = UUID.fromString("20000000-0000-4000-8000-000000000002");
  private static final UUID TENANT_UUID = UUID.fromString("30000000-0000-4000-8000-000000000003");
  private static final UUID REALM_UUID = UUID.fromString("40000000-0000-4000-8000-000000000004");
  private static final UUID NAMESPACE_UUID =
      UUID.fromString("50000000-0000-4000-8000-000000000005");

  @Test
  void parsesExactGrantAndBindsRunProjectPeerAssignmentTargetPayloadAndTlsTrustRoot() {
    RunOwnedPreseededActorAssignmentCapability capability = parse(validJson());

    assertThat(capability.matchesRequest(request())).isTrue();
    assertThat(capability.matchesOwnerTarget(request(), ownerEvidence())).isTrue();
  }

  @Test
  void rejectsUnknownDuplicateAndChangedIntentFields() {
    assertInvalid(
        validJson()
            .replace(
                RunOwnedPreseededActorAssignmentCapability.CAPABILITY_SCHEMA,
                "firemud.run-owned-preseeded-actor-assignment.v2"));
    assertInvalid(
        validJson()
            .replace(
                "\"canonicalVersionUuid\": \""
                    + request().expectedTarget().canonicalVersionUuid()
                    + "\"",
                "\"publishedVersionId\": 17, \"publishedVersionNumber\": 2"));
    assertInvalid(validJson().replace("\"action\":", "\"unexpected\":true,\"action\":"));
    assertInvalid(
        validJson()
            .replace(
                "\"action\": \"PRESEEDED_ACTOR_ASSIGNMENT\"",
                "\"action\": \"PRESEEDED_ACTOR_ASSIGNMENT\","
                    + "\"action\": \"PRESEEDED_ACTOR_ASSIGNMENT\""));
    assertInvalid(
        validJson().replace("\"worldSlug\": \"world\"", "\"extra\": 1, \"worldSlug\": \"world\""));
    assertInvalid(
        validJson()
            .replace("\"actorKind\": \"PLAYER\"", "\"extra\": 1, \"actorKind\": \"PLAYER\""));
    assertInvalid(
        validJson()
            .replace(
                "\"assignmentUuid\": \"" + ASSIGNMENT_UUID,
                "\"assignmentUuid\": \"10000000-0000-4000-8000-000000000009"));
    assertInvalid(
        validJson()
            .replace("\"displayName\": \"Assigned Actor\"", "\"displayName\": \"Changed Actor\""));
    assertInvalid(validJson().replace(ENTITY_TRUST_ROOT_SHA256, "c".repeat(64)));
  }

  @Test
  void rejectsChangedConfiguredRunProjectPeerNamespaceAndTrustRoot() {
    byte[] json = validJson().getBytes(StandardCharsets.UTF_8);

    assertInvalid(
        json, "different-run", PROJECT_NAME, GAME_SESSION_URI, NAMESPACE, ENTITY_TRUST_ROOT_SHA256);
    assertInvalid(
        json, RUN_ID, "different-project", GAME_SESSION_URI, NAMESPACE, ENTITY_TRUST_ROOT_SHA256);
    assertInvalid(
        json,
        RUN_ID,
        PROJECT_NAME,
        "spiffe://firemud/ns/other/sa/game-session-service",
        NAMESPACE,
        ENTITY_TRUST_ROOT_SHA256);
    assertInvalid(json, RUN_ID, PROJECT_NAME, GAME_SESSION_URI, "other", ENTITY_TRUST_ROOT_SHA256);
    assertInvalid(json, RUN_ID, PROJECT_NAME, GAME_SESSION_URI, NAMESPACE, "d".repeat(64));
  }

  @Test
  void rejectsWritableSymlinkedAndOversizedCapabilityFiles(@TempDir Path directory)
      throws Exception {
    makePrivateDirectory(directory);
    Path writableCapability = writeCapability(directory.resolve("writable.json"));
    assertThatThrownBy(
            () -> RunOwnedPreseededActorAssignmentCapability.readCapability(writableCapability))
        .isInstanceOf(RunOwnedPreseededActorAssignmentCapability.InvalidCapabilityException.class);

    Path readonlyCapability = writeCapability(directory.resolve("readonly.json"));
    Files.setPosixFilePermissions(readonlyCapability, readOnlyFilePermissions());
    Path symlink = directory.resolve("symlink.json");
    Files.createSymbolicLink(symlink, readonlyCapability.getFileName());
    assertThatThrownBy(() -> RunOwnedPreseededActorAssignmentCapability.readCapability(symlink))
        .isInstanceOf(RunOwnedPreseededActorAssignmentCapability.InvalidCapabilityException.class);

    Path oversized = directory.resolve("oversized.json");
    Files.writeString(oversized, "x".repeat(16 * 1024 + 1));
    Files.setPosixFilePermissions(oversized, readOnlyFilePermissions());
    assertThatThrownBy(() -> RunOwnedPreseededActorAssignmentCapability.readCapability(oversized))
        .isInstanceOf(RunOwnedPreseededActorAssignmentCapability.InvalidCapabilityException.class);
  }

  @Test
  void readsOnlyOwnerBoundNonWritableCapabilityFile(@TempDir Path directory) throws Exception {
    makePrivateDirectory(directory);
    Path capabilityFile = writeCapability(directory.resolve("capability.json"));
    Files.setPosixFilePermissions(capabilityFile, readOnlyFilePermissions());

    assertThat(RunOwnedPreseededActorAssignmentCapability.readCapability(capabilityFile))
        .isEqualTo(validJson().getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void trustRootPinRequiresOneValidCaAndUsesExactLoadedCertificateFingerprint() throws Exception {
    X509Certificate trustedRoot = mock(X509Certificate.class);
    when(trustedRoot.getBasicConstraints()).thenReturn(1);
    when(trustedRoot.getEncoded()).thenReturn(new byte[] {1, 2, 3});

    assertThat(
            RunOwnedPreseededActorAssignmentCapability.fingerprintValidatedTrustRoot(
                List.of(trustedRoot)))
        .isEqualTo(
            java.util.HexFormat.of()
                .formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(new byte[] {1, 2, 3})));

    String configuredFingerprint =
        RunOwnedPreseededActorAssignmentCapability.fingerprintValidatedTrustRoot(
            List.of(trustedRoot));
    X509TrustManager loadedTrustManager = mock(X509TrustManager.class);
    when(loadedTrustManager.getAcceptedIssuers()).thenReturn(new X509Certificate[] {trustedRoot});
    assertThat(
            RunOwnedPreseededActorAssignmentCapability.activeTrustRootFingerprint(
                new TrustManager[] {loadedTrustManager}, configuredFingerprint))
        .isEqualTo(configuredFingerprint);
    assertThatThrownBy(
            () ->
                RunOwnedPreseededActorAssignmentCapability.activeTrustRootFingerprint(
                    new TrustManager[] {loadedTrustManager}, "c".repeat(64)))
        .isInstanceOf(RunOwnedPreseededActorAssignmentCapability.InvalidCapabilityException.class);
    when(loadedTrustManager.getAcceptedIssuers())
        .thenReturn(new X509Certificate[] {trustedRoot, trustedRoot});
    assertThatThrownBy(
            () ->
                RunOwnedPreseededActorAssignmentCapability.activeTrustRootFingerprint(
                    new TrustManager[] {loadedTrustManager}, configuredFingerprint))
        .isInstanceOf(RunOwnedPreseededActorAssignmentCapability.InvalidCapabilityException.class);

    doThrow(new CertificateExpiredException()).when(trustedRoot).checkValidity();
    assertThatThrownBy(
            () ->
                RunOwnedPreseededActorAssignmentCapability.fingerprintValidatedTrustRoot(
                    List.of(trustedRoot)))
        .isInstanceOf(RunOwnedPreseededActorAssignmentCapability.InvalidCapabilityException.class);
    assertThatThrownBy(
            () ->
                RunOwnedPreseededActorAssignmentCapability.fingerprintValidatedTrustRoot(
                    List.of(trustedRoot, trustedRoot)))
        .isInstanceOf(RunOwnedPreseededActorAssignmentCapability.InvalidCapabilityException.class);
  }

  private static RunOwnedPreseededActorAssignmentCapability parse(String json) {
    return parse(
        json.getBytes(StandardCharsets.UTF_8),
        RUN_ID,
        PROJECT_NAME,
        GAME_SESSION_URI,
        NAMESPACE,
        ENTITY_TRUST_ROOT_SHA256);
  }

  private static RunOwnedPreseededActorAssignmentCapability parse(
      byte[] json,
      String runId,
      String projectName,
      String gameSessionUri,
      String namespace,
      String trustRootSha256) {
    return RunOwnedPreseededActorAssignmentCapability.parseAndValidate(
        json, runId, projectName, gameSessionUri, namespace, trustRootSha256);
  }

  private static void assertInvalid(String json) {
    assertInvalid(
        json.getBytes(StandardCharsets.UTF_8),
        RUN_ID,
        PROJECT_NAME,
        GAME_SESSION_URI,
        NAMESPACE,
        ENTITY_TRUST_ROOT_SHA256);
  }

  private static void assertInvalid(
      byte[] json,
      String runId,
      String projectName,
      String gameSessionUri,
      String namespace,
      String trustRootSha256) {
    assertThatThrownBy(
            () -> parse(json, runId, projectName, gameSessionUri, namespace, trustRootSha256))
        .isInstanceOf(RunOwnedPreseededActorAssignmentCapability.InvalidCapabilityException.class);
  }

  private static Path writeCapability(Path path) throws Exception {
    return Files.writeString(path, validJson());
  }

  private static void makePrivateDirectory(Path directory) throws Exception {
    Files.setPosixFilePermissions(
        directory,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));
  }

  private static Set<PosixFilePermission> readOnlyFilePermissions() {
    return Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.OTHERS_READ);
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
    return new PreseededActorAssignmentOwnerEvidence(
        ASSIGNMENT_UUID,
        ACCOUNT_UUID,
        PreseededActorAssignmentOwnerEvidence.Eligibility.ELIGIBLE,
        Instant.parse("2026-10-04T00:00:00Z"),
        9L,
        "1".repeat(64),
        TENANT_UUID,
        REALM_UUID,
        "world",
        "realm",
        "game-instance",
        4L,
        UUID.fromString("17000000-0000-4000-8000-000000000017"),
        "2".repeat(64),
        NAMESPACE_UUID,
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        PreseededActorAssignmentOwnerEvidence.PublishedEntryPolicy.PRESEEDED_ONLY,
        PreseededActorAssignmentOwnerEvidence.AccountPurpose.PRESEEDED_ACTOR_STAGING,
        PreseededActorAssignmentOwnerEvidence.AccountCurrentness.CURRENT_AT_REVALIDATION,
        "3".repeat(64),
        "4".repeat(64),
        "release-bundle/test");
  }

  private static String validJson() {
    PreseededActorAssignmentRequest request = request();
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
            GAME_SESSION_URI,
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
            ENTITY_TRUST_ROOT_SHA256);
  }
}
