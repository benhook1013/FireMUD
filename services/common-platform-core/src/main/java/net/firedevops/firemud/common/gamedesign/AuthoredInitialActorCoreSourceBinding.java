package net.firedevops.firemud.common.gamedesign;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Disposition;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Family;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Feedback;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.publication.AuthoredInitialActorCore;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;

/** Exact source-only projection for an authored initial actor core in a selected release. */
public final class AuthoredInitialActorCoreSourceBinding {
  public static final String SCHEMA = "game-design-authored-initial-actor-core-source-binding/v1";

  private final UUID worldSourceOperationId;
  private final String worldSourceEvidenceDigest;
  private final String expectedWorldSlug;
  private final String publicationWorkflowId;
  private final DraftCommitBinding selectedCommit;
  private final RealmPolicySource.Policy policySource;
  private final AuthoredInitialActorCore core;
  private final GameplayRuleManifest gameplayManifest;
  private final Disposition disposition;
  private final Feedback safeFeedback;
  private final byte[] canonicalBytes;
  private final String digest;

  private AuthoredInitialActorCoreSourceBinding(
      UUID worldSourceOperationId,
      String worldSourceEvidenceDigest,
      String expectedWorldSlug,
      String publicationWorkflowId,
      byte[] publicationOperationBytes,
      byte[] publicationTerminalEvidenceBytes,
      DraftCommitBinding selectedCommit,
      byte[] policySnapshotBytes,
      RealmPolicySource.Policy policySource,
      byte[] gameplaySnapshotBytes,
      GameplayRuleManifest gameplayManifest,
      GameplayRuleSource.Entry dispositionEntry,
      Disposition disposition,
      GameplayRuleSource.Entry feedbackEntry,
      Feedback safeFeedback) {
    this.worldSourceOperationId = Objects.requireNonNull(worldSourceOperationId);
    this.worldSourceEvidenceDigest = Objects.requireNonNull(worldSourceEvidenceDigest);
    this.expectedWorldSlug = Objects.requireNonNull(expectedWorldSlug);
    this.publicationWorkflowId = Objects.requireNonNull(publicationWorkflowId);
    this.selectedCommit = Objects.requireNonNull(selectedCommit);
    this.policySource = Objects.requireNonNull(policySource);
    this.core = Objects.requireNonNull(policySource.policy().initialActorCore());
    this.gameplayManifest = Objects.requireNonNull(gameplayManifest);
    this.disposition = Objects.requireNonNull(disposition);
    this.safeFeedback = Objects.requireNonNull(safeFeedback);

    var target = selectedCommit.target();
    var policy = policySource.policy();
    var descriptor = Objects.requireNonNull(policy.creationDescriptor());
    if (new UUID(0L, 0L).equals(worldSourceOperationId)
        || !worldSourceEvidenceDigest.matches("sha256:[0-9a-f]{64}")
        || !RealmEntryPolicy.isCanonicalSlug(expectedWorldSlug)
        || publicationWorkflowId.isBlank()
        || publicationWorkflowId.length() > 256
        || policy.schemaVersion() != RealmEntryPolicy.AUTHORED_INITIAL_ACTOR_CORE_SCHEMA_VERSION
        || !policy.worldSlug().equals(expectedWorldSlug)
        || policySource.policy().initialActorCore() == null
        || !core.dispositionKey().equals(disposition.dispositionKey())
        || !disposition.safeFeedbackKey().equals(safeFeedback.feedbackKey())) {
      throw new IllegalArgumentException("Invalid authored initial actor core source binding");
    }
    requireBytes(publicationOperationBytes, "selected publication operation");
    requireBytes(publicationTerminalEvidenceBytes, "selected publication terminal evidence");
    requireBytes(policySnapshotBytes, "selected policy source snapshot");
    requireBytes(gameplaySnapshotBytes, "selected GameplayRuleSource snapshot");

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(output, SCHEMA);
    DraftAuthorizationFenceBinding.frame(output, "1");
    DraftAuthorizationFenceBinding.frame(output, worldSourceOperationId.toString());
    DraftAuthorizationFenceBinding.frame(output, worldSourceEvidenceDigest);
    DraftAuthorizationFenceBinding.frame(output, expectedWorldSlug);
    DraftAuthorizationFenceBinding.frame(output, publicationWorkflowId);
    DraftAuthorizationFenceBinding.frame(output, target.canonicalTenantId().toString());
    DraftAuthorizationFenceBinding.frame(output, target.canonicalVersionId().toString());
    DraftAuthorizationFenceBinding.frame(output, Long.toString(target.gameDesignVersionRowId()));
    DraftAuthorizationFenceBinding.frame(output, target.gameDesignVersionTenantKey());
    DraftAuthorizationFenceBinding.frame(output, Long.toString(target.sourceGameRowId()));
    DraftAuthorizationFenceBinding.frame(output, target.sourceGameTenantKey());
    DraftAuthorizationFenceBinding.frame(output, target.sourceProvenanceKind());
    DraftAuthorizationFenceBinding.frame(output, selectedCommit.commitId().toString());
    DraftAuthorizationFenceBinding.frame(output, selectedCommit.digest());
    DraftAuthorizationFenceBinding.frame(output, selectedCommit.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(output, publicationOperationBytes);
    DraftAuthorizationFenceBinding.frame(output, publicationTerminalEvidenceBytes);
    DraftAuthorizationFenceBinding.frame(output, policySnapshotBytes);
    DraftAuthorizationFenceBinding.frame(output, policySource.commitId().toString());
    DraftAuthorizationFenceBinding.frame(output, policySource.revisionId().toString());
    DraftAuthorizationFenceBinding.frame(output, policySource.logicalRevisionId());
    DraftAuthorizationFenceBinding.frame(
        output, policy.canonicalJson().getBytes(StandardCharsets.UTF_8));
    DraftAuthorizationFenceBinding.frame(output, descriptor.descriptorId().toString());
    DraftAuthorizationFenceBinding.frame(output, descriptor.descriptorRevisionId().toString());
    DraftAuthorizationFenceBinding.frame(output, descriptor.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(output, core.canonicalJson());
    DraftAuthorizationFenceBinding.frame(output, gameplaySnapshotBytes);
    DraftAuthorizationFenceBinding.frame(output, gameplayManifest.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(output, disposition.dispositionKey());
    DraftAuthorizationFenceBinding.frame(
        output, GameplayRuleManifest.canonical(disposition).getBytes(StandardCharsets.UTF_8));
    frameEntry(output, dispositionEntry);
    DraftAuthorizationFenceBinding.frame(output, safeFeedback.feedbackKey());
    DraftAuthorizationFenceBinding.frame(
        output, GameplayRuleManifest.canonical(safeFeedback).getBytes(StandardCharsets.UTF_8));
    frameEntry(output, feedbackEntry);
    canonicalBytes = output.toByteArray();
    digest = CommandSource.sha256(canonicalBytes);
  }

  /**
   * Resolves schema-3 policies for the exact associated world from the already selected owner
   * snapshots. Other worlds remain in the framed source snapshot but are not this association's
   * actor core. The caller remains responsible for authenticated owner reads and exact release
   * selection before calling this.
   */
  public static List<AuthoredInitialActorCoreSourceBinding> resolveAll(
      UUID worldSourceOperationId,
      String worldSourceEvidenceDigest,
      String expectedWorldSlug,
      String publicationWorkflowId,
      byte[] publicationOperationBytes,
      byte[] publicationTerminalEvidenceBytes,
      DraftCommitBinding selectedCommit,
      net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot policySnapshot,
      GameplayRuleSelectedSource gameplaySnapshot) {
    Objects.requireNonNull(selectedCommit, "selectedCommit");
    Objects.requireNonNull(policySnapshot, "policySnapshot");
    Objects.requireNonNull(gameplaySnapshot, "gameplaySnapshot");
    if (!RealmEntryPolicy.isCanonicalSlug(expectedWorldSlug)) {
      throw new IllegalArgumentException("Exact selected world scope is required");
    }
    if (!selectedCommit.equals(policySnapshot.binding())) {
      throw new IllegalArgumentException("Policy source is not from the selected authored commit");
    }
    requireBytes(publicationOperationBytes, "selected publication operation");
    requireBytes(publicationTerminalEvidenceBytes, "selected publication terminal evidence");
    byte[] policySnapshotBytes = policySnapshot.canonicalBytes();
    if (!Arrays.equals(
        net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot.fromStored(
                new String(policySnapshotBytes, StandardCharsets.UTF_8))
            .canonicalBytes(),
        policySnapshotBytes)) {
      throw new IllegalArgumentException("Selected policy source snapshot bytes changed");
    }

    List<RealmPolicySource.Policy> authoredActorPolicies =
        policySnapshot.policies().stream()
            .filter(value -> value.policy().worldSlug().equals(expectedWorldSlug))
            .filter(
                value ->
                    value.policy().schemaVersion()
                        == RealmEntryPolicy.AUTHORED_INITIAL_ACTOR_CORE_SCHEMA_VERSION)
            .toList();
    if (authoredActorPolicies.isEmpty()) {
      return List.of();
    }

    if (!selectedCommit.equals(gameplaySnapshot.binding())) {
      throw new IllegalArgumentException(
          "GameplayRuleSource snapshot differs from the selected authored commit");
    }
    byte[] gameplaySnapshotBytes = gameplaySnapshot.canonicalBytes();
    List<GameplayRuleSource.Entry> gameplayEntries = gameplaySnapshot.entries();
    GameplayRuleManifest manifest = gameplaySnapshot.manifest();

    List<AuthoredInitialActorCoreSourceBinding> result =
        authoredActorPolicies.stream()
            .map(
                policy -> {
                  AuthoredInitialActorCore core = policy.policy().initialActorCore();
                  List<GameplayRuleSource.Entry> dispositions =
                      gameplayEntries.stream()
                          .filter(value -> value.family() == Family.DISPOSITIONS)
                          .filter(value -> value.definition().key().equals(core.dispositionKey()))
                          .toList();
                  if (dispositions.size() != 1
                      || !(dispositions.getFirst().definition()
                          instanceof Disposition disposition)) {
                    throw new IllegalArgumentException(
                        "Selected authored disposition is absent or ambiguous");
                  }
                  List<GameplayRuleSource.Entry> feedbackEntries =
                      gameplayEntries.stream()
                          .filter(value -> value.family() == Family.FEEDBACK)
                          .filter(
                              value ->
                                  value.definition().key().equals(disposition.safeFeedbackKey()))
                          .toList();
                  if (feedbackEntries.size() != 1
                      || !(feedbackEntries.getFirst().definition() instanceof Feedback feedback)) {
                    throw new IllegalArgumentException(
                        "Disposition safeFeedbackKey is absent or ambiguous");
                  }
                  return new AuthoredInitialActorCoreSourceBinding(
                      worldSourceOperationId,
                      worldSourceEvidenceDigest,
                      expectedWorldSlug,
                      publicationWorkflowId,
                      publicationOperationBytes,
                      publicationTerminalEvidenceBytes,
                      selectedCommit,
                      policySnapshotBytes,
                      policy,
                      gameplaySnapshotBytes,
                      manifest,
                      dispositions.getFirst(),
                      disposition,
                      feedbackEntries.getFirst(),
                      feedback);
                })
            .toList();
    return List.copyOf(result);
  }

  /**
   * Decodes and independently reconstructs the exact source frame. This validates structure and
   * digest consistency only; it does not authenticate the producer or grant runtime authority.
   */
  public static AuthoredInitialActorCoreSourceBinding fromStored(
      byte[] originalCanonicalBytes, String expectedDigest) {
    byte[] stored = requireBytes(originalCanonicalBytes, "authored actor core binding").clone();
    if (expectedDigest == null
        || !expectedDigest.matches("sha256:[0-9a-f]{64}")
        || !CommandSource.sha256(stored).equals(expectedDigest)) {
      throw new IllegalArgumentException("Authored actor core binding digest differs");
    }

    FrameReader reader = new FrameReader(stored);
    reader.expect(SCHEMA);
    reader.expect("1");
    UUID worldSourceOperationId = reader.uuid();
    String worldSourceEvidenceDigest = reader.text();
    String expectedWorldSlug = reader.text();
    String publicationWorkflowId = reader.text();

    String tenantId = reader.text();
    String versionId = reader.text();
    String versionRowId = reader.text();
    String versionTenantKey = reader.text();
    String sourceGameRowId = reader.text();
    String sourceGameTenantKey = reader.text();
    String sourceProvenanceKind = reader.text();
    UUID selectedCommitId = reader.uuid();
    String selectedCommitDigest = reader.text();
    byte[] selectedCommitBytes = reader.bytes();
    DraftCommitBinding selectedCommit;
    try {
      selectedCommit =
          DraftCommitBinding.fromStored(strictUtf8(selectedCommitBytes), selectedCommitDigest);
    } catch (IllegalStateException corrupt) {
      throw new IllegalArgumentException("Selected commit source is corrupt", corrupt);
    }
    var target = selectedCommit.target();
    requireFrameEquals(target.canonicalTenantId().toString(), tenantId, "tenant");
    requireFrameEquals(target.canonicalVersionId().toString(), versionId, "version");
    requireFrameEquals(
        Long.toString(target.gameDesignVersionRowId()), versionRowId, "Game Design version row");
    requireFrameEquals(target.gameDesignVersionTenantKey(), versionTenantKey, "version tenant key");
    requireFrameEquals(Long.toString(target.sourceGameRowId()), sourceGameRowId, "source game row");
    requireFrameEquals(target.sourceGameTenantKey(), sourceGameTenantKey, "source game tenant key");
    requireFrameEquals(target.sourceProvenanceKind(), sourceProvenanceKind, "source provenance");
    if (!selectedCommit.commitId().equals(selectedCommitId)
        || !Arrays.equals(selectedCommit.canonicalBytes(), selectedCommitBytes)) {
      throw new IllegalArgumentException("Selected commit frame differs from its canonical source");
    }

    byte[] publicationOperationBytes = reader.bytes();
    byte[] publicationTerminalEvidenceBytes = reader.bytes();
    byte[] policySnapshotBytes = reader.bytes();
    net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot policySnapshot;
    try {
      policySnapshot =
          net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot.fromStored(
              strictUtf8(policySnapshotBytes));
    } catch (IllegalStateException corrupt) {
      throw new IllegalArgumentException("Policy source snapshot is corrupt", corrupt);
    }
    if (!selectedCommit.equals(policySnapshot.binding())
        || !Arrays.equals(policySnapshot.canonicalBytes(), policySnapshotBytes)) {
      throw new IllegalArgumentException("Policy snapshot differs from the selected commit");
    }

    UUID policyCommitId = reader.uuid();
    UUID policyRevisionId = reader.uuid();
    String policyLogicalRevisionId = reader.text();
    byte[] policyBytes = reader.bytes();
    String policyJson = strictUtf8(policyBytes);
    List<RealmPolicySource.Policy> matchingPolicies =
        policySnapshot.policies().stream()
            .filter(value -> value.commitId().equals(policyCommitId))
            .filter(value -> value.revisionId().equals(policyRevisionId))
            .filter(value -> value.logicalRevisionId().equals(policyLogicalRevisionId))
            .filter(value -> value.policy().canonicalJson().equals(policyJson))
            .toList();
    if (matchingPolicies.size() != 1) {
      throw new IllegalArgumentException("Policy source is absent or ambiguous in its snapshot");
    }
    RealmPolicySource.Policy policySource = matchingPolicies.getFirst();
    var policy = policySource.policy();
    if (policy.schemaVersion() != RealmEntryPolicy.AUTHORED_INITIAL_ACTOR_CORE_SCHEMA_VERSION
        || policy.entryPolicy() != RealmEntryPolicy.EntryPolicy.PLAYER_CREATED
        || policy.creationDescriptor() == null
        || policy.initialActorCore() == null) {
      throw new IllegalArgumentException("Schema-three authored actor core policy is required");
    }
    var descriptor = policy.creationDescriptor();
    requireFrameEquals(descriptor.descriptorId(), reader.uuid().toString(), "descriptor identity");
    requireFrameEquals(
        descriptor.descriptorRevisionId(), reader.uuid().toString(), "descriptor revision");
    byte[] descriptorBytes = reader.bytes();
    if (!Arrays.equals(descriptor.canonicalBytes(), descriptorBytes)) {
      throw new IllegalArgumentException("Descriptor bytes differ from the authored policy");
    }
    String coreJson = reader.text();
    AuthoredInitialActorCore core = Objects.requireNonNull(policy.initialActorCore());
    if (!core.canonicalJson().equals(coreJson)) {
      throw new IllegalArgumentException("Actor core differs from the authored policy");
    }

    byte[] gameplaySnapshotBytes = reader.bytes();
    GameplayRuleSelectedSource gameplaySnapshot;
    try {
      gameplaySnapshot = new GameplayRuleSelectedSource(strictUtf8(gameplaySnapshotBytes));
    } catch (IllegalStateException corrupt) {
      throw new IllegalArgumentException("Gameplay rule source snapshot is corrupt", corrupt);
    }
    if (!selectedCommit.equals(gameplaySnapshot.binding())
        || !Arrays.equals(gameplaySnapshot.canonicalBytes(), gameplaySnapshotBytes)) {
      throw new IllegalArgumentException("Gameplay rules differ from the selected commit");
    }
    byte[] manifestBytes = reader.bytes();
    if (!Arrays.equals(gameplaySnapshot.manifest().canonicalBytes(), manifestBytes)) {
      throw new IllegalArgumentException("Gameplay manifest differs from its selected source");
    }

    String dispositionKey = reader.text();
    byte[] dispositionBytes = reader.bytes();
    EntryFrame dispositionFrame = reader.entry();
    String feedbackKey = reader.text();
    byte[] feedbackBytes = reader.bytes();
    EntryFrame feedbackFrame = reader.entry();
    reader.requireEnd();

    List<GameplayRuleSource.Entry> entries = gameplaySnapshot.entries();
    List<GameplayRuleSource.Entry> dispositions =
        entries.stream()
            .filter(value -> value.family() == Family.DISPOSITIONS)
            .filter(value -> value.definition().key().equals(core.dispositionKey()))
            .toList();
    if (dispositions.size() != 1
        || !(dispositions.getFirst().definition() instanceof Disposition disposition)
        || !disposition.dispositionKey().equals(dispositionKey)
        || !Arrays.equals(
            GameplayRuleManifest.canonical(disposition).getBytes(StandardCharsets.UTF_8),
            dispositionBytes)) {
      throw new IllegalArgumentException("Selected disposition differs from its source entry");
    }
    GameplayRuleSource.Entry dispositionEntry = dispositions.getFirst();
    requireEntryFrame(dispositionFrame, dispositionEntry);

    List<GameplayRuleSource.Entry> feedbackEntries =
        entries.stream()
            .filter(value -> value.family() == Family.FEEDBACK)
            .filter(value -> value.definition().key().equals(disposition.safeFeedbackKey()))
            .toList();
    if (feedbackEntries.size() != 1
        || !(feedbackEntries.getFirst().definition() instanceof Feedback safeFeedback)
        || !safeFeedback.feedbackKey().equals(feedbackKey)
        || !Arrays.equals(
            GameplayRuleManifest.canonical(safeFeedback).getBytes(StandardCharsets.UTF_8),
            feedbackBytes)) {
      throw new IllegalArgumentException("Mandatory FEEDBACK differs from its source entry");
    }
    GameplayRuleSource.Entry feedbackEntry = feedbackEntries.getFirst();
    requireEntryFrame(feedbackFrame, feedbackEntry);

    AuthoredInitialActorCoreSourceBinding decoded =
        new AuthoredInitialActorCoreSourceBinding(
            worldSourceOperationId,
            worldSourceEvidenceDigest,
            expectedWorldSlug,
            publicationWorkflowId,
            publicationOperationBytes,
            publicationTerminalEvidenceBytes,
            selectedCommit,
            policySnapshotBytes,
            policySource,
            gameplaySnapshotBytes,
            gameplaySnapshot.manifest(),
            dispositionEntry,
            disposition,
            feedbackEntry,
            safeFeedback);
    if (!Arrays.equals(decoded.canonicalBytes, stored) || !decoded.digest.equals(expectedDigest)) {
      throw new IllegalArgumentException("Authored actor core binding bytes changed on decode");
    }
    return decoded;
  }

  public UUID worldSourceOperationId() {
    return worldSourceOperationId;
  }

  public String worldSourceEvidenceDigest() {
    return worldSourceEvidenceDigest;
  }

  public String expectedWorldSlug() {
    return expectedWorldSlug;
  }

  public String publicationWorkflowId() {
    return publicationWorkflowId;
  }

  public DraftCommitBinding selectedCommit() {
    return selectedCommit;
  }

  public RealmPolicySource.Policy policySource() {
    return policySource;
  }

  public AuthoredInitialActorCore core() {
    return core;
  }

  public GameplayRuleManifest gameplayManifest() {
    return gameplayManifest;
  }

  public Disposition disposition() {
    return disposition;
  }

  public Feedback safeFeedback() {
    return safeFeedback;
  }

  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  public String digest() {
    return digest;
  }

  private static void frameEntry(ByteArrayOutputStream output, GameplayRuleSource.Entry entry) {
    DraftAuthorizationFenceBinding.frame(output, entry.family().name());
    DraftAuthorizationFenceBinding.frame(
        output,
        GameplayRuleManifest.canonical(entry.definition()).getBytes(StandardCharsets.UTF_8));
    DraftAuthorizationFenceBinding.frame(output, entry.sourceBinding().digest());
    DraftAuthorizationFenceBinding.frame(output, entry.sourceBinding().canonicalBytes());
    DraftAuthorizationFenceBinding.frame(output, entry.revisionOrder());
    DraftAuthorizationFenceBinding.frame(output, entry.revisionId().toString());
  }

  private static void requireEntryFrame(EntryFrame frame, GameplayRuleSource.Entry entry) {
    if (!frame.family().equals(entry.family().name())
        || !Arrays.equals(
            frame.definitionBytes(),
            GameplayRuleManifest.canonical(entry.definition()).getBytes(StandardCharsets.UTF_8))
        || !frame.sourceBindingDigest().equals(entry.sourceBinding().digest())
        || !Arrays.equals(frame.sourceBindingBytes(), entry.sourceBinding().canonicalBytes())
        || !frame.revisionOrder().equals(entry.revisionOrder())
        || !frame.revisionId().equals(entry.revisionId().toString())) {
      throw new IllegalArgumentException("Selected rule entry provenance differs");
    }
  }

  private static void requireFrameEquals(String expected, String actual, String label) {
    if (!Objects.equals(expected, actual)) {
      throw new IllegalArgumentException("Authored actor core " + label + " frame differs");
    }
  }

  private static String strictUtf8(byte[] value) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(value))
          .toString();
    } catch (CharacterCodingException malformed) {
      throw new IllegalArgumentException(
          "Authored actor core source contains invalid UTF-8", malformed);
    }
  }

