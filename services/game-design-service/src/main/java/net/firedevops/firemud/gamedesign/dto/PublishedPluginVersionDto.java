package net.firedevops.firemud.gamedesign.dto;

import java.time.LocalDateTime;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;

public record PublishedPluginVersionDto(
    Long id,
    String tenantId,
    String pluginId,
    String pluginVersionId,
    Long baseVersionId,
    VersionLifecycleState publicationState,
    String abilitySchemaDigest,
    String bundleDigest,
    Integer manifestSchemaVersion,
    String distributionManifestHash,
    String distributionManifestPath,
    String signerKeyId,
    boolean signerRevoked,
    String componentPolicyDecision,
    String notes,
    String statusReason,
    LocalDateTime lastChangedAt) {}
