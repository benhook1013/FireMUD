package net.firedevops.firemud.gamesession.presentation;

/** Structured application-level command error prior to final protocol rendering. */
public record ErrorOutput(String code, String message) implements PlayerOutputPayload {}
