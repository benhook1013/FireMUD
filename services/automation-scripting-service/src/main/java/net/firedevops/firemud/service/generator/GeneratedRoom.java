package net.firedevops.firemud.service.generator;

/** DTO representing a procedurally generated room with metadata for editor overlays. */
public record GeneratedRoom(
    long roomId,
    long connectedTo,
    int x,
    int y,
    java.util.Map<String, Long> exitMap,
    java.util.List<String> tags,
    String biome,
    int elevation,
    Long regionId) {}
