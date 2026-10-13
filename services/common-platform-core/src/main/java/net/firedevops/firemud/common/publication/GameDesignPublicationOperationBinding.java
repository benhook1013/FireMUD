package net.firedevops.firemud.common.publication;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;

/** Original closed publication input; byte integrity never authenticates its producer. */
public final class GameDesignPublicationOperationBinding {
  public static final String SCHEMA = "game-design-publication-operation/v2";
  public static final int MAX_OPERATION_BYTES = 4 * 1024 * 1024;

  private static final String HISTORICAL_SCHEMA = "game-design-publication-operation/v1";

  private enum Profile {
    HISTORICAL_V1,
    COMPLETE_V2
  }

  private final AccountPublicationAuthorizationBinding account;
  private final WorldPublishedStartLocationEvidence world;
  private final WorldSelectedPublicationArtifactInventoryEvidence inventory;
  private final Profile profile;

  public GameDesignPublicationOperationBinding(
      AccountPublicationAuthorizationBinding account,
      WorldPublishedStartLocationEvidence world,
      WorldSelectedPublicationArtifactInventoryEvidence inventory) {
    this(account, world, Objects.requireNonNull(inventory, "inventory"), Profile.COMPLETE_V2);
  }

  private GameDesignPublicationOperationBinding(
      AccountPublicationAuthorizationBinding account,
      WorldPublishedStartLocationEvidence world,
      WorldSelectedPublicationArtifactInventoryEvidence inventory,
      Profile profile) {
    account =
        AccountPublicationAuthorizationBinding.fromStored(
            Objects.requireNonNull(account).canonicalBytes());
    world =
        WorldPublishedStartLocationEvidence.fromStored(
            Objects.requireNonNull(world).canonicalBytes());
    requireAccountWorldCorrelation(account, world);
    this.account = account;
    this.world = world;
    this.profile = profile;
    if (profile == Profile.HISTORICAL_V1) {
      this.inventory = null;
      return;
    }
    Objects.requireNonNull(inventory, "inventory");
    inventory =
        WorldSelectedPublicationArtifactInventoryEvidence.fromRetainedSelection(
            account, world, inventory.canonicalBytes(), inventory.digest());
    this.inventory = inventory;
    long size =
        5L * Integer.BYTES
            + SCHEMA.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            + account.canonicalBytes().length
            + world.canonicalBytes().length
            + inventory.canonicalBytes().length
            + inventory.digest().length();
    if (size > MAX_OPERATION_BYTES)
      throw new IllegalArgumentException("Publication operation exceeds 4 MiB");
  }

  /** Historical correlation only; neither authentication nor complete operation authority. */
  public static void requireAccountWorldCorrelation(
      AccountPublicationAuthorizationBinding account, WorldPublishedStartLocationEvidence world) {
    var selection = account.input().selection();
    var intent = selection.intent();
    var freeze = world.request();
    if (!account.tenantId().equals(freeze.canonicalTenantId())
        || !intent.canonicalVersionId().equals(freeze.canonicalVersionId())
        || !intent.publishRequestId().equals(freeze.publicationRequestId())
        || !selection.digest().equals("sha256:" + freeze.requestDigest())
        || !intent.expectedVersionStateEpoch().equals(Long.toString(freeze.versionStateEpoch()))
        || !intent.selectedCommitId().toString().equals(freeze.appliedCommitId())
        || !Arrays.equals(
            selection.selectedCommit().canonicalBytes(),
            DraftAuthorizationFenceBinding.fromStored(world.originalAccountBindingBytes())
                .normalizedInput())) {
      throw new IllegalArgumentException("PUBLICATION_OPERATION_IDENTITY_CONFLICT");
    }
    if (!PublicationDigestRequestBinding.full(
            account.tenantId().toString(),
            Long.toString(selection.target().gameDesignVersionRowId()),
            intent.publishRequestId())
        .derivedWorkflowIdentity()
        .equals(freeze.publishWorkflowId())) {
      throw new IllegalArgumentException("PUBLICATION_OPERATION_WORKFLOW_CONFLICT");
    }
  }

  public AccountPublicationAuthorizationBinding account() {
    return account;
  }

  public WorldPublishedStartLocationEvidence world() {
    return world;
  }

  /** Retained v1 history cannot supply complete inventory or fresh publication authority. */
  public WorldSelectedPublicationArtifactInventoryEvidence inventory() {
    if (profile == Profile.HISTORICAL_V1)
      throw new IllegalStateException("HISTORICAL_PUBLICATION_INVENTORY_UNAVAILABLE");
    return inventory;
  }

  public byte[] canonicalBytes() {
    var out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(
        out, profile == Profile.HISTORICAL_V1 ? HISTORICAL_SCHEMA : SCHEMA);
    DraftAuthorizationFenceBinding.frame(out, account.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(out, world.canonicalBytes());
    if (profile == Profile.COMPLETE_V2) {
      DraftAuthorizationFenceBinding.frame(out, inventory.canonicalBytes());
      DraftAuthorizationFenceBinding.frame(out, inventory.digest());
    }
    return out.toByteArray();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof GameDesignPublicationOperationBinding binding
        && Arrays.equals(canonicalBytes(), binding.canonicalBytes());
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(canonicalBytes());
  }

  public static GameDesignPublicationOperationBinding fromStored(byte[] bytes) {
    if (bytes == null || bytes.length == 0)
      throw new IllegalArgumentException("Original publication operation required");
    var reader = new DraftAuthorizationFenceBinding.FrameReader(bytes);
    String schema = reader.text();
    boolean historical = HISTORICAL_SCHEMA.equals(schema);
    if (!historical && (!SCHEMA.equals(schema) || bytes.length > MAX_OPERATION_BYTES))
      throw new IllegalArgumentException("Complete bounded v2 publication operation required");
    var account = AccountPublicationAuthorizationBinding.fromStored(reader.bytes());
    var world = WorldPublishedStartLocationEvidence.fromStored(reader.bytes());
    GameDesignPublicationOperationBinding operation;
    if (historical) {
      operation =
          new GameDesignPublicationOperationBinding(account, world, null, Profile.HISTORICAL_V1);
    } else {
      var inventoryBytes = reader.bytes();
      String digest = new String(reader.bytes(), java.nio.charset.StandardCharsets.US_ASCII);
      operation =
          new GameDesignPublicationOperationBinding(
              account,
              world,
              WorldSelectedPublicationArtifactInventoryEvidence.fromRetainedSelection(
                  account, world, inventoryBytes, digest));
    }
    reader.requireEnd();
    if (!Arrays.equals(bytes, operation.canonicalBytes())) {
      throw new IllegalArgumentException("Noncanonical publication operation");
    }
    return operation;
  }
}
