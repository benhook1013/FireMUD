package net.firedevops.firemud.gamesession.service.impl;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import net.firedevops.firemud.gamesession.service.SessionContext;

final class SessionContextSerializationTestSupport {
  private SessionContextSerializationTestSupport() {}

  static String serialize(SessionContext context) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ObjectOutputStream objectOutput = new ObjectOutputStream(output)) {
      objectOutput.writeObject(context);
    }
    return output.toString(StandardCharsets.ISO_8859_1);
  }
}
