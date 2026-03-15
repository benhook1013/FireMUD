package net.firedevops.firemud.automationscripting.model;

/** Represents the aggression state for an NPC. */
public enum AggressionState {
  HOSTILE,
  NEUTRAL,
  PASSIVE,
  /** NPC has low health or morale and is attempting to flee combat. */
  FLEEING,
  /** NPC has yielded and will no longer fight. */
  SURRENDERED
}
