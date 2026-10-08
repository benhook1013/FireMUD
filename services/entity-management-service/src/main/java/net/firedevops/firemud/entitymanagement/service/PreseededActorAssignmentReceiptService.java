package net.firedevops.firemud.entitymanagement.service;

import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;

/** Exposes only repository-verified committed receipts for owner-resolved assigned actors. */
public final class PreseededActorAssignmentReceiptService {
  private final CharacterRepository characterRepository;

  public PreseededActorAssignmentReceiptService(CharacterRepository characterRepository) {
    this.characterRepository = Objects.requireNonNull(characterRepository, "characterRepository");
  }

  public Optional<PreseededActorAssignmentReceipt> read(
      PreseededActorAssignmentReceiptRequest request) {
    Objects.requireNonNull(request, "request");
    return characterRepository.readPreseededActorAssignmentReceipt(request);
  }
}
