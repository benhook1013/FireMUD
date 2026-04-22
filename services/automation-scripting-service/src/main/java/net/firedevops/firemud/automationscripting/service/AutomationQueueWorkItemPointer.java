package net.firedevops.firemud.automationscripting.service;

public record AutomationQueueWorkItemPointer(
    int schemaVersion,
    long outboxWorkItemId,
    String gameInstanceId,
    String scriptPatchVersion,
    String scriptEventId) {}
