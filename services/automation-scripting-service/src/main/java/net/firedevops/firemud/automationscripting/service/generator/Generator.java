package net.firedevops.firemud.automationscripting.service.generator;

import java.util.List;

/** Generic interface for procedural generators. */
public interface Generator {
  /**
   * Generate content using the provided parameters.
   *
   * @param params input parameters, must include a seed
   * @return result containing generated rooms and metadata
   */
  GenerationResult generate(GenerationParams params);

  /**
   * Validate the supplied parameters.
   *
   * @param params generation parameters
   * @return list of error messages, empty if valid
   */
  List<String> validateParams(GenerationParams params);

  /**
   * @return unique generator name for registration
   */
  String getName();
}
