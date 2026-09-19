package net.firedevops.firemud.tcpproxy.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class GameplayTelnetDriverTest {
  @Test
  void enterGameplayAndWaitReadyFailsWhenLookContentNeverArrives() throws Exception {
    try (ServerSocket server = new ServerSocket(0)) {
      Thread serverThread = new Thread(() -> serveLoginAndPlayOnly(server));
      serverThread.start();

      try (GameplayTelnetDriver driver =
          GameplayTelnetDriver.connect("localhost", server.getLocalPort(), Duration.ofSeconds(1))) {
        AssertionError failure =
            assertThrows(
                AssertionError.class,
                () ->
                    driver.enterGameplayAndWaitReady(
                        "player@example.com", "secret", "demo", "LOOK READY"));

        assertEquals("Expected block containing 'LOOK READY', got:\n", failure.getMessage());
        assertEquals(
            List.of("Logged in as player@example.com", "", "OK PLAY Entered world: demo"),
            driver.responses());
      } finally {
        server.close();
        serverThread.join(5_000);
      }
    }
  }

  @Test
  void readBlockContainingFailsWhenStreamClosesAfterMatch() throws Exception {
    try (ServerSocket server = new ServerSocket(0)) {
      Thread serverThread =
          new Thread(
              () -> {
                try (Socket socket = server.accept();
                    PrintWriter writer =
                        new PrintWriter(
                            new OutputStreamWriter(
                                socket.getOutputStream(), StandardCharsets.ISO_8859_1),
                            true)) {
                  writer.println("LOOK READY");
                } catch (IOException ignored) {
                  // The client may close the socket while the test is cleaning up.
                }
              });
      serverThread.start();

      try (GameplayTelnetDriver driver =
          GameplayTelnetDriver.connect("localhost", server.getLocalPort(), Duration.ofSeconds(1))) {
        AssertionError failure =
            assertThrows(AssertionError.class, () -> driver.readBlockContaining("LOOK READY"));

        assertEquals(
            "Expected block containing 'LOOK READY', got:\nLOOK READY\n", failure.getMessage());
      } finally {
        server.close();
        serverThread.join(5_000);
      }
    }
  }

  @Test
  void readBlockContainingFailsWhenTimeoutOccursAfterMatch() throws Exception {
    try (ServerSocket server = new ServerSocket(0)) {
      Thread serverThread =
          new Thread(
              () -> {
                try (Socket socket = server.accept();
                    PrintWriter writer =
                        new PrintWriter(
                            new OutputStreamWriter(
                                socket.getOutputStream(), StandardCharsets.ISO_8859_1),
                            true)) {
                  writer.println("LOOK READY");
                  Thread.sleep(2_000);
                } catch (IOException | InterruptedException ignored) {
                  // The client may close the socket while the test is cleaning up.
                }
              });
      serverThread.start();

      try (GameplayTelnetDriver driver =
          GameplayTelnetDriver.connect(
              "localhost", server.getLocalPort(), Duration.ofMillis(250))) {
        AssertionError failure =
            assertThrows(AssertionError.class, () -> driver.readBlockContaining("LOOK READY"));

        assertEquals(
            "Expected block containing 'LOOK READY', got:\nLOOK READY\n", failure.getMessage());
      } finally {
        server.close();
        serverThread.join(5_000);
      }
    }
  }

  private static void serveLoginAndPlayOnly(ServerSocket server) {
    try (Socket socket = server.accept();
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
        PrintWriter writer =
            new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1),
                true)) {
      if (!"LOGIN player@example.com secret".equals(reader.readLine())) {
        return;
      }
      writer.println("Logged in as player@example.com");
      writer.println();
      if (!"PLAY demo".equals(reader.readLine())) {
        return;
      }
      writer.println("OK PLAY Entered world: demo");
      reader.readLine();
    } catch (IOException ignored) {
      // The client may close the socket while the test is cleaning up.
    }
  }
}
