package net.firedevops.firemud.accountservice.dto;

/** Tenant-qualified world selector visible to a bootstrap-authenticated first-party client. */
public record BootstrapWorldDto(String tenantSlug, String worldSlug, String displayName) {}
