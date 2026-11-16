package net.firedevops.firemud.accountjwtmaterializer;

/** One-shot process entry point. Output contains only a bounded public outcome token. */
public final class MaterializerMain {
  private MaterializerMain() {}

  public static void main(String[] args) {
    boolean observeOnly;
    if (args.length == 0) {
      observeOnly = false;
    } else if (args.length == 1 && "--observe-only".equals(args[0])) {
      observeOnly = true;
    } else {
      deny();
      return;
    }
    try {
      ProtectedBinding.Snapshot snapshot = ProtectedBinding.loadDefault();
      try (AccountClient account = new AccountClient(snapshot, ProtectedBinding.DEFAULT_PATH)) {
        KubernetesApi kubernetes = new KubernetesApi(snapshot, ProtectedBinding.DEFAULT_PATH);
        Materializer materializer = new Materializer(snapshot, account, kubernetes);
        Materializer.Outcome outcome =
            observeOnly ? materializer.observeSecretOnce() : materializer.reconcileOnce();
        System.out.println("{\"schemaVersion\":1,\"outcome\":\"" + outcome.publicName() + "\"}");
      }
    } catch (java.io.IOException | RuntimeException ignored) {
      deny();
    }
  }

  private static void deny() {
    System.out.println("{\"schemaVersion\":1,\"outcome\":\"denied\"}");
    System.exit(2);
  }
}
