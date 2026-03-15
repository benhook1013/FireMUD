package net.firedevops.firemud.gamedesign.service;

import net.firedevops.firemud.gamedesign.dto.RevisionDto;

public interface RevisionService {
  RevisionDto saveRevision(RevisionDto dto);
}
