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
validated by the gateway and executed by EmergentTerrain.

For a gateway started separately from the game, stop only that process with:

```bash
./scripts/stop-local-ai-gateway.sh
```
