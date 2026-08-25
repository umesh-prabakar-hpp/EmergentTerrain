# Local local-ai provider

EmergentTerrain can use the local ChatGPT/Codex CLI login without storing an OpenAI API key. The
loopback-only `local-ai-gateway` is for one trusted developer machine; do not expose it
through a reverse proxy or firewall rule.

## Setup

Install Node.js and the Codex CLI, then sign in as the OS user that will run the application:

```bash
codex login
```

The proxy invokes one ephemeral `codex exec --json` turn per completion from `/tmp` in a read-only
sandbox. Application-owned professor tools are planned by Codex and executed by EmergentTerrain.
The proxy model is `gpt-5.6-terra`; `gpt-5-codex` is not accepted by this ChatGPT-authenticated
installation.

## Run and verify

```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
./scripts/start.sh
curl -fsS http://127.0.0.1:8788/healthz
curl -fsS http://127.0.0.1:8788/v1/models
tail -f logs/codex-proxy.log
```

The app uses the non-secret sentinel `local-chatgpt-codex-login`. Codex token usage is returned for
API-equivalent budget accounting. A failed login, unavailable CLI, proxy failure, or account limit
falls through to the configured OpenRouter and Groq providers.

To run without the local bridge for one launch:

```bash
ET_DISABLE_LOCAL_AI_GATEWAY=1 ./scripts/start.sh
```

Use `ET_RESTART_LOCAL_AI_GATEWAY=1` after changing proxy code or environment variables. `./scripts/stop.sh`
stops the Spring process and the local AI gateway.
