package net.firedevops.firemud.tcpproxy.testsupport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** FireMUD-specific telnet gameplay test driver for chained login/play/command flows. */
public final class GameplayTelnetDriver implements AutoCloseable {
  private static final Charset TELNET_CHARSET = Charset.forName("ISO_8859_1");

  private final Socket socket;
  private final PrintWriter writer;
  private final BufferedReader reader;
  private final Duration waitTimeout;

  private GameplayTelnetDriver(
      Socket socket, PrintWriter writer, BufferedReader reader, Duration waitTimeout) {
    this.socket = socket;
    this.writer = writer;
    this.reader = reader;
    this.waitTimeout = waitTimeout;
  }

  public static GameplayTelnetDriver connect(String host, int port, Duration waitTimeout)
      throws IOException {
    Socket socket = new Socket(host, port);
    socket.setSoTimeout((int) waitTimeout.toMillis());
    return new GameplayTelnetDriver(
        socket,
        new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), TELNET_CHARSET), true),
        new BufferedReader(new InputStreamReader(socket.getInputStream(), TELNET_CHARSET)),
        waitTimeout);
  }

  public void awaitInitialGuidance() throws IOException {
    expectExactLine("OK CONNECTED");
    expectExactLine("Type WORLDS to list available worlds.");
    expectExactLine("Type LOGIN <email> <password> to authenticate.");
    expectExactLine("Type PLAY <world> after LOGIN to enter a world.");
    expectExactLine("Type HELP for commands.");
  }

  public void login(String email, String password) throws IOException {
    sendLine("LOGIN " + email + " " + password);
    readBlockContaining("Logged in as " + email);
  }

  public void play(String world) throws IOException {
    sendLine("PLAY " + world);
    readLineContaining("OK PLAY Entered world: " + world);
  }

  public void play(String world, String characterName) throws IOException {
    sendLine("PLAY " + world + " " + characterName);
    readLineContaining("OK PLAY Entered world: " + world);
  }

  public void enterGameplayAndWaitReady(String email, String password, String world, String text)
      throws IOException {
    login(email, password);
    play(world);
    sendLine("LOOK");
    readBlockContainingOrTimeout(text);
  }

  public void enterGameplayAndWaitReady(
      String email, String password, String world, String characterName, String text)
      throws IOException {
    login(email, password);
    play(world, characterName);
    sendLine("LOOK");
    readBlockContainingOrTimeout(text);
  }

  public void sendLine(String command) {
    writer.println(command);
  }

  public String sendAndExpectExactLine(String command, String expectedLine) throws IOException {
    for (int attempt = 1; attempt <= 2; attempt++) {
      sendLine(command);
      StringBuilder received = new StringBuilder();
      while (true) {
        final String line;
        try {
          line = reader.readLine();
        } catch (java.net.SocketTimeoutException ex) {
          if (attempt == 1 && received.isEmpty()) {
            break;
          }
          throw ex;
        }
        if (line == null) {
          throw new AssertionError(
              "Expected line '"
                  + expectedLine
                  + "' for command '"
                  + command
                  + "' but stream closed after receiving:\n"
                  + received);
        }
        received.append(line).append("\n");
        if (expectedLine.equals(line)) {
          return line;
        }
      }
    }
    throw new AssertionError(
        "Expected line '"
            + expectedLine
            + "' for command '"
            + command
            + "' but no response was received");
  }

  public String readLineContaining(String expectedSubstring) throws IOException {
    long deadline = System.nanoTime() + waitTimeout.toNanos();
    while (System.nanoTime() < deadline) {
      String line = reader.readLine();
      if (line == null) {
        throw new AssertionError("Expected line containing '" + expectedSubstring + "'");
      }
      if (line.contains(expectedSubstring)) {
        return line;
      }
    }
    throw new AssertionError("Expected line containing '" + expectedSubstring + "'");
  }

  public String readBlockContaining(String expectedSubstring) throws IOException {
    return readBlockContaining(expectedSubstring, false);
  }

  public String readBlockMatching(Predicate<String> matcher, String description)
      throws IOException {
    long deadline = System.nanoTime() + waitTimeout.toNanos();
    StringBuilder block = new StringBuilder();
    while (System.nanoTime() < deadline) {
      String line = reader.readLine();
      if (line == null) {
        break;
      }
      block.append(line).append("\n");
      String current = block.toString().trim();
      if (matcher.test(current)) {
        return block.toString();
      }
    }
    throw new AssertionError("Expected block matching '" + description + "', got:\n" + block);
  }

  public String readBlockContainingOrTimeout(String expectedSubstring) throws IOException {
    return readBlockContaining(expectedSubstring, true);
  }

  public String readMultiLineResponse() throws IOException {
    List<String> lines = new ArrayList<>();
    String line;
    while ((line = reader.readLine()) != null) {
      lines.add(line);
      if (line.isEmpty()) {
        break;
      }
    }
    return String.join("\n", lines) + "\n";
  }

  private void expectExactLine(String expected) throws IOException {
    String line = reader.readLine();
    if (!expected.equals(line)) {
      throw new AssertionError("Expected line '" + expected + "' but got '" + line + "'");
    }
  }

  private String readBlockContaining(String expectedSubstring, boolean returnOnTimeout)
      throws IOException {
    long deadline = System.nanoTime() + waitTimeout.toNanos();
    StringBuilder block = new StringBuilder();
    boolean matched = false;
    while (System.nanoTime() < deadline) {
      final String line;
      try {
        line = reader.readLine();
      } catch (java.net.SocketTimeoutException ex) {
        if (returnOnTimeout) {
          return block.toString();
        }
        throw ex;
      }
      if (line == null) {
        break;
      }
      block.append(line).append("\n");
      if (!matched && line.contains(expectedSubstring)) {
        matched = true;
        continue;
      }
      if (matched && line.isEmpty()) {
        return block.toString();
      }
    }
    if (matched || returnOnTimeout) {
      return block.toString();
    }
    throw new AssertionError(
        "Expected block containing '" + expectedSubstring + "', got:\n" + block);
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }
}
