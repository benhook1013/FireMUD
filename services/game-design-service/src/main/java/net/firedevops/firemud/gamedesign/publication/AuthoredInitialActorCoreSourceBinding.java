package net.firedevops.firemud.gamedesign.publication;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.common.gamedesign.GameplayRuleSource;
import net.firedevops.firemud.common.gamedesign.RealmPolicySource;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Disposition;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Family;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Feedback;
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
      GameplayRuleSnapshot gameplaySnapshot) {
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

    GameplayRuleSnapshot selectedGameplaySnapshot =
        GameplayRuleSnapshot.fromStored(gameplaySnapshot.canonicalJson());
    if (!selectedCommit.equals(selectedGameplaySnapshot.binding())
        || !Arrays.equals(
            selectedGameplaySnapshot.canonicalBytes(), gameplaySnapshot.canonicalBytes())) {
      throw new IllegalArgumentException(
          "GameplayRuleSource snapshot differs from the selected authored commit");
    }
    byte[] gameplaySnapshotBytes = selectedGameplaySnapshot.canonicalBytes();
    List<GameplayRuleSource.Entry> gameplayEntries = selectedGameplaySnapshot.entries();
    GameplayRuleManifest manifest = GameplayRuleSource.manifest(gameplayEntries);

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

  private static void requireBytes(byte[] value, String label) {
    if (value == null || value.length == 0) {
      throw new IllegalArgumentException("Exact " + label + " bytes are required");
    }
  }
}
