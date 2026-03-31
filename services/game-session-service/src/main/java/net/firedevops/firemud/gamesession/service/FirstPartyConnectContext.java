package net.firedevops.firemud.gamesession.service;

public record FirstPartyConnectContext(
    long accountId,
    long tenantId,
    long gameInstanceId,
    String connectTokenJti,
    String gatewayRequestId) {}
