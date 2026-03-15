package net.firedevops.firemud.gamelogic.logic.dto;

import net.firedevops.firemud.common.ErrorDetail;

/** Result of executing a command, optionally containing an error. */
public record CommandResult(String result, ErrorDetail error) {}
