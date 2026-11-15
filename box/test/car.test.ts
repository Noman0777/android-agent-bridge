import { execFile } from "node:child_process";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { carReply } from "../src/car.js";
import { startFakeRelay, type FakeRelay } from "./fake-relay.js";

const root = join(import.meta.dirname, "..");
let relay: FakeRelay;

beforeAll(async () => {
  relay = await startFakeRelay();
});

afterAll(async () => {
  await relay.close();
});

function run(args: string[], env: NodeJS.ProcessEnv): Promise<{ code: number; stdout: string; stderr: string }> {
  return new Promise((resolve) => {
    execFile("npx", ["tsx", "src/cli.ts", ...args], { cwd: root, env: { ...process.env, ...env } }, (error, stdout, stderr) => {
      const code = typeof error?.code === "number" ? error.code : error ? 1 : 0;
      resolve({ code, stdout, stderr });
    });
  });
}

function env(token = relay.agentToken): NodeJS.ProcessEnv {
  return {
    ABRIDGE_RELAY_URL: relay.url,
    ABRIDGE_ROOM: relay.room,
    ABRIDGE_AGENT_TOKEN: token,
    HOME: mkdtempSync(join(tmpdir(), "abridge-home-")),
  };
}

describe("abridge car", () => {
  it("posts a car.reply over HTTP and never prints the token", async () => {
    const sockets = relay.agentSockets;
    const result = await run(["car", "reply", "--bot", "Travel Bot", "--reply-to", "m1", "Booked", "it."], env());
    expect(result.code).toBe(0);
    expect(JSON.parse(result.stdout.trim())).toEqual({ id: "car_reply", ok: true, data: { delivered: "queued" } });
    expect(relay.carReplies.at(-1)).toEqual({ bot: "Travel Bot", text: "Booked it.", replyTo: "m1" });
    expect(result.stdout + result.stderr).not.toContain(relay.agentToken);
    // HTTP only: car commands must not replace a running agent socket.
    expect(relay.agentSockets).toBe(sockets);
  });

  it("needs --bot and text", async () => {
    const result = await run(["car", "reply", "hello"], env());
    expect(result.code).toBe(1);
    expect(JSON.parse(result.stdout.trim()).error.code).toBe("BAD_ARGS");
  });

  it("reports UNAUTHORIZED for a wrong token", async () => {
    const result = await run(["car", "reply", "--bot", "B", "hi"], env("wrong-token"));
    expect(result.code).toBe(1);
    expect(JSON.parse(result.stdout.trim()).error.code).toBe("UNAUTHORIZED");
    expect(result.stdout).not.toContain("wrong-token");
  });

  it("prints queued car.say lines with inbox and listen --once", async () => {
    const inbox = await run(["car", "inbox"], env());
    expect(inbox.code).toBe(0);
    expect(JSON.parse(inbox.stdout.trim())).toMatchObject({ type: "car.say", seq: 1, bot: "Travel Bot", text: "find a hotel" });
    const after = await run(["car", "listen", "--once", "--after", "1"], env());
    expect(after.code).toBe(0);
    expect(after.stdout.trim()).toBe("");
  });

  it("maps an unreachable relay to RELAY_UNREACHABLE", async () => {
    const envelope = await carReply(
      { relayUrl: "http://127.0.0.1:1", room: "ROOMTEST1234", agentToken: "x" },
      { bot: "B", text: "t" },
    );
    expect(envelope.error?.code).toBe("RELAY_UNREACHABLE");
  });
});
