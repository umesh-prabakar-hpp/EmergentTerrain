# Local AI gateway

EmergentTerrain can use a locally configured AI command without storing provider credentials in the
application. The loopback-only `local-ai-gateway` is for one trusted developer machine; do not
expose it through a reverse proxy or firewall rule.

## Setup

Install Node.js and configure an executable that reads one complete prompt from standard input and
writes one complete assistant response to standard output:

```bash
export LOCAL_AI_GATEWAY_COMMAND=/absolute/path/to/local-ai-adapter
export LOCAL_AI_GATEWAY_COMMAND_ARGS_JSON='["--safe"]'
```

The gateway is tracked at `scripts/local-ai-gateway`. Set `ET_LOCAL_AI_GATEWAY_DIR` to use another
location.

## Run and verify

```bash
cd "$(git rev-parse --show-toplevel)"
./scripts/start.sh
curl -fsS http://127.0.0.1:8788/healthz
curl -fsS http://127.0.0.1:8788/v1/models
tail -f .runtime/logs/local-ai-gateway.log
```

The app uses the non-secret sentinel `local-ai-gateway`. A missing command or gateway failure falls
through to the remaining configured providers.

To run without the local gateway for one launch:

```bash
ET_DISABLE_LOCAL_AI_GATEWAY=1 ./scripts/start.sh
```

Use `ET_RESTART_LOCAL_AI_GATEWAY=1` after changing gateway code or environment variables.
`./scripts/stop.sh` stops the Spring process and the local AI gateway.
