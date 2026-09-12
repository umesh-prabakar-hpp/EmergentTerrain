# Local AI gateway

This optional loopback-only service exposes a compatible chat-completions endpoint for
EmergentTerrain. It is vendor-neutral: the operator supplies the local AI command.

## Configure and run

Set `LOCAL_AI_GATEWAY_COMMAND` to an executable that reads one complete prompt from standard input
and writes one complete assistant response to standard output. Set
`LOCAL_AI_GATEWAY_COMMAND_ARGS_JSON` to a JSON array when fixed command arguments are needed.

```bash
export LOCAL_AI_GATEWAY_COMMAND=/absolute/path/to/local-ai-adapter
export LOCAL_AI_GATEWAY_COMMAND_ARGS_JSON='["--safe"]'
./scripts/start.sh
```

The gateway binds to `127.0.0.1:8788` by default. `LOCAL_AI_GATEWAY_PORT`,
`LOCAL_AI_GATEWAY_HOST`, `LOCAL_AI_GATEWAY_MODEL`, and `LOCAL_AI_GATEWAY_TURN_TIMEOUT_MS` override
the defaults. It never exposes application tools to the configured command; tool selection is
validated by the gateway and executed by EmergentTerrain. Tool conversations are multi-round: after
EmergentTerrain returns a tool result, the command may request another supplied application tool or
return a final answer. The transcript includes earlier tool names, arguments, and results so a
stateless local command can select the next measurement without gaining tool authority.

Verify that protocol without credentials or a live model:

```bash
node scripts/local-ai-gateway/test-tool-loop.mjs
```

The test starts a temporary loopback gateway with a deterministic fixture and covers sequential
multi-round calls, parallel calls, and a no-tool completion. Gateway logs intentionally contain only
the request correlation ID, continuation state, message/tool counts, finish reason, elapsed time,
and a sanitized failure category; they never log prompts, tool output, command stderr, or secrets.

For a gateway started separately from the game, stop only that process with:

```bash
./scripts/stop-local-ai-gateway.sh
```
