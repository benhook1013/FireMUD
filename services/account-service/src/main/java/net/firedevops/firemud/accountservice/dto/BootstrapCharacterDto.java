package net.firedevops.firemud.accountservice.dto;

/** Character visible to a bootstrap-authenticated first-party client. */
public record BootstrapCharacterDto(String characterId, String characterName, int level) {}