  private static byte[] requireBytes(byte[] value, String label) {
    if (value == null || value.length == 0) {
      throw new IllegalArgumentException("Exact " + label + " bytes are required");
    }
    return value;
  }

  private record EntryFrame(
      String family,
      byte[] definitionBytes,
      String sourceBindingDigest,
      byte[] sourceBindingBytes,
      String revisionOrder,
      String revisionId) {}

  private static final class FrameReader {
    private final byte[] stored;
    private int offset;

    private FrameReader(byte[] stored) {
      this.stored = stored;
    }

    private void expect(String expected) {
      if (!expected.equals(text())) {
        throw new IllegalArgumentException("Unsupported authored actor core source frame");
      }
    }

    private String text() {
      return strictUtf8(bytes());
    }

    private UUID uuid() {
      String value = text();
      UUID parsed;
      try {
        parsed = UUID.fromString(value);
      } catch (IllegalArgumentException invalid) {
        throw new IllegalArgumentException("Canonical UUID frame required", invalid);
      }
      if (!parsed.toString().equals(value) || parsed.equals(new UUID(0L, 0L))) {
        throw new IllegalArgumentException("Canonical non-nil UUID frame required");
      }
      return parsed;
    }

    private byte[] bytes() {
      if (stored.length - offset < Integer.BYTES) {
        throw new IllegalArgumentException("Incomplete authored actor core frame length");
      }
      int length =
          ByteBuffer.wrap(stored, offset, Integer.BYTES).order(ByteOrder.BIG_ENDIAN).getInt();
      offset += Integer.BYTES;
      if (length < 0 || length > stored.length - offset) {
        throw new IllegalArgumentException("Invalid authored actor core frame length");
      }
      byte[] value = Arrays.copyOfRange(stored, offset, offset + length);
      offset += length;
      return value;
    }

    private EntryFrame entry() {
      String family = text();
      Family.valueOf(family);
      return new EntryFrame(family, bytes(), text(), bytes(), text(), text());
    }

    private void requireEnd() {
      if (offset != stored.length) {
        throw new IllegalArgumentException("Trailing authored actor core source bytes");
      }
    }
  }
}
