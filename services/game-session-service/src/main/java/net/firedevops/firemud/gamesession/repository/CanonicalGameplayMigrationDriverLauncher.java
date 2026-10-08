package net.firedevops.firemud.gamesession.repository;

import java.io.PrintStream;

/** JDK-only entry point that reserves stdout for the finite driver's framed protocol. */
public final class CanonicalGameplayMigrationDriverLauncher {
  private CanonicalGameplayMigrationDriverLauncher() {}

  public static void main(String[] args) {
    PrintStream protocolOutput = preserveProtocolOutputAndRedirectDiagnostics();
    CanonicalGameplayMigrationDriverMain.launch(args, protocolOutput, System.err);
  }

  static PrintStream preserveProtocolOutputAndRedirectDiagnostics() {
    PrintStream protocolOutput = System.out;
    System.setOut(System.err);
    return protocolOutput;
  }
}
