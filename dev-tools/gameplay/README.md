# Gameplay tools

`telnet-session.py` is a small standard-library client for a maintained
FireMUD TCP/Telnet player connection. It keeps the socket open while a
background receiver records incoming data, including unsolicited and late
messages. The JSONL transcript is append-only and uses an integer `cursor`
(also exposed as `seq`) so a later reader can resume without assuming that a
command has one response.

## Connect and play

Use the intended TCP Proxy/Telnet endpoint for the environment. The tool does
not create a listener or alter transport security:

```bash
python3 dev-tools/gameplay/telnet-session.py connect \
  --host dev.preview.firedevops.net --port 32016 \
  --transcript /tmp/firemud-session.jsonl
```

Type ordinary player commands at the prompt. The usual compatible flow is
`WORLDS`, `LOGIN <email> <password>`, `PLAY demo`, and `LOOK`; the current
Telnet smoke contract describes the environment-specific admission flow and
fixtures. Incoming output is received independently, and each displayed event
has its cursor in brackets. The first socket timeout in each quiet period,
connection errors, Telnet negotiation, and disconnects are recorded as their
own event types rather than being presented as gameplay output. Repeated
receive polling timeouts are suppressed until more data is sent or received,
keeping long-lived transcripts bounded while they are idle.

Meta-commands are local and are not sent to the game:

```text
:cursor                 print the latest transcript cursor
:read                   print the complete transcript
:read 17                print events after cursor 17
:close demo_complete    record a local close and close the socket
:quit                   record a local close and close the socket
```

If stdin ends, the client closes with reason `stdin_eof`. A remote EOF is
recorded as `disconnect` with reason `remote_eof`. The explicit close command
is preferred when preserving a clear end-of-session boundary.

## Reread evidence

The `read` mode is a separate invocation and prints JSON records followed by a
`next_cursor=N` marker:

```bash
python3 dev-tools/gameplay/telnet-session.py read \
  --transcript /tmp/firemud-session.jsonl --after 17
```

Credentials are never printed or written: outbound `LOGIN` commands and an
exact echoed `LOGIN` line have the password replaced with `[REDACTED]` before
display and append. As with any interactive client, credentials necessarily
exist transiently in process memory while being sent; this tool does not claim
to protect that memory from a compromised host or debugger.
