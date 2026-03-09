package net.firedevops.firemud.worldmanagement.service;

import net.firedevops.firemud.worldmanagement.dto.WorldEventDto;

public interface WorldEventService {
  WorldEventDto scheduleEvent(WorldEventDto dto);

  void processDueEvents();
}
