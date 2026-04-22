package net.firedevops.firemud.accountservice.dto;

public record RealmAccessGrantResult(
    long accountId,
    long tenantId,
    String worldSlug,
    String realmSlug,
    boolean granted,
    long grantVersion,
    String evaluatedAt) {}
