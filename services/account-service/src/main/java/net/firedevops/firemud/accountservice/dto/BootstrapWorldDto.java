package net.firedevops.firemud.accountservice.dto;

/** World visible to a bootstrap-authenticated first-party client. */
public record BootstrapWorldDto(String worldSlug, String displayName) {}
