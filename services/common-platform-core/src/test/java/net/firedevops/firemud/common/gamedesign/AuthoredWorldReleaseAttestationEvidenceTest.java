package net.firedevops.firemud.common.gamedesign;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthoredWorldReleaseAttestationEvidenceTest {
  private static final String TENANT = "12345678-1234-4234-8234-123456789abc";
  private static final String VERSION = "32345678-1234-4234-8234-123456789abc";
  private static final String SOURCE_OPERATION = "22345678-1234-4234-8234-123456789abc";
  private static final String SOURCE_DIGEST = digest("a");
  private static final String GENERATION_REVISION = "gen-rév-🧭";
  private static final long MAX_EPOCH = Long.MAX_VALUE;
  private static final String EXPECTED_DESCRIPTOR_RESULT_DIGEST =
      "sha256:7129db5acb9aa27830598cdcfe4996f2f2259cfbe1f6dc137db5d39ec8ebc80c";
  private static final String EXPECTED_EVIDENCE_DIGEST =
      "sha256:c1a8d369e3012ced0247b6de05a152fc0434b565db611acc9095447184cbe42f";

  @Test
  void fullEvidenceMatchesIndependentUnicodeFramingVectorAndExactDescriptor() {
    Fixture fixture = fixture();
    String preimage =
        text(AuthoredWorldReleaseAttestationEvidence.evidencePreimage(fixture.evidence()));

    assertEquals(EXPECTED_DESCRIPTOR_RESULT_DIGEST, fixture.descriptor().resultDigest());
    assertEquals(EXPECTED_EVIDENCE_DIGEST, fixture.evidence().evidenceDigest());
    assertEquals(
        EXPECTED_EVIDENCE_DIGEST,
        AuthoredWorldReleaseAttestationEvidence.computeEvidenceDigest(fixture.evidence()));
    assertTrue(preimage.startsWith("49:game-design-authored-world-release-attestation/v1"));
    assertTrue(preimage.contains("18:launchDescriptorId5:ld-é"));
    assertTrue(preimage.contains("25:publishedReleaseBundleRef18:release-bundle:雪"));
    assertTrue(preimage.contains("10:commit-雪"));
    assertTrue(preimage.contains("13:gen-rév-🧭"));
    assertTrue(preimage.contains("43:participantDigests[0].baseVersionId.present5:false"));
    assertTrue(preimage.contains("49:participantDigests[2].abilitySchemaDigest.present4:true"));
    assertTrue(preimage.contains("37:artifactDigests[0].immutableObjectKey81:artifacts/sha256/"));
    assertEquals(MAX_EPOCH, fixture.evidence().versionStateEpoch());
    assertEquals(
        List.of(
            "WORLD_MANAGEMENT",
            "ENTITY_MANAGEMENT",
            "GAME_LOGIC",
            "AUTOMATION_SCRIPTING",
            "GAME_DESIGN_CONTROL_PLANE"),
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder());
    assertEquals(
        List.of(2, 2, 1, 5, 1),
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(AuthoredWorldReleaseAttestationEvidence::supportedParticipantDigestSchema)
            .toList());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                "UNSUPPORTED"));
    assertDoesNotThrow(() -> fixture.evidence().requireValid(fixture.descriptor()));
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            fixture
                .evidence()
                .participantDigests()
                .add(fixture.evidence().participantDigests().get(0)));
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            fixture.evidence().artifactDigests().add(fixture.evidence().artifactDigests().get(0)));
  }

  @Test
  void changedFieldsAndRetainedArrayOrderChangeTheDigest() {
    Fixture fixture = fixture();
    AuthoredWorldReleaseAttestationEvidence changedGeneration =
        evidence(
            fixture.descriptor(),
            MAX_EPOCH,
            fixture.evidence().participantDigests(),
            fixture.evidence().requiredManifestAssetKeys(),
            fixture.evidence().artifactDigests(),
            fixture.evidence().commandDefinitions(),
            "gen-rév-🧭-next");
    AuthoredWorldReleaseAttestationEvidence changedCommandOrder =
        evidence(
            fixture.descriptor(),
            MAX_EPOCH,
            fixture.evidence().participantDigests(),
            fixture.evidence().requiredManifestAssetKeys(),
            fixture.evidence().artifactDigests(),
            List.of("say 雪", "LOOK"),
            GENERATION_REVISION);
    AuthoredWorldReleaseAttestationEvidence changedEpoch =
        evidence(
            fixture.descriptor(),
            MAX_EPOCH - 1,
            fixture.evidence().participantDigests(),
            fixture.evidence().requiredManifestAssetKeys(),
            fixture.evidence().artifactDigests(),
            fixture.evidence().commandDefinitions(),
            GENERATION_REVISION);

    assertNotEquals(fixture.evidence().evidenceDigest(), changedGeneration.evidenceDigest());
    assertNotEquals(fixture.evidence().evidenceDigest(), changedCommandOrder.evidenceDigest());
    assertNotEquals(fixture.evidence().evidenceDigest(), changedEpoch.evidenceDigest());
    assertThrows(
        IllegalArgumentException.class, () -> changedGeneration.requireValid(fixture.descriptor()));
    assertThrows(
        IllegalArgumentException.class, () -> changedEpoch.requireValid(fixture.descriptor()));

    AuthoredWorldReleaseAttestationEvidence tampered =
        rawCopy(
            fixture.evidence(),
            fixture.evidence().evidenceDigest(),
            List.of("changed command", "LOOK"));
    assertThrows(IllegalArgumentException.class, tampered::requireValid);
  }

  @Test
  void fullVersionParticipantSetIsExactOrderedAndBoundToTheDescriptorVersion() {
    Fixture fixture = fixture();
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        fixture.evidence().participantDigests();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            evidence(
                fixture.descriptor(),
                MAX_EPOCH,
                participants.subList(0, 4),
                keys(),
                artifacts(),
                commands(),
                GENERATION_REVISION));

    List<AuthoredWorldReleaseAttestationEvidence.Participant> wrongOwner =
        new ArrayList<>(participants);
    wrongOwner.set(
        0,
        participant("ENTITY_MANAGEMENT", "42", false, null, "commit-雪", digest("a"), false, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            evidence(
                fixture.descriptor(),
                MAX_EPOCH,
                wrongOwner,
                keys(),
                artifacts(),
                commands(),
                GENERATION_REVISION));

    List<AuthoredWorldReleaseAttestationEvidence.Participant> duplicateOwner =
        new ArrayList<>(participants);
    duplicateOwner.set(
        4,
        participant(
            "AUTOMATION_SCRIPTING", "42", false, null, "commit-雪", digest("e"), false, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            evidence(
                fixture.descriptor(),
                MAX_EPOCH,
                duplicateOwner,
                keys(),
                artifacts(),
                commands(),
                GENERATION_REVISION));

    List<AuthoredWorldReleaseAttestationEvidence.Participant> wrongScope =
        participantsWithScope("43");
    AuthoredWorldReleaseAttestationEvidence wrongScopeEvidence =
        evidence(
            fixture.descriptor(),
            MAX_EPOCH,
            wrongScope,
            keys(),
            artifacts(),
            commands(),
            GENERATION_REVISION);
    assertThrows(
        IllegalArgumentException.class,
        () -> wrongScopeEvidence.requireValid(fixture.descriptor()));

    AuthoredWorldLaunchDescriptorEvidence version43 =
        descriptor(
            "test",
            "cp-α",
            uuid(TENANT),
            "copper-coast",
            uuid(SOURCE_OPERATION),
            SOURCE_DIGEST,
            "ld-é",
            "release-bundle:雪",
            43L,
            MAX_EPOCH,
            GENERATION_REVISION,
            "{}");
    AuthoredWorldReleaseAttestationEvidence version43WrongScope =
        AuthoredWorldReleaseAttestationEvidence.create(
            "test",
            version43.resultDigest(),
            uuid(TENANT),
            uuid(VERSION),
            "copper-coast",
            uuid(SOURCE_OPERATION),
            SOURCE_DIGEST,
            "ld-é",
            "release-bundle:雪",
            MAX_EPOCH,
            "publish:tenant-key:publish-request:pr-é",
            "commit-雪",
            participants,
            digest("9"),
            1,
            keys(),
            artifacts(),
            commands(),
            GENERATION_REVISION);
    assertThrows(IllegalArgumentException.class, () -> version43WrongScope.requireValid(version43));
  }

  @Test
  void participantScopeBaseCommitSchemaAndAbilityEvidenceFailClosed() {
    Fixture fixture = fixture();
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        fixture.evidence().participantDigests();

    List<AuthoredWorldReleaseAttestationEvidence.Participant> wrongBase =
        new ArrayList<>(participants);
    wrongBase.set(
        0, participant("WORLD_MANAGEMENT", "42", true, 7L, "commit-雪", digest("a"), false, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            evidence(
                fixture.descriptor(),
                MAX_EPOCH,
                wrongBase,
                keys(),
                artifacts(),
                commands(),
                GENERATION_REVISION));

    List<AuthoredWorldReleaseAttestationEvidence.Participant> wrongCommit =
        participantsWithCommit("other-commit");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            evidence(
                fixture.descriptor(),
                MAX_EPOCH,
                wrongCommit,
                keys(),
                artifacts(),
                commands(),
                GENERATION_REVISION));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            participant(
                "WORLD_MANAGEMENT", "42", false, null, "commit-雪", digest("a"), false, null, 0));

    List<AuthoredWorldReleaseAttestationEvidence.Participant> wrongSchema =
        new ArrayList<>(participants);
    wrongSchema.set(
        0,
        participant(
            "WORLD_MANAGEMENT", "42", false, null, "commit-雪", digest("a"), false, null, 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            evidence(
                fixture.descriptor(),
                MAX_EPOCH,
                wrongSchema,
                keys(),
                artifacts(),
                commands(),
                GENERATION_REVISION));

    List<AuthoredWorldReleaseAttestationEvidence.Participant> missingAbility =
        new ArrayList<>(participants);
    missingAbility.set(
        2, participant("GAME_LOGIC", "42", false, null, "commit-雪", digest("c"), false, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            evidence(
                fixture.descriptor(),
                MAX_EPOCH,
                missingAbility,
                keys(),
                artifacts(),
                commands(),
                GENERATION_REVISION));

    List<AuthoredWorldReleaseAttestationEvidence.Participant> wrongAbilityOwner =
        new ArrayList<>(participants);
    wrongAbilityOwner.set(
        0,
        participant(
            "WORLD_MANAGEMENT", "42", false, null, "commit-雪", digest("a"), true, digest("f")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            evidence(
                fixture.descriptor(),
                MAX_EPOCH,
                wrongAbilityOwner,
                keys(),
                artifacts(),
                commands(),
                GENERATION_REVISION));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            participant(
                "GAME_LOGIC", "42", false, null, "commit-雪", digest("c"), true, "not-a-digest"));
  }

  @Test
  void participantContentDigestUsesRawLowercaseHexWhileOtherDigestFieldsStayPrefixed() {
    assertDoesNotThrow(
        () ->
            new AuthoredWorldReleaseAttestationEvidence.Participant(
                "WORLD_MANAGEMENT", "42", false, null, "commit-雪", "a".repeat(64), 2, false, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AuthoredWorldReleaseAttestationEvidence.Participant(
                "WORLD_MANAGEMENT", "42", false, null, "commit-雪", digest("a"), 2, false, null));
    assertDoesNotThrow(
        () ->
            new AuthoredWorldReleaseAttestationEvidence.Artifact(
                "world.navmesh",
                "NAVMESH",
                artifactKey("3"),
                digest("3"),
                "application/vnd.firemud.navmesh+binary",
                1));
  }

  @Test
  void artifactsMustMatchContentAddressAndRequiredKeysAndManifestMustBePresent() {
    Fixture fixture = fixture();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AuthoredWorldReleaseAttestationEvidence.Artifact(
                "world.navmesh",
                "NAVMESH",
                artifactKey("4"),
                digest("3"),
                "application/vnd.firemud.navmesh+binary",
                1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AuthoredWorldReleaseAttestationEvidence.Artifact(
                "world.navmesh",
                "NAVMESH",
                artifactKey("3"),
                digest("3"),
                "application/vnd.firemud.navmesh+binary",
                2));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AuthoredWorldReleaseAttestationEvidence.create(
                "test",
                fixture.evidence().descriptorResultDigest(),
                uuid(TENANT),
                uuid(VERSION),
                "copper-coast",
                uuid(SOURCE_OPERATION),
                SOURCE_DIGEST,
                "ld-é",
                "release-bundle:雪",
                MAX_EPOCH,
                "publish:tenant-key:publish-request:pr-é",
                "commit-雪",
                fixture.evidence().participantDigests(),
                digest("9"),
                1,
                List.of("world.missing"),
                artifacts(),
                commands(),
                GENERATION_REVISION));
    assertThrows(
        NullPointerException.class,
        () ->
            AuthoredWorldReleaseAttestationEvidence.create(
                "test",
                fixture.evidence().descriptorResultDigest(),
                uuid(TENANT),
                uuid(VERSION),
                "copper-coast",
                uuid(SOURCE_OPERATION),
                SOURCE_DIGEST,
                "ld-é",
                "release-bundle:雪",
                MAX_EPOCH,
                "publish:tenant-key:publish-request:pr-é",
                "commit-雪",
                fixture.evidence().participantDigests(),
                null,
                1,
                keys(),
                artifacts(),
                commands(),
                GENERATION_REVISION));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AuthoredWorldReleaseAttestationEvidence.create(
                "test",
                fixture.evidence().descriptorResultDigest(),
                uuid(TENANT),
                uuid(VERSION),
                "copper-coast",
                uuid(SOURCE_OPERATION),
                SOURCE_DIGEST,
                "ld-é",
                "release-bundle:雪",
                MAX_EPOCH,
                "publish:tenant-key:publish-request:pr-é",
                "commit-雪",
                fixture.evidence().participantDigests(),
                digest("9"),
                2,
                keys(),
                artifacts(),
                commands(),
                GENERATION_REVISION));

    AuthoredWorldReleaseAttestationEvidence emptyManifest =
        AuthoredWorldReleaseAttestationEvidence.create(
            "test",
            fixture.evidence().descriptorResultDigest(),
            uuid(TENANT),
            uuid(VERSION),
            "copper-coast",
            uuid(SOURCE_OPERATION),
            SOURCE_DIGEST,
            "ld-é",
            "release-bundle:雪",
            MAX_EPOCH,
            "publish:tenant-key:publish-request:pr-é",
            "commit-雪",
            fixture.evidence().participantDigests(),
            digest("9"),
            1,
            List.of(),
            List.of(),
            commands(),
            GENERATION_REVISION);
    assertDoesNotThrow(() -> emptyManifest.requireValid(fixture.descriptor()));
  }

  @Test
  void descriptorSubstitutionsAreRejectedAndRequestIdentityIsBoundByResultDigest() {
    Fixture fixture = fixture();
    List<AuthoredWorldLaunchDescriptorEvidence> substitutions =
        List.of(
            descriptor(
                "other",
                "cp-α",
                uuid(TENANT),
                "copper-coast",
                uuid(SOURCE_OPERATION),
                SOURCE_DIGEST,
                "ld-é",
                "release-bundle:雪",
                42L,
                MAX_EPOCH,
                GENERATION_REVISION,
                "{}"),
            descriptor(
                "test",
                "cp-other",
                uuid(TENANT),
                "copper-coast",
                uuid(SOURCE_OPERATION),
                SOURCE_DIGEST,
                "ld-é",
                "release-bundle:雪",
                42L,
                MAX_EPOCH,
                GENERATION_REVISION,
                "{}"),
            descriptor(
                "test",
                "cp-α",
                uuid(TENANT),
                "copper-shore",
                uuid(SOURCE_OPERATION),
                SOURCE_DIGEST,
                "ld-é",
                "release-bundle:雪",
                42L,
                MAX_EPOCH,
                GENERATION_REVISION,
                "{}"),
            descriptor(
                "test",
                "cp-α",
                uuid("52345678-1234-4234-8234-123456789abc"),
                "copper-coast",
                uuid(SOURCE_OPERATION),
                SOURCE_DIGEST,
                "ld-é",
                "release-bundle:雪",
                42L,
                MAX_EPOCH,
                GENERATION_REVISION,
                "{}"),
            descriptor(
                "test",
                "cp-α",
                uuid(TENANT),
                "copper-coast",
                uuid("62345678-1234-4234-8234-123456789abc"),
                SOURCE_DIGEST,
                "ld-é",
                "release-bundle:雪",
                42L,
                MAX_EPOCH,
                GENERATION_REVISION,
                "{}"),
            descriptor(
                "test",
                "cp-α",
                uuid(TENANT),
                "copper-coast",
                uuid(SOURCE_OPERATION),
                digest("b"),
                "ld-é",
                "release-bundle:雪",
                42L,
                MAX_EPOCH,
                GENERATION_REVISION,
                "{}"),
            descriptor(
                "test",
                "cp-α",
                uuid(TENANT),
                "copper-coast",
                uuid(SOURCE_OPERATION),
                SOURCE_DIGEST,
                "ld-other",
                "release-bundle:雪",
                42L,
                MAX_EPOCH,
                GENERATION_REVISION,
                "{}"),
            descriptor(
                "test",
                "cp-α",
                uuid(TENANT),
                "copper-coast",
                uuid(SOURCE_OPERATION),
                SOURCE_DIGEST,
                "ld-é",
                "release-bundle:other",
                42L,
                MAX_EPOCH,
                GENERATION_REVISION,
                "{}"),
            descriptor(
                "test",
                "cp-α",
                uuid(TENANT),
                "copper-coast",
                uuid(SOURCE_OPERATION),
                SOURCE_DIGEST,
                "ld-é",
                "release-bundle:雪",
                42L,
                MAX_EPOCH - 1,
                GENERATION_REVISION,
                "{}"),
            descriptor(
                "test",
                "cp-α",
                uuid(TENANT),
                "copper-coast",
                uuid(SOURCE_OPERATION),
                SOURCE_DIGEST,
                "ld-é",
                "release-bundle:雪",
                42L,
                MAX_EPOCH,
                "new-generation",
                "{}"),
            descriptor(
                "test",
                "cp-α",
                uuid(TENANT),
                "copper-coast",
                uuid(SOURCE_OPERATION),
                SOURCE_DIGEST,
                "ld-é",
                "release-bundle:雪",
                42L,
                MAX_EPOCH,
                GENERATION_REVISION,
                "{\"different\":true}"));

    for (AuthoredWorldLaunchDescriptorEvidence substitution : substitutions) {
      assertThrows(
          IllegalArgumentException.class, () -> fixture.evidence().requireValid(substitution));
    }

    List<AuthoredWorldReleaseAttestationEvidence> changedEvidenceBindings =
        List.of(
            evidenceWithBindings(
                fixture,
                "other",
                fixture.evidence().descriptorResultDigest(),
                uuid(TENANT),
                "copper-coast",
                uuid(SOURCE_OPERATION),
                SOURCE_DIGEST,
                "ld-é",
                "release-bundle:雪",
                MAX_EPOCH,
                GENERATION_REVISION),
            evidenceWithBindings(
                fixture,
                "test",
                digest("8"),
                uuid(TENANT),
                "copper-coast",
                uuid(SOURCE_OPERATION),
                SOURCE_DIGEST,
                "ld-é",
                "release-bundle:雪",
                MAX_EPOCH,
                GENERATION_REVISION),
            evidenceWithBindings(
                fixture,
                "test",
                fixture.evidence().descriptorResultDigest(),
                uuid("52345678-1234-4234-8234-123456789abc"),
                "copper-coast",
                uuid(SOURCE_OPERATION),
                SOURCE_DIGEST,
                "ld-é",
                "release-bundle:雪",
                MAX_EPOCH,
                GENERATION_REVISION),
            evidenceWithBindings(
                fixture,
                "test",
                fixture.evidence().descriptorResultDigest(),
                uuid(TENANT),
                "copper-shore",
                uuid(SOURCE_OPERATION),
                SOURCE_DIGEST,
                "ld-é",
                "release-bundle:雪",
                MAX_EPOCH,
                GENERATION_REVISION),
            evidenceWithBindings(
                fixture,
                "test",
                fixture.evidence().descriptorResultDigest(),
                uuid(TENANT),
                "copper-coast",
                uuid("62345678-1234-4234-8234-123456789abc"),
                SOURCE_DIGEST,
                "ld-é",
                "release-bundle:雪",
                MAX_EPOCH,
                GENERATION_REVISION),
            evidenceWithBindings(
                fixture,
                "test",
                fixture.evidence().descriptorResultDigest(),
                uuid(TENANT),
                "copper-coast",
                uuid(SOURCE_OPERATION),
                digest("b"),
                "ld-é",
                "release-bundle:雪",
                MAX_EPOCH,
                GENERATION_REVISION),
            evidenceWithBindings(
                fixture,
                "test",
                fixture.evidence().descriptorResultDigest(),
                uuid(TENANT),
                "copper-coast",
                uuid(SOURCE_OPERATION),
                SOURCE_DIGEST,
                "ld-other",
                "release-bundle:雪",
                MAX_EPOCH,
                GENERATION_REVISION),
            evidenceWithBindings(
                fixture,
                "test",
                fixture.evidence().descriptorResultDigest(),
                uuid(TENANT),
                "copper-coast",
                uuid(SOURCE_OPERATION),
                SOURCE_DIGEST,
                "ld-é",
                "release-bundle:other",
                MAX_EPOCH,
                GENERATION_REVISION),
            evidenceWithBindings(
                fixture,
                "test",
                fixture.evidence().descriptorResultDigest(),
                uuid(TENANT),
                "copper-coast",
                uuid(SOURCE_OPERATION),
                SOURCE_DIGEST,
                "ld-é",
                "release-bundle:雪",
                MAX_EPOCH - 1,
                GENERATION_REVISION),
            evidenceWithBindings(
                fixture,
                "test",
                fixture.evidence().descriptorResultDigest(),
                uuid(TENANT),
                "copper-coast",
                uuid(SOURCE_OPERATION),
                SOURCE_DIGEST,
                "ld-é",
                "release-bundle:雪",
                MAX_EPOCH,
                "new-generation"));
    for (AuthoredWorldReleaseAttestationEvidence changed : changedEvidenceBindings) {
      assertThrows(
          IllegalArgumentException.class, () -> changed.requireValid(fixture.descriptor()));
    }
  }

  private static Fixture fixture() {
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        descriptor(
            "test",
            "cp-α",
            uuid(TENANT),
            "copper-coast",
            uuid(SOURCE_OPERATION),
            SOURCE_DIGEST,
            "ld-é",
            "release-bundle:雪",
            42L,
            MAX_EPOCH,
            GENERATION_REVISION,
            "{}");
    AuthoredWorldReleaseAttestationEvidence evidence =
        evidence(
            descriptor,
            MAX_EPOCH,
            participantsWithScope("42"),
            keys(),
            artifacts(),
            commands(),
            GENERATION_REVISION);
    return new Fixture(evidence, descriptor);
  }

  private static AuthoredWorldReleaseAttestationEvidence evidence(
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      long epoch,
      List<AuthoredWorldReleaseAttestationEvidence.Participant> participants,
      List<String> requiredKeys,
      List<AuthoredWorldReleaseAttestationEvidence.Artifact> artifacts,
      List<String> commandDefinitions,
      String generationRevision) {
    return evidenceWithBindings(
        descriptor,
        descriptor.targetNamespace(),
        descriptor.resultDigest(),
        descriptor.canonicalTenantId(),
        descriptor.worldSlug(),
        descriptor.authoredWorldSourceOperationId(),
        descriptor.authoredWorldSourceEvidenceDigest(),
        descriptor.launchDescriptorId(),
        descriptor.publishedReleaseBundleRef(),
        epoch,
        generationRevision,
        participants,
        requiredKeys,
        artifacts,
        commandDefinitions);
  }

  private static AuthoredWorldReleaseAttestationEvidence evidenceWithBindings(
      Fixture fixture,
      String namespace,
      String descriptorResultDigest,
      UUID tenantId,
      String worldSlug,
      UUID sourceOperationId,
      String sourceEvidenceDigest,
      String launchDescriptorId,
      String releaseBundleRef,
      long epoch,
      String generationRevision) {
    return evidenceWithBindings(
        fixture.descriptor(),
        namespace,
        descriptorResultDigest,
        tenantId,
        worldSlug,
        sourceOperationId,
        sourceEvidenceDigest,
        launchDescriptorId,
        releaseBundleRef,
        epoch,
        generationRevision,
        fixture.evidence().participantDigests(),
        fixture.evidence().requiredManifestAssetKeys(),
        fixture.evidence().artifactDigests(),
        fixture.evidence().commandDefinitions());
  }

  private static AuthoredWorldReleaseAttestationEvidence evidenceWithBindings(
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      String namespace,
      String descriptorResultDigest,
      UUID tenantId,
      String worldSlug,
      UUID sourceOperationId,
      String sourceEvidenceDigest,
      String launchDescriptorId,
      String releaseBundleRef,
      long epoch,
      String generationRevision,
      List<AuthoredWorldReleaseAttestationEvidence.Participant> participants,
      List<String> requiredKeys,
      List<AuthoredWorldReleaseAttestationEvidence.Artifact> artifacts,
      List<String> commandDefinitions) {
    return AuthoredWorldReleaseAttestationEvidence.create(
        namespace,
        descriptorResultDigest,
        tenantId,
        uuid(VERSION),
        worldSlug,
        sourceOperationId,
        sourceEvidenceDigest,
        launchDescriptorId,
        releaseBundleRef,
        epoch,
        "publish:tenant-key:publish-request:pr-é",
        "commit-雪",
        participants,
        digest("9"),
        1,
        requiredKeys,
        artifacts,
        commandDefinitions,
        generationRevision);
  }

  private static AuthoredWorldLaunchDescriptorEvidence descriptor(
      String namespace,
      String requestId,
      UUID tenantId,
      String worldSlug,
      UUID sourceOperationId,
      String sourceEvidenceDigest,
      String launchDescriptorId,
      String releaseBundleRef,
      long versionId,
      long epoch,
      String generationRevision,
      String runtimeFlags) {
    AuthoredWorldLaunchDescriptorEvidence.Request request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            namespace,
            requestId,
            tenantId,
            worldSlug,
            sourceOperationId,
            sourceEvidenceDigest,
            19L,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    return AuthoredWorldLaunchDescriptorEvidence.create(
        request,
        launchDescriptorId,
        versionId,
        false,
        null,
        runtimeFlags,
        generationRevision,
        epoch,
        7L,
        releaseBundleRef,
        false,
        null);
  }

  private static List<AuthoredWorldReleaseAttestationEvidence.Participant> participantsWithScope(
      String scope) {
    return List.of(
        participant("WORLD_MANAGEMENT", scope, false, null, "commit-雪", digest("a"), false, null),
        participant("ENTITY_MANAGEMENT", scope, false, null, "commit-雪", digest("b"), false, null),
        participant("GAME_LOGIC", scope, false, null, "commit-雪", digest("c"), true, digest("f")),
        participant(
            "AUTOMATION_SCRIPTING", scope, false, null, "commit-雪", digest("d"), false, null),
        participant(
            "GAME_DESIGN_CONTROL_PLANE", scope, false, null, "commit-雪", digest("e"), false, null));
  }

  private static List<AuthoredWorldReleaseAttestationEvidence.Participant> participantsWithCommit(
      String commit) {
    return List.of(
        participant("WORLD_MANAGEMENT", "42", false, null, commit, digest("a"), false, null),
        participant("ENTITY_MANAGEMENT", "42", false, null, commit, digest("b"), false, null),
        participant("GAME_LOGIC", "42", false, null, commit, digest("c"), true, digest("f")),
        participant("AUTOMATION_SCRIPTING", "42", false, null, commit, digest("d"), false, null),
        participant(
            "GAME_DESIGN_CONTROL_PLANE", "42", false, null, commit, digest("e"), false, null));
  }

  private static AuthoredWorldReleaseAttestationEvidence.Participant participant(
      String key,
      String scope,
      boolean baseVersionPresent,
      Long baseVersion,
      String commit,
      String contentDigest,
      boolean abilityPresent,
      String abilityDigest) {
    return participant(
        key,
        scope,
        baseVersionPresent,
        baseVersion,
        commit,
        contentDigest,
        abilityPresent,
        abilityDigest,
        switch (key) {
          case "WORLD_MANAGEMENT", "ENTITY_MANAGEMENT" -> 2;
          case "AUTOMATION_SCRIPTING" -> 5;
          case "GAME_LOGIC", "GAME_DESIGN_CONTROL_PLANE" -> 1;
          default -> 1;
        });
  }

  private static AuthoredWorldReleaseAttestationEvidence.Participant participant(
      String key,
      String scope,
      boolean baseVersionPresent,
      Long baseVersion,
      String commit,
      String contentDigest,
      boolean abilityPresent,
      String abilityDigest,
      int schemaVersion) {
    return new AuthoredWorldReleaseAttestationEvidence.Participant(
        key,
        scope,
        baseVersionPresent,
        baseVersion,
        commit,
        contentDigest.startsWith("sha256:")
            ? contentDigest.substring("sha256:".length())
            : contentDigest,
        schemaVersion,
        abilityPresent,
        abilityDigest);
  }

  private static List<String> keys() {
    return List.of("world.navmesh", "world.雪");
  }

  private static List<AuthoredWorldReleaseAttestationEvidence.Artifact> artifacts() {
    return List.of(
        artifact("world.navmesh", "NAVMESH", "3", "application/vnd.firemud.navmesh+binary"),
        artifact("world.雪", "PATH_GRAPH", "4", "application/vnd.firemud.path-graph+binary"));
  }

  private static AuthoredWorldReleaseAttestationEvidence.Artifact artifact(
      String usageKey, String kind, String digestHex, String contentType) {
    return new AuthoredWorldReleaseAttestationEvidence.Artifact(
        usageKey, kind, artifactKey(digestHex), digest(digestHex), contentType, 1);
  }

  private static String artifactKey(String digestHex) {
    return "artifacts/sha256/" + digestHex.repeat(64);
  }

  private static List<String> commands() {
    return List.of("LOOK", "say 雪");
  }

  private static AuthoredWorldReleaseAttestationEvidence rawCopy(
      AuthoredWorldReleaseAttestationEvidence evidence,
      String evidenceDigest,
      List<String> commandDefinitions) {
    return new AuthoredWorldReleaseAttestationEvidence(
        evidence.schemaVersion(),
        evidence.targetNamespace(),
        evidence.descriptorResultDigest(),
        evidence.canonicalTenantId(),
        evidence.canonicalVersionId(),
        evidence.worldSlug(),
        evidence.authoredWorldSourceOperationId(),
        evidence.authoredWorldSourceEvidenceDigest(),
        evidence.launchDescriptorId(),
        evidence.publishedReleaseBundleRef(),
        evidence.versionStateEpoch(),
        evidence.publishWorkflowId(),
        evidence.commitId(),
        evidence.participantDigests(),
        evidence.manifestHash(),
        evidence.manifestSchemaVersion(),
        evidence.requiredManifestAssetKeys(),
        evidence.artifactDigests(),
        commandDefinitions,
        evidence.generationConfigRevision(),
        evidenceDigest);
  }

  private static UUID uuid(String text) {
    return UUID.fromString(text);
  }

  private static String digest(String value) {
    return "sha256:" + value.repeat(64);
  }

  private static String text(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private record Fixture(
      AuthoredWorldReleaseAttestationEvidence evidence,
      AuthoredWorldLaunchDescriptorEvidence descriptor) {}
}
