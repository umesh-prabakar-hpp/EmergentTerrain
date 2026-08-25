import http from "node:http";
import { randomUUID } from "node:crypto";
import { spawn } from "node:child_process";

const PORT = Number.parseInt(process.env.LOCAL_AI_GATEWAY_PORT || "8788", 10);
const HOST = process.env.LOCAL_AI_GATEWAY_HOST || "127.0.0.1";
const COMMAND = process.env.LOCAL_AI_GATEWAY_COMMAND || "";
const MODEL = process.env.LOCAL_AI_GATEWAY_MODEL || "local-ai";
const TIMEOUT_MS = Number.parseInt(process.env.LOCAL_AI_GATEWAY_TURN_TIMEOUT_MS || "900000", 10);
const MAX_BODY_BYTES = 32 * 1024 * 1024;

function commandArgs() {
  const raw = process.env.LOCAL_AI_GATEWAY_COMMAND_ARGS_JSON || "[]";
  try {
    const value = JSON.parse(raw);
    if (!Array.isArray(value) || !value.every((part) => typeof part === "string")) throw new Error();
    return value;
  } catch {
    throw new Error("LOCAL_AI_GATEWAY_COMMAND_ARGS_JSON must be a JSON string array");
  }
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let bytes = 0;
    const chunks = [];
    req.on("data", (chunk) => {
      bytes += chunk.length;
      if (bytes > MAX_BODY_BYTES) {
        reject(new Error("request body too large"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

function sendJson(res, status, value) {
  const body = JSON.stringify(value);
  res.writeHead(status, { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(body) });
  res.end(body);
}

function sendError(res, status, message, type = "upstream_error") {
  sendJson(res, status, { error: { message, type, code: null, param: null } });
}

function textFromContent(content) {
  if (content == null) return "";
  if (typeof content === "string") return content;
  if (Array.isArray(content)) return content.map((part) => typeof part === "string" ? part : part?.text || "").join("");
  return String(content);
}

function renderConversation(messages) {
  return (messages || []).map((message) => {
    const role = message?.role || "user";
    const suffix = message.tool_call_id ? ` (tool_call_id=${message.tool_call_id})` : "";
    return `${role.toUpperCase()}${suffix}:\n${textFromContent(message.content)}`;
  }).join("\n\n");
}

function buildPrompt(messages, tools, forceFinal) {
  const toolInstructions = tools.length
    ? forceFinal
      ? "Tool results are present. Return only the final answer as JSON: {\"content\":\"...\"}."
      : "Select only supplied application tools. Return JSON: {\"tool_calls\":[{\"name\":\"...\",\"arguments\":{}}]} or {\"content\":\"...\"}."
    : "Return only the requested assistant response.";
  return [
    "You are the AI backend for EmergentTerrain.",
    toolInstructions,
    "Do not execute shell commands, access files, use network services, or invoke tools outside the supplied application tools.",
    tools.length ? `--- APPLICATION TOOLS ---\n${JSON.stringify(tools)}` : "",
    "--- APPLICATION CONVERSATION ---",
    renderConversation(messages),
    "--- END APPLICATION CONVERSATION ---",
  ].filter(Boolean).join("\n\n");
}

function extractJsonObject(text) {
  const start = text.indexOf("{");
  if (start < 0) return null;
  let depth = 0;
  let quoted = false;
  let escaped = false;
  for (let index = start; index < text.length; index++) {
    const character = text[index];
    if (quoted) {
      if (escaped) escaped = false;
      else if (character === "\\") escaped = true;
      else if (character === '"') quoted = false;
    } else if (character === '"') quoted = true;
    else if (character === "{") depth++;
    else if (character === "}" && --depth === 0) return text.slice(start, index + 1);
  }
  return null;
}

function parseToolPlan(text, tools) {
  const raw = extractJsonObject(text);
  if (!raw) return { content: text };
  let plan;
  try { plan = JSON.parse(raw); } catch { return { content: text }; }
  const names = new Set(tools.map((tool) => tool?.function?.name).filter(Boolean));
  const calls = Array.isArray(plan.tool_calls) ? plan.tool_calls : [];
  const validCalls = calls
    .filter((call) => call && names.has(call.name) && call.arguments && typeof call.arguments === "object" && !Array.isArray(call.arguments))
    .map((call) => ({ id: `call_${randomUUID().replaceAll("-", "")}`, type: "function", function: { name: call.name, arguments: JSON.stringify(call.arguments) } }));
  return validCalls.length ? { tool_calls: validCalls } : { content: typeof plan.content === "string" ? plan.content : text };
}

function runLocalAi(prompt) {
  if (!COMMAND) return Promise.reject(new Error("LOCAL_AI_GATEWAY_COMMAND is not configured"));
  return new Promise((resolve, reject) => {
    let args;
    try { args = commandArgs(); } catch (error) { reject(error); return; }
    const child = spawn(COMMAND, args, { cwd: "/tmp", stdio: ["pipe", "pipe", "pipe"], env: { ...process.env } });
    let stdout = "";
    let stderr = "";
    let settled = false;
    const finish = (fn, value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      fn(value);
    };
    child.stdout.on("data", (chunk) => { stdout += chunk; });
    child.stderr.on("data", (chunk) => { stderr += chunk; });
    child.on("error", (error) => finish(reject, new Error(`Unable to start local AI command: ${error.message}`)));
    child.on("close", (code, signal) => {
      if (code !== 0) return finish(reject, new Error(`Local AI command exited ${code ?? "?"}${signal ? ` (${signal})` : ""}: ${stderr.trim()}`));
      if (!stdout.trim()) return finish(reject, new Error("Local AI command returned no response"));
      finish(resolve, stdout.trim());
    });
    child.stdin.end(prompt);
    const timer = setTimeout(() => {
      child.kill("SIGTERM");
      setTimeout(() => child.kill("SIGKILL"), 5_000).unref();
      finish(reject, new Error(`Local AI command exceeded ${TIMEOUT_MS}ms`));
    }, TIMEOUT_MS);
  });
}

async function handleCompletion(req, res) {
  let body;
  try { body = JSON.parse(await readBody(req) || "{}"); } catch { return sendError(res, 400, "Invalid JSON body", "invalid_request_error"); }
  if (!Array.isArray(body.messages)) return sendError(res, 400, "messages must be an array", "invalid_request_error");
  const id = `chatcmpl-${randomUUID()}`;
  const created = Math.floor(Date.now() / 1000);
  const tools = Array.isArray(body.tools) ? body.tools : [];
  try {
    const text = await runLocalAi(buildPrompt(body.messages, tools, body.messages.some((message) => message?.role === "tool")));
    const planned = tools.length ? parseToolPlan(text, tools) : { content: text };
    const message = planned.tool_calls ? { role: "assistant", content: null, tool_calls: planned.tool_calls } : { role: "assistant", content: planned.content };
    const response = { id, object: "chat.completion", created, model: MODEL, choices: [{ index: 0, message, finish_reason: planned.tool_calls ? "tool_calls" : "stop" }] };
    if (body.stream) {
      res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache", Connection: "keep-alive" });
      res.write(`data: ${JSON.stringify({ id, object: "chat.completion.chunk", created, model: MODEL, choices: [{ index: 0, delta: message, finish_reason: null }] })}\n\n`);
      res.write(`data: ${JSON.stringify({ id, object: "chat.completion.chunk", created, model: MODEL, choices: [{ index: 0, delta: {}, finish_reason: response.choices[0].finish_reason }] })}\n\n`);
      return res.end("data: [DONE]\n\n");
    }
    sendJson(res, 200, response);
  } catch (error) {
    sendError(res, 502, `Local AI request failed: ${error.message}`);
  }
}

const server = http.createServer(async (req, res) => {
  if (req.method === "GET" && req.url === "/healthz") return sendJson(res, COMMAND ? 200 : 503, { status: COMMAND ? "ready" : "configuration_required", service: "local-ai-gateway" });
  if (req.method === "GET" && req.url === "/v1/models") return sendJson(res, 200, { object: "list", data: [{ id: MODEL, object: "model" }] });
  if (req.method === "POST" && req.url === "/v1/chat/completions") return handleCompletion(req, res);
  sendError(res, 404, "Not found", "not_found_error");
});

server.listen(PORT, HOST, () => console.log(`local-ai-gateway listening on http://${HOST}:${PORT}`));
