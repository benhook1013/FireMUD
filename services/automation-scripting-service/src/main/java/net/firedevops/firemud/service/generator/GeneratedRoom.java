package net.firedevops.firemud.service.generator;

import lombok.Data;

/** Simple DTO representing a procedurally generated room. */
@Data
public class GeneratedRoom {
  private final long id;
  private final long connectedTo;
}
