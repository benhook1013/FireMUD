package net.firedevops.firemud.gamesession.service;

/**
 * Selector and display metadata for the one visible public shared demo catalog per tenant.
 *
 * <p>This is an internal fixture-owner input, not an external catalog mutation API. The fixture
 * coordinator must call it only after resolving the exact real Game Design tenant/template.
 */
public record InitialAdmissionBindCatalogDescriptor(
    long tenantId,
    long gameTemplateId,
    String worldSlug,
    String worldDisplayName,
    String realmSlug,
    String realmDisplayName,
    boolean requiresCharacterSelection) {}
