import assert from "node:assert/strict";
import { once } from "node:events";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

const directory = path.dirname(fileURLToPath(import.meta.url));
const gateway = path.join(directory, "server.mjs");
const fixture = path.join(directory, "test-fixture-command.mjs");
const child = spawn(process.execPath, [gateway], {
  env: {
    ...process.env,
    LOCAL_AI_GATEWAY_HOST: "127.0.0.1",
    LOCAL_AI_GATEWAY_PORT: "0",
    LOCAL_AI_GATEWAY_COMMAND: process.execPath,
    LOCAL_AI_GATEWAY_COMMAND_ARGS_JSON: JSON.stringify([fixture]),
  },
  stdio: ["ignore", "pipe", "pipe"],
});

let output = "";
child.stdout.on("data", (chunk) => { output += chunk; });
child.stderr.on("data", (chunk) => { output += chunk; });
const deadline = Date.now() + 10_000;
let baseUrl;
while (!baseUrl && Date.now() < deadline) {
  const match = output.match(/listening on (http:\/\/127\.0\.0\.1:\d+)/);
  if (match) baseUrl = match[1];
  else await new Promise((resolve) => setTimeout(resolve, 25));
}

try {
  assert.ok(baseUrl, `gateway did not start:\n${output}`);
  const tools = [
    { type: "function", function: { name: "multiply", parameters: { type: "object" } } },
    { type: "function", function: { name: "add", parameters: { type: "object" } } },
  ];

  async function completion(messages) {
    const response = await fetch(`${baseUrl}/v1/chat/completions`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-EmergentTerrain-Request-Id": "gateway-contract" },
      body: JSON.stringify({ model: "local-ai", messages, tools }),
    });
    const body = await response.text();
    assert.equal(response.status, 200, body);
    return JSON.parse(body);
  }

  async function runLoop(marker) {
    const messages = [{ role: "system", content: "Use application tools for arithmetic." }, { role: "user", content: marker }];
    const rounds = [];
    for (let round = 0; round < 4; round++) {
      const response = await completion(messages);
      const choice = response.choices[0];
      rounds.push(choice);
      if (choice.finish_reason !== "tool_calls") return rounds;
      messages.push(choice.message);
      for (const call of choice.message.tool_calls) {
        const args = JSON.parse(call.function.arguments);
        const result = call.function.name === "multiply" ? String(args.a * args.b) : String(args.a + args.b);
        messages.push({ role: "tool", tool_call_id: call.id, content: result });
      }
    }
    throw new Error("gateway did not finish within the bounded test loop");
  }

  const multiRound = await runLoop("MULTI_ROUND");
  assert.equal(multiRound.length, 3);
  assert.equal(multiRound[0].message.tool_calls[0].function.name, "multiply");
  assert.equal(multiRound[1].message.tool_calls[0].function.name, "add");
  assert.equal(multiRound[2].message.content, "FINAL=142");

  const parallel = await runLoop("PARALLEL");
  assert.equal(parallel.length, 2);
  assert.equal(parallel[0].message.tool_calls.length, 2);
  assert.equal(parallel[1].message.content, "PARALLEL=42");

  const noTool = await completion([{ role: "user", content: "NO_TOOL" }]);
  assert.equal(noTool.choices[0].finish_reason, "stop");
  assert.equal(noTool.choices[0].message.content, "NO_TOOL_OK");
  console.log("local-ai-gateway multi-round contract: PASS");
} finally {
  child.kill("SIGTERM");
  await Promise.race([once(child, "exit"), new Promise((resolve) => setTimeout(resolve, 2_000))]);
}
