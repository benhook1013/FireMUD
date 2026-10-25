package net.firedevops.firemud.automationscripting.sourceintake;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.automationscripting.service.ScriptDesignDigestService.ScriptDraftDesignDigest;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerEmptySourceInputs;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.automation.sourceintake.AutomationEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.publication.SelectedEmptyPublicationDigestSupport;

/**
 * Unregistered selected-empty full-version digest reader over Automation's retained owner receipt.
 */
public final class AutomationSelectedEmptyPublicationDigestService {
  public static final int DIGEST_SCHEMA_VERSION = 6;
  private static final String DIGEST_DOMAIN = "automation-selected-empty-publication-digest/v1";

  private final String configuredNamespace;
  private final AutomationEmptySelectedSourceIntakeRepository repository;

  public AutomationSelectedEmptyPublicationDigestService(
      String configuredNamespace, AutomationEmptySelectedSourceIntakeRepository repository) {
    if (!GrpcPeerIdentity.isValidNamespace(configuredNamespace)) {
      throw new IllegalArgumentException("Configured Automation namespace is required");
    }
    this.configuredNamespace = configuredNamespace;
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  /** Returns a selected-empty digest only for one exact canonical tenant and GD Version row. */
  public ScriptDraftDesignDigest getDraftDesignDigest(
      PublicationDigestRequestBinding publicationRequest) {
    Objects.requireNonNull(publicationRequest, "publication request is required");
    if (publicationRequest.scopeKind() != PublicationDigestRequestBinding.ScopeKind.FULL_VERSION) {
      throw new IllegalArgumentException(
          "Automation selected-empty publication supports full versions only");
    }
    UUID canonicalTenantId =
        SelectedEmptyPublicationDigestSupport.canonicalUuid(
            publicationRequest.tenantId(), "tenantId");
    long gameDesignVersionRowId =
        SelectedEmptyPublicationDigestSupport.canonicalPositiveLong(
            publicationRequest.versionId(), "versionId");

    AutomationEmptySelectedSourceIntakeReceipt receipt =
        repository.readPublicationScope(
            configuredNamespace, canonicalTenantId, gameDesignVersionRowId);
    var target = receipt.authorizationBinding().selected().target();
    if (!configuredNamespace.equals(receipt.targetNamespace())
        || !canonicalTenantId.equals(receipt.canonicalTenantId())
        || !canonicalTenantId.equals(target.canonicalTenantId())
        || target.gameDesignVersionRowId() != gameDesignVersionRowId
        || !receipt.canonicalVersionId().equals(target.canonicalVersionId())) {
      throw new IllegalStateException(
          "Automation selected-empty receipt differs from publication scope");
    }
    SelectedEmptyPublicationDigestSupport.requireOriginalPublicationRequest(
        publicationRequest,
        canonicalTenantId,
        gameDesignVersionRowId,
        receipt.canonicalVersionId(),
        receipt.selectedCommitId(),
        receipt.worldInventoryReadEvidence().request().freezeEvidence(),
        "Automation");
    if (receipt.authorizationBinding().owner() != Owner.AUTOMATION_SCRIPTING) {
      throw new IllegalStateException("Automation receipt contains a different owner declaration");
    }
    requireEmptyReceipt(receipt);

    SelectedOwnerEmptySourceInputs inputs =
        new SelectedOwnerEmptySourceInputs(
            receipt.authorizationBinding(), receipt.worldInventoryReadEvidence());
    if (inputs.ownerSourceInventoryDeclaration().owner() != Owner.AUTOMATION_SCRIPTING) {
      throw new IllegalStateException(
          "Automation receipt has no exact owner inventory declaration");
    }
    String inventoryJson = inputs.ownerSourceInventoryDeclaration().inventory().canonicalJson();
    String contentDigest =
        contentDigest(
            canonicalTenantId, gameDesignVersionRowId, receipt.canonicalVersionId(), inventoryJson);
    return new ScriptDraftDesignDigest(
        publicationRequest.tenantId(),
        publicationRequest.versionId(),
        0L,
        receipt.selectedCommitId().toString(),
        contentDigest,
        DIGEST_SCHEMA_VERSION);
  }

  static String contentDigest(
      UUID canonicalTenantId,
      long gameDesignVersionRowId,
      UUID canonicalVersionId,
      String canonicalInventoryJson) {
    if (gameDesignVersionRowId <= 0L) {
      throw new IllegalArgumentException("Game Design Version row must be positive");
    }
    Objects.requireNonNull(canonicalTenantId, "canonicalTenantId");
    Objects.requireNonNull(canonicalVersionId, "canonicalVersionId");
    Objects.requireNonNull(canonicalInventoryJson, "canonicalInventoryJson");
    return SelectedEmptyPublicationDigestSupport.framedSha256(
        DIGEST_DOMAIN,
        DIGEST_SCHEMA_VERSION,
        canonicalTenantId.toString(),
        Long.toString(gameDesignVersionRowId),
        canonicalVersionId.toString(),
        canonicalInventoryJson);
  }

  private static void requireEmptyReceipt(AutomationEmptySelectedSourceIntakeReceipt receipt) {
    if (!"COMMITTED_EMPTY".equals(receipt.outcome())
        || receipt.scriptsRowCount() != 0L
        || receipt.eventBindingsRowCount() != 0L
        || receipt.patchBaseBindingsRowCount() != 0L
        || receipt.unqualifiedScriptsRowCount() != 0L
        || receipt.unqualifiedEventBindingsRowCount() != 0L
        || receipt.unqualifiedPatchBaseBindingsRowCount() != 0L
        || receipt.emptyAssociatedScriptsRowCount() != 0L
        || receipt.emptyAssociatedEventBindingsRowCount() != 0L
        || receipt.emptyAssociatedPatchBaseBindingsRowCount() != 0L
        || receipt.selectedScopeScriptsRowCount() != 0L
        || receipt.selectedScopeEventBindingsRowCount() != 0L
        || receipt.selectedScopePatchBaseBindingsRowCount() != 0L) {
      throw new IllegalStateException(
          "Automation selected-empty receipt is incomplete or nonempty");
    }
  }
}
