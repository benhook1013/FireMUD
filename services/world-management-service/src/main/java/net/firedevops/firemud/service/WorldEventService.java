package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.WorldEventDto;

public interface WorldEventService {
  WorldEventDto scheduleEvent(WorldEventDto dto);

  void processDueEvents();
}
