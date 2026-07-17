# Local Codex and legacy-ai providers

EmergentTerrain can use the subscriptions signed into the **local Codex** and
**Claude** CLIs without putting an OpenAI or Anthropic API key in the app. Both
adapters are loopback-only OpenAI-compatible HTTP services. They are intended
for one trusted developer machine, not for a shared or internet-facing host.

## Current default

The checked-in configuration uses Codex first. Its provider is enabled and
points to `http://127.0.0.1:8788/v1`; the Claude provider configuration is
preserved but has `enabled: false`. The normal route is therefore:

```text
EmergentTerrain -> local-ai-gateway :8788 -> local Codex login
                 -> OpenRouter -> Groq              (fallbacks)
```

`./scripts/start.sh` starts the local AI gateway before the Spring Boot app when
both `node` and `codex` are on `PATH`. Claude is intentionally not launched
unless explicitly enabled. The provider order and provider blocks live in
[`src/main/resources/application.yml`](../src/main/resources/application.yml).

## One-time local account setup

Install Node.js plus the Codex CLI and Claude CLI using their normal supported
installers. The account login belongs to the OS user that runs
`./scripts/start.sh`; logging in as a different user does not authenticate this
user's proxy process.

### Codex / ChatGPT subscription

```bash
codex login
```

Finish the browser/device-login flow using the ChatGPT account with the Codex
subscription. The Codex CLI keeps that login in its own user configuration;
EmergentTerrain neither reads nor stores an OpenAI API key.

The local bridge invokes one ephemeral `codex exec --json` turn per app
completion. It uses `/tmp` as its working directory, a read-only sandbox, and
disables Codex shell, browser, computer-use, app, image, and tool-suggestion
features. Application-owned professor tools are instead planned by Codex and
executed by EmergentTerrain.

The current proxy model is `gpt-5.6-terra`, validated by a local read-only
Codex probe on 2026-07-17. Its API-equivalent budget rate is $2.50 input / $15
output per million tokens, half the configured Sol rate it replaces. Do not
substitute `gpt-5-codex`: this ChatGPT-authenticated CLI installation rejects
that explicit selection. A different available local model can be selected
with `LOCAL_AI_GATEWAY_MODEL`.

### Claude Pro/Max subscription

Run `claude` once and complete its interactive login with the Claude Pro/Max
account. Install the local Agent SDK dependency once:

```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain/scripts/legacy-ai-gateway
npm install
node probe.mjs
```

The probe should return `SUBSCRIPTION_OK` and report
`apiKeySource: none` (subscription), not `ANTHROPIC_API_KEY`. The bridge
removes `ANTHROPIC_API_KEY` from every child process so a stray API key cannot
silently turn a subscription request into billed API usage.

For a headless machine, `claude setup-token` can mint an `sk-ant-oat...`
subscription token. Store it only in the existing local secrets mechanism,
then set `api-key-env: ANTHROPIC_OAUTH_TOKEN` on the Claude provider. The
bridge passes that bearer token to the Claude Agent SDK as
`CLAUDE_CODE_OAUTH_TOKEN`. Do not commit the token, `secrets.json`, or an
environment file containing it.

## Run with the default Codex provider

```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
./scripts/start.sh
```

Expected startup output includes the local AI gateway address. Verify it separately
from the application:

```bash
curl -fsS http://127.0.0.1:8788/healthz
curl -fsS http://127.0.0.1:8788/v1/models
tail -f logs/codex-proxy.log
```

The app sends its OpenAI-style requests to the local proxy using the sentinel
value `local-chatgpt-codex-login`; it is not a credential. The proxy sends
Codex usage back to the app so its API-equivalent run-budget accounting still
works. The Codex pool permits two concurrent requests; subscription account
limits still apply. A failed login, unavailable CLI, proxy failure, or account
limit makes routing fall through to the configured OpenRouter and then Groq
providers when available.

To run without the bridge for one launch, use:

```bash
ET_DISABLE_LOCAL_AI_GATEWAY=1 ./scripts/start.sh
```

That only prevents the local service from starting. It does not edit the
provider configuration; with Codex still first in the order, its requests fail
over to the next available provider.

## Enable and use Claude deliberately

Starting the Claude bridge alone does not make the app use Claude: its checked
in provider block is disabled. For a local rollback or comparison, make these
local `application.yml` changes:

```yaml
app:
  ai:
    tool-calling:
      # Optional: add Claude here only when Claude should receive professor-tool loops.
      providers: "legacy-ai,local-ai,openrouter,groq"
    primaryProviderOrder: "legacy-ai,local-ai,openrouter,groq"
    providers:
      legacy-ai:
        enabled: true
```

Omit `legacy-ai` from `tool-calling.providers` if it should serve
normal primary text only. The Claude shim does support the app's OpenAI
function-calling protocol through its tool bridge, but it is opt-in so an
operator can limit which providers receive those longer tool loops.

Restart the application and start the bridge in the same environment as the
Claude login:

```bash
./scripts/stop.sh
ET_ENABLE_LEGACY_AI_GATEWAY=1 ./scripts/start.sh
```

Then check the bridge and its log:

```bash
curl -fsS http://127.0.0.1:8787/healthz
curl -fsS http://127.0.0.1:8787/v1/models
tail -f logs/claude-proxy.log
```

The app supplies `local-subscription-login` as the default bearer sentinel;
the shim ignores it and uses the locally logged-in subscription. Claude calls
are also limited to two concurrent processes. The subscription is not
per-token API billed, but ET records API-equivalent usage/cost for its normal
run guard. A Claude failure falls through to Codex, OpenRouter, and Groq in the
order shown above.

## Lifecycle and troubleshooting

`scripts/start.sh` leaves an already-running proxy in place. Use
`ET_RESTART_LOCAL_AI_GATEWAY=1` or `ET_RESTART_CLAUDE_PROXY=1` when changing that
proxy's environment variables or code, after stopping the app. `./scripts/stop.sh`
stops the Spring process and both local proxies.

| Need | Codex | Claude |
| --- | --- | --- |
| Local address | `127.0.0.1:8788` | `127.0.0.1:8787` |
| Health endpoint | `/healthz` | `/healthz` |
| Main log | `logs/codex-proxy.log` | `logs/claude-proxy.log` |
| Disable auto-start | `ET_DISABLE_LOCAL_AI_GATEWAY=1` | `ET_DISABLE_CLAUDE_PROXY=1` |
| Start normally dormant proxy | n/a (default) | `ET_ENABLE_LEGACY_AI_GATEWAY=1` |
| Restart running proxy | `ET_RESTART_LOCAL_AI_GATEWAY=1` | `ET_RESTART_CLAUDE_PROXY=1` |

Useful failure checks are: verify `node`, `codex`, and (for Claude) `claude`
are on `PATH`; repeat the corresponding interactive login as the process
owner; run the Claude `probe.mjs`; and inspect the proxy log before changing
application routing. The proxies bind only to `127.0.0.1`; do not expose either
port through a reverse proxy or firewall rule.

For adapter-specific implementation details and timeout knobs, see
[`scripts/local-ai-gateway/README.md`](../scripts/local-ai-gateway/README.md)
and
[`scripts/legacy-ai-gateway/README.md`](../scripts/legacy-ai-gateway/README.md).
