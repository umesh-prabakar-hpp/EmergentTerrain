let prompt = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk) => { prompt += chunk; });
process.stdin.on("end", () => {
  const respond = (value) => process.stdout.write(`${JSON.stringify(value)}\n`);
  const has = (value) => prompt.includes(value);

  if (has("MULTI_ROUND")) {
    if (!has("TOOL (tool_call_id=")) {
      respond({ tool_calls: [{ name: "multiply", arguments: { a: 6, b: 7 } }] });
    } else if (has("42") && !has("142")) {
      respond({ tool_calls: [{ name: "add", arguments: { a: 42, b: 100 } }] });
    } else {
      respond({ content: "FINAL=142" });
    }
    return;
  }

  if (has("PARALLEL")) {
    if (!has("TOOL (tool_call_id=")) {
      respond({ tool_calls: [
        { name: "multiply", arguments: { a: 6, b: 7 } },
        { name: "add", arguments: { a: 40, b: 2 } },
      ] });
    } else {
      respond({ content: "PARALLEL=42" });
    }
    return;
  }

  respond({ content: "NO_TOOL_OK" });
});
