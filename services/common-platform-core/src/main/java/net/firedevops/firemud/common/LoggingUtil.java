package net.firedevops.firemud.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LoggingUtil {
  private LoggingUtil() {}

  public static Logger getLogger(Class<?> clazz) {
    return LoggerFactory.getLogger(clazz);
  }
}
