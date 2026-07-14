package net.firedevops.firemud.common.settings;

/** Tenant/game-controlled availability groups for standard player commands. */
public enum PlayerCommandCapability {
  MANDATORY,
  SOCIAL,
  PRESENCE,
  INVENTORY,
  COMMAND_HISTORY
}
