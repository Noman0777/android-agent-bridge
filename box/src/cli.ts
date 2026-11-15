#!/usr/bin/env node
import { pathToFileURL } from "node:url";
import { callPhone, errorEnvelope, type Envelope } from "./client.js";
import { carInbox, carReply, inboxMessages } from "./car.js";
import { loadConfig } from "./config.js";
import { serveMcp } from "./mcp.js";
import { renderQr, startPair } from "./pair.js";

export async function main(argv: string[]): Promise<number> {
  const [command, ...rest] = argv;
  if (!command || command === "--help" || command === "-h") {
    process.stderr.write(usage());
    return 2;
  }
  if (command === "mcp") {
    await serveMcp();
    return 0;
  }
  if (command === "pair") {
    return pairCommand(rest);
  }
  if (command === "car") {
    return carCommand(rest);
  }
  let params: unknown = {};
  if (rest.length > 0) {
    try {
      params = JSON.parse(rest[0]);
    } catch {
      writeEnvelope(
        errorEnvelope("local", "BAD_ARGS", "Params must be one JSON object.", "never", "Example: abridge snapshot '{\"maxNodes\":50}'"),
      );
      return 1;
    }
  }
  const config = loadConfig();
  if (!config) {
    writeEnvelope(
      errorEnvelope(
        "local",
        "BAD_ARGS",
        "Missing relay config.",
        "never",
        "Set ABRIDGE_RELAY_URL, ABRIDGE_ROOM, and ABRIDGE_AGENT_TOKEN or run abridge pair.",
      ),
    );
    return 1;
  }
  const envelope = await callPhone(config, command, params);
  writeEnvelope(envelope);
  return envelope.ok ? 0 : 1;
}

async function pairCommand(args: string[]): Promise<number> {
  let relay = "";
  let admin = process.env.ABRIDGE_ADMIN_TOKEN ?? "";
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--relay") relay = args[++i] ?? "";
    else if (args[i] === "--admin-token") admin = args[++i] ?? "";
  }
  if (!relay || !admin) {
    writeEnvelope(
      errorEnvelope(
        "local",
        "BAD_ARGS",
        "pair needs --relay and an admin token.",
        "never",
        "Pass --admin-token or set ABRIDGE_ADMIN_TOKEN. The token is not printed.",
      ),
    );
    return 1;
  }
  try {
    const paired = await startPair(relay, admin);
    process.stdout.write(paired.link + "\n");
    process.stdout.write((await renderQr(paired.link)) + "\n");
    process.stderr.write(`Paired room ${paired.room}. Agent token saved to the config file.\n`);
    return 0;
  } catch {
    writeEnvelope(
      errorEnvelope("local", "TIMEOUT", "pair/start failed.", "safe", "Check the relay URL and admin token."),
    );
    return 1;
  }
}

function missingConfig(): number {
  writeEnvelope(
    errorEnvelope(
      "local",
      "BAD_ARGS",
      "Missing relay config.",
      "never",
      "Set ABRIDGE_RELAY_URL, ABRIDGE_ROOM, and ABRIDGE_AGENT_TOKEN or run abridge pair.",
    ),
  );
  return 1;
}

async function carCommand(args: string[]): Promise<number> {
  const [sub, ...rest] = args;
  let bot = "";
  let replyTo: string | undefined;
  let after = 0;
  let once = false;
  let intervalMs = 3000;
  const words: string[] = [];
  for (let i = 0; i < rest.length; i++) {
    const arg = rest[i];
    if (arg === "--bot") bot = rest[++i] ?? "";
    else if (arg === "--reply-to") replyTo = rest[++i];
    else if (arg === "--after") after = Number.parseInt(rest[++i] ?? "0", 10) || 0;
    else if (arg === "--interval") intervalMs = Math.max(1000, (Number.parseFloat(rest[++i] ?? "3") || 3) * 1000);
    else if (arg === "--once") once = true;
    else words.push(arg);
  }
  if (sub === "reply") {
    const text = words.join(" ").trim();
    if (!bot.trim() || !text) {
      writeEnvelope(
        errorEnvelope("local", "BAD_ARGS", "car reply needs --bot and text.", "never", 'Example: abridge car reply --bot "Travel Bot" "Booked."'),
      );
      return 1;
    }
    const config = loadConfig();
    if (!config) return missingConfig();
    const envelope = await carReply(config, { bot: bot.trim(), text, ...(replyTo ? { replyTo } : {}) });
    writeEnvelope(envelope);
    return envelope.ok ? 0 : 1;
  }
  if (sub === "listen" || sub === "inbox") {
    const config = loadConfig();
    if (!config) return missingConfig();
    const single = once || sub === "inbox";
    let cursor = after;
    for (;;) {
      const envelope = await carInbox(config, cursor);
      if (!envelope.ok) {
        writeEnvelope(envelope);
        if (single || envelope.error?.code === "UNAUTHORIZED") return 1;
      } else {
        for (const message of inboxMessages(envelope)) {
          process.stdout.write(JSON.stringify({ type: "car.say", ...message }) + "\n");
          cursor = Math.max(cursor, message.seq);
        }
        if (single) return 0;
      }
      await new Promise((resolve) => setTimeout(resolve, intervalMs));
    }
  }
  writeEnvelope(
    errorEnvelope("local", "BAD_ARGS", "Unknown car command.", "never", "Use: abridge car reply|listen|inbox"),
  );
  return 1;
}

function writeEnvelope(envelope: Envelope): void {
  process.stdout.write(JSON.stringify(envelope) + "\n");
}

function usage(): string {
  return `abridge pair --relay <url> [--admin-token <token>]
abridge <method> [json-params]
abridge mcp
abridge car reply --bot <name> [--reply-to <id>] <text>
abridge car listen [--after <seq>] [--interval <seconds>] [--once]
abridge car inbox [--after <seq>]

Methods: status snapshot tap type swipe scroll key launch list_apps wait screenshot volume
Config: ~/.config/abridge/config.json or ABRIDGE_RELAY_URL, ABRIDGE_ROOM, ABRIDGE_AGENT_TOKEN
`;
}

const entry = process.argv[1];
if (entry && import.meta.url === pathToFileURL(entry).href) {
  main(process.argv.slice(2))
    .then((code) => process.exit(code))
    .catch(() => {
      writeEnvelope(errorEnvelope("local", "UNSUPPORTED", "abridge failed.", "never", "Re-run with a method and JSON params."));
      process.exit(1);
    });
}
