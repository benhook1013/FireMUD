package net.firedevops.firemud.common.gamedesign;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;

/** Closed immutable full release-attestation evidence for an authored-world launch. */
public record AuthoredWorldReleaseAttestationEvidence(
    int schemaVersion,
    String targetNamespace,
    String descriptorResultDigest,
    UUID canonicalTenantId,
    UUID canonicalVersionId,
    String worldSlug,
    UUID authoredWorldSourceOperationId,
    String authoredWorldSourceEvidenceDigest,
    String launchDescriptorId,
    String publishedReleaseBundleRef,
    long versionStateEpoch,
    String publishWorkflowId,
    String commitId,
    List<Participant> participantDigests,
    String manifestHash,
    int manifestSchemaVersion,
    List<String> requiredManifestAssetKeys,
    List<Artifact> artifactDigests,
    List<String> commandDefinitions,
    String generationConfigRevision,
    String evidenceDigest,
    @JsonInclude(JsonInclude.Include.NON_NULL)
        WorldPublishedStartLocationEvidence worldStartLocationEvidence) {
  public static final int SCHEMA_VERSION = 1;
  public static final int SELECTOR_SCHEMA_VERSION = 2;
  public static final int CLOSURE_SELECTOR_SCHEMA_VERSION = 3;
  private static final String EVIDENCE_DOMAIN = "game-design-authored-world-release-attestation/v1";
  private static final String SELECTOR_EVIDENCE_DOMAIN =
      "game-design-authored-world-release-attestation/v2";
  private static final String CLOSURE_SELECTOR_EVIDENCE_DOMAIN =
      "game-design-authored-world-release-attestation/v3";
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Pattern PARTICIPANT_CONTENT_DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern CANONICAL_POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]*");
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final List<String> PARTICIPANT_ORDER =
      List.of(
          "WORLD_MANAGEMENT",
          "ENTITY_MANAGEMENT",
          "GAME_LOGIC",
          "AUTOMATION_SCRIPTING",
          "GAME_DESIGN_CONTROL_PLANE");
  private static final Map<String, Integer> SUPPORTED_PARTICIPANT_DIGEST_SCHEMAS =
      Map.of(
          "WORLD_MANAGEMENT", 3,
          "ENTITY_MANAGEMENT", 2,
          "GAME_LOGIC", 1,
          "AUTOMATION_SCRIPTING", 5,
          "GAME_DESIGN_CONTROL_PLANE", 1);
  private static final Comparator<String> UNSIGNED_UTF8_ORDER =
      AuthoredWorldReleaseAttestationEvidence::compareUnsignedUtf8;

  private record Field(String name, String value) {}

  /** Returns the required full-Version participant owner order. */
  public static List<String> requiredParticipantOrder() {
    return PARTICIPANT_ORDER;
  }

  /** Returns the one supported digest schema for a required participant owner. */
  public static int supportedParticipantDigestSchema(String participantKey) {
    return supportedParticipantDigestSchema(participantKey, SCHEMA_VERSION);
  }

  /** Selects owner schemas from the retained-v1, selected-v2, or closure-selected-v3 contract. */
  public static int supportedParticipantDigestSchema(
      String participantKey, int attestationSchemaVersion) {
    if (attestationSchemaVersion != SCHEMA_VERSION
        && attestationSchemaVersion != SELECTOR_SCHEMA_VERSION
        && attestationSchemaVersion != CLOSURE_SELECTOR_SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported authored-world release-attestation schema");
    }
    Objects.requireNonNull(participantKey, "participantKey");
    Integer version = SUPPORTED_PARTICIPANT_DIGEST_SCHEMAS.get(participantKey);
    if (version == null) {
      throw new IllegalArgumentException("Unsupported full-Version participant owner");
    }
    if (attestationSchemaVersion == CLOSURE_SELECTOR_SCHEMA_VERSION
        && "WORLD_MANAGEMENT".equals(participantKey)) {
      return 4;
    }
    return (attestationSchemaVersion == SELECTOR_SCHEMA_VERSION
                || attestationSchemaVersion == CLOSURE_SELECTOR_SCHEMA_VERSION)
            && "GAME_DESIGN_CONTROL_PLANE".equals(participantKey)
        ? 2
        : version;
  }

  public static boolean requiresWorldStartLocationEvidence(int attestationSchemaVersion) {
    return attestationSchemaVersion == SELECTOR_SCHEMA_VERSION
        || attestationSchemaVersion == CLOSURE_SELECTOR_SCHEMA_VERSION;
  }

  /** One successful, immutable owner digest included in the release attestation. */
  public record Participant(
      String participantKey,
      String scopeValue,
      boolean baseVersionIdPresent,
      Long baseVersionId,
      String appliedCommitId,
      String contentDigest,
      int digestSchemaVersion,
      boolean abilitySchemaDigestPresent,
      String abilitySchemaDigest) {
    public Participant {
      requireText(participantKey, "participantKey");
      requireText(scopeValue, "scopeValue");
      requireOptional(baseVersionIdPresent, baseVersionId, "baseVersionId");
      if (baseVersionIdPresent) {
        requirePositive(baseVersionId, "baseVersionId");
      }
      requireText(appliedCommitId, "appliedCommitId");
      requireParticipantContentDigest(contentDigest);
      requirePositiveSchema(digestSchemaVersion, "digestSchemaVersion");
      requireOptional(abilitySchemaDigestPresent, abilitySchemaDigest, "abilitySchemaDigest");
      if (abilitySchemaDigestPresent) {
        requireDigest(abilitySchemaDigest, "abilitySchemaDigest");
      }
    }
  }

  /** One content-addressed artifact included in the immutable release manifest. */
  public record Artifact(
      String usageKey,
      String artifactKind,
      String immutableObjectKey,
      String contentDigest,
      String contentType,
      int artifactSchemaVersion) {
    public Artifact {
      requireText(usageKey, "usageKey");
      requireText(artifactKind, "artifactKind");
      requireText(immutableObjectKey, "immutableObjectKey");
      requireDigest(contentDigest, "contentDigest");
      requireText(contentType, "contentType");
      requireSupportedSchema(artifactSchemaVersion, "artifactSchemaVersion");
      String digestHex = contentDigest.substring("sha256:".length());
      String expectedObjectKey = "artifacts/sha256/" + digestHex;
      if (!expectedObjectKey.equals(immutableObjectKey)) {
        throw new IllegalArgumentException(
            "immutableObjectKey must be the content-addressed key for contentDigest");
      }
    }
  }

  public AuthoredWorldReleaseAttestationEvidence {
    if (schemaVersion != SCHEMA_VERSION
        && schemaVersion != SELECTOR_SCHEMA_VERSION
        && schemaVersion != CLOSURE_SELECTOR_SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported authored-world release-attestation schema");
    }
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("targetNamespace must be a canonical DNS label");
    }
    requireDigest(descriptorResultDigest, "descriptorResultDigest");
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    requireNonNil(canonicalVersionId, "canonicalVersionId");
    AuthoredWorldSourceDigest.validateReadSelector(targetNamespace, canonicalTenantId, worldSlug);
    requireNonNil(authoredWorldSourceOperationId, "authoredWorldSourceOperationId");
    requireDigest(authoredWorldSourceEvidenceDigest, "authoredWorldSourceEvidenceDigest");
    requireText(launchDescriptorId, "launchDescriptorId");
    requireText(publishedReleaseBundleRef, "publishedReleaseBundleRef");
    if (versionStateEpoch <= 0) {
      throw new IllegalArgumentException("versionStateEpoch must be positive");
    }
    requireText(publishWorkflowId, "publishWorkflowId");
    requireText(commitId, "commitId");
    participantDigests =
        List.copyOf(Objects.requireNonNull(participantDigests, "participantDigests"));
    validateParticipants(participantDigests, commitId, schemaVersion);
    requireDigest(manifestHash, "manifestHash");
    requireSupportedSchema(manifestSchemaVersion, "manifestSchemaVersion");
    requiredManifestAssetKeys =
        List.copyOf(Objects.requireNonNull(requiredManifestAssetKeys, "requiredManifestAssetKeys"));
    validateOrderedUniqueStrings(requiredManifestAssetKeys, "requiredManifestAssetKeys");
    artifactDigests = List.copyOf(Objects.requireNonNull(artifactDigests, "artifactDigests"));
    validateArtifacts(artifactDigests, requiredManifestAssetKeys);
    commandDefinitions =
        List.copyOf(Objects.requireNonNull(commandDefinitions, "commandDefinitions"));
    for (String commandDefinition : commandDefinitions) {
      strictUtf8(Objects.requireNonNull(commandDefinition, "commandDefinition"));
    }
    requireText(generationConfigRevision, "generationConfigRevision");
    requireDigest(evidenceDigest, "evidenceDigest");
    if (requiresWorldStartLocationEvidence(schemaVersion) != (worldStartLocationEvidence != null)) {
      throw new IllegalArgumentException(
          "Selected release-attestation schemas require World selector evidence");
    }
    if (worldStartLocationEvidence != null) {
      WorldPublishedStartLocationEvidence.fromStored(worldStartLocationEvidence.canonicalBytes());
      var selected = worldStartLocationEvidence.request();
      var world = participantDigests.getFirst();
      if (!targetNamespace.equals(selected.targetNamespace())
          || !canonicalTenantId.equals(selected.canonicalTenantId())
          || !canonicalVersionId.equals(selected.canonicalVersionId())
          || !publishWorkflowId.equals(selected.publishWorkflowId())
          || !commitId.equals(selected.appliedCommitId())
          || !world.contentDigest().equals(selected.contentDigest())
          || world.digestSchemaVersion() != selected.digestSchemaVersion()) {
        throw new IllegalArgumentException(
            "World selector differs from the exact attested release checkpoint");
      }
    }
  }

  /** Original v1 constructor: retained bytes never gain a selector. */
  public AuthoredWorldReleaseAttestationEvidence(
      int schemaVersion,
      String targetNamespace,
      String descriptorResultDigest,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      String worldSlug,
      UUID authoredWorldSourceOperationId,
      String authoredWorldSourceEvidenceDigest,
      String launchDescriptorId,
      String publishedReleaseBundleRef,
      long versionStateEpoch,
      String publishWorkflowId,
      String commitId,
      List<Participant> participantDigests,
      String manifestHash,
      int manifestSchemaVersion,
      List<String> requiredManifestAssetKeys,
      List<Artifact> artifactDigests,
      List<String> commandDefinitions,
      String generationConfigRevision,
      String evidenceDigest) {
    this(
        schemaVersion,
        targetNamespace,
        descriptorResultDigest,
        canonicalTenantId,
        canonicalVersionId,
        worldSlug,
        authoredWorldSourceOperationId,
        authoredWorldSourceEvidenceDigest,
        launchDescriptorId,
        publishedReleaseBundleRef,
        versionStateEpoch,
        publishWorkflowId,
        commitId,
        participantDigests,
        manifestHash,
        manifestSchemaVersion,
        requiredManifestAssetKeys,
        artifactDigests,
        commandDefinitions,
        generationConfigRevision,
        evidenceDigest,
        null);
  }

  /** Creates v2 from the exact unchanged World evidence retained in the immutable release. */
  public static AuthoredWorldReleaseAttestationEvidence create(
      String targetNamespace,
      String descriptorResultDigest,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      String worldSlug,
      UUID authoredWorldSourceOperationId,
      String authoredWorldSourceEvidenceDigest,
      String launchDescriptorId,
      String publishedReleaseBundleRef,
      long versionStateEpoch,
      String publishWorkflowId,
      String commitId,
      List<Participant> participantDigests,
      String manifestHash,
      int manifestSchemaVersion,
      List<String> requiredManifestAssetKeys,
      List<Artifact> artifactDigests,
      List<String> commandDefinitions,
      String generationConfigRevision,
      WorldPublishedStartLocationEvidence worldStartLocationEvidence) {
    return createSelected(
        SELECTOR_SCHEMA_VERSION,
        targetNamespace,
        descriptorResultDigest,
        canonicalTenantId,
        canonicalVersionId,
        worldSlug,
        authoredWorldSourceOperationId,
        authoredWorldSourceEvidenceDigest,
        launchDescriptorId,
        publishedReleaseBundleRef,
        versionStateEpoch,
        publishWorkflowId,
        commitId,
        participantDigests,
        manifestHash,
        manifestSchemaVersion,
        requiredManifestAssetKeys,
        artifactDigests,
        commandDefinitions,
        generationConfigRevision,
        worldStartLocationEvidence);
  }

  /** Creates v3 for a selected World closure-qualified schema-4 participant digest. */
  public static AuthoredWorldReleaseAttestationEvidence createClosureSelector(
      String targetNamespace,
      String descriptorResultDigest,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      String worldSlug,
      UUID authoredWorldSourceOperationId,
      String authoredWorldSourceEvidenceDigest,
      String launchDescriptorId,
      String publishedReleaseBundleRef,
      long versionStateEpoch,
      String publishWorkflowId,
      String commitId,
      List<Participant> participantDigests,
      String manifestHash,
      int manifestSchemaVersion,
      List<String> requiredManifestAssetKeys,
      List<Artifact> artifactDigests,
      List<String> commandDefinitions,
      String generationConfigRevision,
      WorldPublishedStartLocationEvidence worldStartLocationEvidence) {
    return createSelected(
        CLOSURE_SELECTOR_SCHEMA_VERSION,
        targetNamespace,
        descriptorResultDigest,
        canonicalTenantId,
        canonicalVersionId,
        worldSlug,
        authoredWorldSourceOperationId,
        authoredWorldSourceEvidenceDigest,
        launchDescriptorId,
        publishedReleaseBundleRef,
        versionStateEpoch,
        publishWorkflowId,
        commitId,
        participantDigests,
        manifestHash,
        manifestSchemaVersion,
        requiredManifestAssetKeys,
        artifactDigests,
        commandDefinitions,
        generationConfigRevision,
        worldStartLocationEvidence);
  }

  private static AuthoredWorldReleaseAttestationEvidence createSelected(
      int schemaVersion,
      String targetNamespace,
      String descriptorResultDigest,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      String worldSlug,
      UUID authoredWorldSourceOperationId,
      String authoredWorldSourceEvidenceDigest,
      String launchDescriptorId,
      String publishedReleaseBundleRef,
      long versionStateEpoch,
      String publishWorkflowId,
      String commitId,
      List<Participant> participantDigests,
      String manifestHash,
      int manifestSchemaVersion,
      List<String> requiredManifestAssetKeys,
      List<Artifact> artifactDigests,
      List<String> commandDefinitions,
      String generationConfigRevision,
      WorldPublishedStartLocationEvidence worldStartLocationEvidence) {
    Objects.requireNonNull(worldStartLocationEvidence, "worldStartLocationEvidence");
    var provisional =
        new AuthoredWorldReleaseAttestationEvidence(
            schemaVersion,
            targetNamespace,
            descriptorResultDigest,
            canonicalTenantId,
            canonicalVersionId,
            worldSlug,
            authoredWorldSourceOperationId,
            authoredWorldSourceEvidenceDigest,
            launchDescriptorId,
            publishedReleaseBundleRef,
            versionStateEpoch,
            publishWorkflowId,
            commitId,
            participantDigests,
            manifestHash,
            manifestSchemaVersion,
            requiredManifestAssetKeys,
            artifactDigests,
            commandDefinitions,
            generationConfigRevision,
            "sha256:" + "0".repeat(64),
            worldStartLocationEvidence);
    return new AuthoredWorldReleaseAttestationEvidence(
        schemaVersion,
        targetNamespace,
        descriptorResultDigest,
        canonicalTenantId,
        canonicalVersionId,
        worldSlug,
        authoredWorldSourceOperationId,
        authoredWorldSourceEvidenceDigest,
        launchDescriptorId,
        publishedReleaseBundleRef,
        versionStateEpoch,
        publishWorkflowId,
        commitId,
        participantDigests,
        manifestHash,
        manifestSchemaVersion,
        requiredManifestAssetKeys,
        artifactDigests,
        commandDefinitions,
        generationConfigRevision,
        computeEvidenceDigest(provisional),
        worldStartLocationEvidence);
  }

  /** Creates evidence from the complete owner attestation and derives its evidence digest. */
  public static AuthoredWorldReleaseAttestationEvidence create(
      String targetNamespace,
      String descriptorResultDigest,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      String worldSlug,
      UUID authoredWorldSourceOperationId,
      String authoredWorldSourceEvidenceDigest,
      String launchDescriptorId,
      String publishedReleaseBundleRef,
      long versionStateEpoch,
      String publishWorkflowId,
      String commitId,
      List<Participant> participantDigests,
      String manifestHash,
      int manifestSchemaVersion,
      List<String> requiredManifestAssetKeys,
      List<Artifact> artifactDigests,
      List<String> commandDefinitions,
      String generationConfigRevision) {
    AuthoredWorldReleaseAttestationEvidence provisional =
        new AuthoredWorldReleaseAttestationEvidence(
            SCHEMA_VERSION,
            targetNamespace,
            descriptorResultDigest,
            canonicalTenantId,
            canonicalVersionId,
            worldSlug,
            authoredWorldSourceOperationId,
            authoredWorldSourceEvidenceDigest,
            launchDescriptorId,
            publishedReleaseBundleRef,
            versionStateEpoch,
            publishWorkflowId,
            commitId,
            participantDigests,
            manifestHash,
            manifestSchemaVersion,
            requiredManifestAssetKeys,
            artifactDigests,
            commandDefinitions,
            generationConfigRevision,
            "sha256:" + "0".repeat(64));
    AuthoredWorldReleaseAttestationEvidence evidence =
        new AuthoredWorldReleaseAttestationEvidence(
            provisional.schemaVersion(),
            provisional.targetNamespace(),
            provisional.descriptorResultDigest(),
            provisional.canonicalTenantId(),
            provisional.canonicalVersionId(),
            provisional.worldSlug(),
            provisional.authoredWorldSourceOperationId(),
            provisional.authoredWorldSourceEvidenceDigest(),
            provisional.launchDescriptorId(),
            provisional.publishedReleaseBundleRef(),
            provisional.versionStateEpoch(),
            provisional.publishWorkflowId(),
            provisional.commitId(),
            provisional.participantDigests(),
            provisional.manifestHash(),
            provisional.manifestSchemaVersion(),
            provisional.requiredManifestAssetKeys(),
            provisional.artifactDigests(),
            provisional.commandDefinitions(),
            provisional.generationConfigRevision(),
            computeEvidenceDigest(provisional));
    evidence.requireValid();
    return evidence;
  }

  /** Recomputes the closed evidence digest after construction or deserialization. */
  public void requireValid() {
    if (!computeEvidenceDigest(this).equals(evidenceDigest)) {
      throw new IllegalArgumentException(
          "Evidence digest does not match the exact release attestation");
    }
  }

  /** Recomputes both closed digests and binds every applicable descriptor field exactly. */
  public void requireValid(AuthoredWorldLaunchDescriptorEvidence descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    requireValid();
    descriptor.requireValid();
    if (!targetNamespace.equals(descriptor.targetNamespace())
        || !descriptorResultDigest.equals(descriptor.resultDigest())
        || !canonicalTenantId.equals(descriptor.canonicalTenantId())
        || !worldSlug.equals(descriptor.worldSlug())
        || !authoredWorldSourceOperationId.equals(descriptor.authoredWorldSourceOperationId())
        || !authoredWorldSourceEvidenceDigest.equals(descriptor.authoredWorldSourceEvidenceDigest())
        || !launchDescriptorId.equals(descriptor.launchDescriptorId())
        || !publishedReleaseBundleRef.equals(descriptor.publishedReleaseBundleRef())
        || versionStateEpoch != descriptor.versionStateEpoch()
        || !generationConfigRevision.equals(descriptor.generationConfigRevision())) {
      throw new IllegalArgumentException(
          "Release attestation does not match the exact launch descriptor binding");
    }
    String descriptorVersionScope = Long.toString(descriptor.versionId());
    for (Participant participant : participantDigests) {
      if (!descriptorVersionScope.equals(participant.scopeValue())) {
        throw new IllegalArgumentException(
            "Participant scope does not match the launch descriptor Version");
      }
    }
  }

  /** Computes the digest using the schema-specific domain and exhaustive field order. */
  public static String computeEvidenceDigest(AuthoredWorldReleaseAttestationEvidence evidence) {
    Objects.requireNonNull(evidence, "evidence");
    return "sha256:" + HexFormat.of().formatHex(sha256(evidencePreimage(evidence)));
  }

  /** Returns the exact strict-UTF-8 preimage for cross-language codec proof. */
  public static byte[] evidencePreimage(AuthoredWorldReleaseAttestationEvidence evidence) {
    Objects.requireNonNull(evidence, "evidence");
    List<Field> fields = new ArrayList<>();
    fields.add(field("schemaVersion", Integer.toString(evidence.schemaVersion())));
    fields.add(field("targetNamespace", evidence.targetNamespace()));
    fields.add(field("descriptorResultDigest", evidence.descriptorResultDigest()));
    fields.add(field("canonicalTenantId", evidence.canonicalTenantId().toString()));
    fields.add(field("canonicalVersionId", evidence.canonicalVersionId().toString()));
    fields.add(field("worldSlug", evidence.worldSlug()));
    fields.add(
        field(
            "authoredWorldSourceOperationId",
            evidence.authoredWorldSourceOperationId().toString()));
    fields.add(
        field("authoredWorldSourceEvidenceDigest", evidence.authoredWorldSourceEvidenceDigest()));
    fields.add(field("launchDescriptorId", evidence.launchDescriptorId()));
    fields.add(field("publishedReleaseBundleRef", evidence.publishedReleaseBundleRef()));
    fields.add(field("versionStateEpoch", Long.toString(evidence.versionStateEpoch())));
    fields.add(field("publishWorkflowId", evidence.publishWorkflowId()));
    fields.add(field("commitId", evidence.commitId()));
    fields.add(
        field("participantDigests.size", Integer.toString(evidence.participantDigests().size())));
    for (int index = 0; index < evidence.participantDigests().size(); index++) {
      Participant participant = evidence.participantDigests().get(index);
      String prefix = "participantDigests[" + index + "].";
      fields.add(field(prefix + "participantKey", participant.participantKey()));
      fields.add(field(prefix + "scopeValue", participant.scopeValue()));
      fields.add(optionalPresence(prefix + "baseVersionId", participant.baseVersionIdPresent()));
      fields.add(
          optionalValue(
              prefix + "baseVersionId",
              participant.baseVersionIdPresent(),
              decimal(participant.baseVersionId())));
      fields.add(field(prefix + "appliedCommitId", participant.appliedCommitId()));
      fields.add(field(prefix + "contentDigest", participant.contentDigest()));
      fields.add(
          field(
              prefix + "digestSchemaVersion", Integer.toString(participant.digestSchemaVersion())));
      fields.add(
          optionalPresence(
              prefix + "abilitySchemaDigest", participant.abilitySchemaDigestPresent()));
      fields.add(
          optionalValue(
              prefix + "abilitySchemaDigest",
              participant.abilitySchemaDigestPresent(),
              participant.abilitySchemaDigest()));
    }
    fields.add(field("manifestHash", evidence.manifestHash()));
    fields.add(field("manifestSchemaVersion", Integer.toString(evidence.manifestSchemaVersion())));
    fields.add(
        field(
            "requiredManifestAssetKeys.size",
            Integer.toString(evidence.requiredManifestAssetKeys().size())));
    for (int index = 0; index < evidence.requiredManifestAssetKeys().size(); index++) {
      fields.add(
          field(
              "requiredManifestAssetKeys[" + index + "]",
              evidence.requiredManifestAssetKeys().get(index)));
    }
    fields.add(field("artifactDigests.size", Integer.toString(evidence.artifactDigests().size())));
    for (int index = 0; index < evidence.artifactDigests().size(); index++) {
      Artifact artifact = evidence.artifactDigests().get(index);
      String prefix = "artifactDigests[" + index + "].";
      fields.add(field(prefix + "usageKey", artifact.usageKey()));
      fields.add(field(prefix + "artifactKind", artifact.artifactKind()));
      fields.add(field(prefix + "immutableObjectKey", artifact.immutableObjectKey()));
      fields.add(field(prefix + "contentDigest", artifact.contentDigest()));
      fields.add(field(prefix + "contentType", artifact.contentType()));
      fields.add(
          field(
              prefix + "artifactSchemaVersion",
              Integer.toString(artifact.artifactSchemaVersion())));
    }
    fields.add(
        field("commandDefinitions.size", Integer.toString(evidence.commandDefinitions().size())));
    for (int index = 0; index < evidence.commandDefinitions().size(); index++) {
      fields.add(
          field("commandDefinitions[" + index + "]", evidence.commandDefinitions().get(index)));
    }
    fields.add(field("generationConfigRevision", evidence.generationConfigRevision()));
    if (requiresWorldStartLocationEvidence(evidence.schemaVersion())) {
      fields.add(
          field(
              "worldStartLocationEvidence.canonicalBytesBase64",
              Base64.getEncoder()
                  .encodeToString(evidence.worldStartLocationEvidence().canonicalBytes())));
    }
    String domain =
        switch (evidence.schemaVersion()) {
          case SCHEMA_VERSION -> EVIDENCE_DOMAIN;
          case SELECTOR_SCHEMA_VERSION -> SELECTOR_EVIDENCE_DOMAIN;
          case CLOSURE_SELECTOR_SCHEMA_VERSION -> CLOSURE_SELECTOR_EVIDENCE_DOMAIN;
          default -> throw new IllegalArgumentException("Unsupported release-attestation schema");
        };
    return preimage(domain, fields.toArray(Field[]::new));
  }

  private static void validateParticipants(
      List<Participant> participants, String commitId, int attestationSchemaVersion) {
    if (participants.size() != PARTICIPANT_ORDER.size()) {
      throw new IllegalArgumentException(
          "A complete attestation requires exactly five participants");
    }
    for (int index = 0; index < PARTICIPANT_ORDER.size(); index++) {
      Participant participant =
          Objects.requireNonNull(participants.get(index), "participantDigest");
      String expectedKey = PARTICIPANT_ORDER.get(index);
      if (!expectedKey.equals(participant.participantKey())) {
        throw new IllegalArgumentException("Participant digests are not in canonical owner order");
      }
      if (supportedParticipantDigestSchema(participant.participantKey(), attestationSchemaVersion)
          != participant.digestSchemaVersion()) {
        throw new IllegalArgumentException(
            "Participant digest schema is unsupported for its owner");
      }
      if (participant.baseVersionIdPresent()) {
        throw new IllegalArgumentException(
            "Full-Version participants must not have a baseVersionId");
      }
      if (participant.scopeValue() == null
          || !CANONICAL_POSITIVE_DECIMAL.matcher(participant.scopeValue()).matches()) {
        throw new IllegalArgumentException(
            "Full-Version participant scope must be a positive decimal Version");
      }
      try {
        if (Long.parseLong(participant.scopeValue()) <= 0) {
          throw new IllegalArgumentException("Participant scope must be positive");
        }
      } catch (NumberFormatException exception) {
        throw new IllegalArgumentException(
            "Participant scope must fit a positive Version id", exception);
      }
      if (!commitId.equals(participant.appliedCommitId())) {
        throw new IllegalArgumentException("Every participant must report the attested commitId");
      }
      boolean requiresAbilityDigest = "GAME_LOGIC".equals(participant.participantKey());
      if (participant.abilitySchemaDigestPresent() != requiresAbilityDigest) {
        throw new IllegalArgumentException(
            "Only GAME_LOGIC may supply the dedicated ability-schema digest");
      }
    }
  }

  private static void validateOrderedUniqueStrings(List<String> values, String name) {
    String previous = null;
    for (String value : values) {
      Objects.requireNonNull(value, name + " entry");
      requireText(value, name + " entry");
      if (previous != null && UNSIGNED_UTF8_ORDER.compare(previous, value) >= 0) {
        throw new IllegalArgumentException(name + " must be unique and unsigned-UTF-8 ordered");
      }
      previous = value;
    }
  }

  private static void validateArtifacts(List<Artifact> artifacts, List<String> requiredKeys) {
    String previous = null;
    Map<String, Artifact> byUsageKey = new java.util.HashMap<>();
    for (Artifact artifact : artifacts) {
      Objects.requireNonNull(artifact, "artifactDigest");
      if (previous != null && UNSIGNED_UTF8_ORDER.compare(previous, artifact.usageKey()) >= 0) {
        throw new IllegalArgumentException(
            "artifactDigests must be unique and unsigned-UTF-8 ordered");
      }
      previous = artifact.usageKey();
      byUsageKey.put(artifact.usageKey(), artifact);
    }
    for (String requiredKey : requiredKeys) {
      if (!byUsageKey.containsKey(requiredKey)) {
        throw new IllegalArgumentException("Every required manifest key must select an artifact");
      }
    }
  }

  private static Field optionalPresence(String name, boolean present) {
    return field(name + ".present", Boolean.toString(present));
  }

  private static Field optionalValue(String name, boolean present, String value) {
    return field(name + ".value", present ? Objects.requireNonNull(value, name) : "");
  }

  private static Field field(String name, String value) {
    return new Field(name, value);
  }

  private static byte[] preimage(String domain, Field... fields) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    writeSegment(bytes, domain);
    for (Field field : fields) {
      writeSegment(bytes, field.name());
      writeSegment(bytes, field.value());
    }
    return bytes.toByteArray();
  }

  private static void writeSegment(ByteArrayOutputStream output, String value) {
    byte[] bytes = strictUtf8(value);
    output.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
    output.write(':');
    output.writeBytes(bytes);
  }

  private static byte[] strictUtf8(String value) {
    Objects.requireNonNull(value, "value");
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      byte[] result = new byte[encoded.remaining()];
      encoded.get(result);
      return result;
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException("Digest inputs must contain valid Unicode", exception);
    }
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static int compareUnsignedUtf8(String left, String right) {
    byte[] leftBytes = strictUtf8(left);
    byte[] rightBytes = strictUtf8(right);
    int commonLength = Math.min(leftBytes.length, rightBytes.length);
    for (int index = 0; index < commonLength; index++) {
      int difference = Byte.toUnsignedInt(leftBytes[index]) - Byte.toUnsignedInt(rightBytes[index]);
      if (difference != 0) {
        return difference;
      }
    }
    return Integer.compare(leftBytes.length, rightBytes.length);
  }

  private static String decimal(Long value) {
    return value == null ? "" : Long.toString(value);
  }

  private static void requireOptional(boolean present, Object value, String name) {
    if (present != (value != null)) {
      throw new IllegalArgumentException(name + " presence does not match its value");
    }
    if (value instanceof String text) {
      strictUtf8(text);
    }
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(name + " must not be nil");
    }
  }

  private static void requirePositive(Long value, String name) {
    if (value == null || value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requireSupportedSchema(int value, String name) {
    if (value != SCHEMA_VERSION) {
      throw new IllegalArgumentException(name + " is unsupported");
    }
  }

  private static void requirePositiveSchema(int value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    strictUtf8(value);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }

  private static void requireDigest(String value, String name) {
    Objects.requireNonNull(value, name);
    if (!SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(name + " must be a lowercase SHA-256 digest");
    }
  }

  private static void requireParticipantContentDigest(String value) {
    Objects.requireNonNull(value, "contentDigest");
    if (!PARTICIPANT_CONTENT_DIGEST.matcher(value).matches()) {
      throw new IllegalArgumentException(
          "Participant contentDigest must be 64 lowercase hexadecimal characters");
    }
  }
}
