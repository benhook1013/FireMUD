package net.firedevops.firemud.logic.command;

/** Parsed command structure. */
public record Command(ActionType actionType, String target, String raw) {}
