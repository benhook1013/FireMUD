package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.RevisionDto;

public interface RevisionService {
  RevisionDto saveRevision(RevisionDto dto);
}
