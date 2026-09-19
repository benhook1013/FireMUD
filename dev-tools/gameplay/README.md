# Gameplay tools

`telnet-session.py` is a small standard-library client for a maintained FireMUD TCP/Telnet player connection. It uses authenticated TLS by default and keeps the socket open while a background receiver records incoming data, including unsolicited and late messages. The JSONL transcript is append-only and uses an integer `cursor` (also exposed as `seq`) so a later reader can resume without assuming that a command has one response.

The client requires a POSIX environment because it uses `fcntl` file locking and `select` on stdin. Linux and WSL are supported; native Windows Python is not supported.

## Connect and play

Use the intended TLS-enabled TCP Proxy/Telnet endpoint for the environment. The tool verifies the server certificate using the system trust store and verifies the hostname (the `--host` value is also used for TLS SNI by default):

```bash
python3 dev-tools/gameplay/telnet-session.py connect \
  --host telnet.example.invalid --port 32016 \
  --transcript /tmp/firemud-session.jsonl
```

Use `--ca-file /path/to/ca.pem` to replace the system trust roots with a custom CA bundle, or `--server-hostname name` to override the TLS SNI and hostname-check name. TCP connection establishment and the TLS handshake use the bounded `--connect-timeout` (10 seconds by default), independently of the short `--timeout` used to poll for received data. Both values must be positive finite numbers. For localhost-equivalent plaintext proof only, pass `--allow-insecure`; it accepts `localhost`, `localhost.`, or a loopback IP literal such as `127.0.0.1` or `::1`. Private-network addresses and arbitrary hostnames are rejected, and TLS remains required for every non-loopback endpoint.

Because raw TCP does not use TLS trust or hostname verification, `--allow-insecure` cannot be combined with `--ca-file` or `--server-hostname`; either combination is a command-line usage error that exits with status `2` before connecting.

Type ordinary player commands at the prompt. The current direct-text compatibility flow is optional `WORLDS`, `LOGIN <email> [secret]`, `PLAY <world> [realm] [character]`, and `LOOK`. `LOGIN <email>` requests a verified-email code; `LOGIN <email> <secret>` authenticates immediately, with the supplied secret treated as one opaque value. `LOGON` is accepted as the login spelling alias. The current runtime uses the abbreviated existing-member path and does not implement explicit `JOIN`, `REALMS`, or `CHARS` ceremony. The target sequence is `WORLDS` -> `LOGIN` -> `REALMS` -> conditional `JOIN` -> conditional `CHARS`/creation -> `PLAY`, as documented by the [TCP Proxy protocol contract](../../design/architecture/microservices/tcp-proxy-service/protocols.md). Incoming output is received independently, and each displayed event has its cursor in brackets. The first socket timeout in each quiet period, connection errors, Telnet negotiation, and disconnects are recorded as their own event types rather than being presented as gameplay output. Repeated receive polling timeouts are suppressed until more data is sent or received, keeping long-lived transcripts bounded while they are idle.

Meta-commands are local and are not sent to the game:

```text
:cursor                 print the latest transcript cursor
:read                   print the complete transcript
:read 17                print events after cursor 17
:close demo_complete    record a local close and close the socket
:quit                   record a local close and close the socket
```

If stdin ends, the client closes with reason `stdin_eof`. A remote EOF is recorded as `disconnect` with reason `remote_eof`. The explicit close command is preferred when preserving a clear end-of-session boundary.

## Reread evidence

The `read` mode is a separate invocation and prints JSON records followed by a `next_cursor=N` marker:

```bash
python3 dev-tools/gameplay/telnet-session.py read \
  --transcript /tmp/firemud-session.jsonl --after 17
```

Credentials are never printed or written: outbound `LOGIN`/`LOGON` commands and an exact echoed login line have the secret replaced with `[REDACTED]` before display and append. As with any interactive client, credentials necessarily exist transiently in process memory while being sent; this tool does not claim to protect that memory from a compromised host or debugger.

When an inbound line partially matches a held `LOGIN`/`LOGON` echo through credential bytes but later bytes disprove the exact echo, the client discards the held prefix and the remainder of that line through its boundary rather than risk exposing a credential fragment. The transcript records `redaction_suppressed` events with `phase` values `start` and `end`, and interactive output renders those boundaries so the operator can see that inbound display contained a deliberate safety gap.
