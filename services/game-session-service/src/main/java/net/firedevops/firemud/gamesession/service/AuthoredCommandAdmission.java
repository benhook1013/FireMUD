package net.firedevops.firemud.gamesession.service;

/** Immutable authored-command declaration snapshot captured before durable enqueue. */
public record AuthoredCommandAdmission(
    long releaseBundleId, long versionId, String commandId, String declaredEffectsJson) {}
