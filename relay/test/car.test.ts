import { env, SELF } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { buildWebhookRequest, parseCarReply, parseCarSay, webhookTarget } from "../src/car";
import type { Env } from "../src/env";

const ADMIN = "test-admin-token";

interface Paired {
  room: string;
  agentToken: string;
  deviceToken: string;
}

async function pair(): Promise<Paired> {
  const started = (await (
    await SELF.fetch("http://relay.test/v1/pair/start", {
      method: "POST",
      headers: { Authorization: `Bearer ${ADMIN}` },
    })
  ).json()) as { room: string; pairCode: string; agentToken: string };
  const claimed = (await (
    await SELF.fetch("http://relay.test/v1/pair/claim", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ room: started.room, pairCode: started.pairCode }),
    })
  ).json()) as { deviceToken: string };
  return { room: started.room, agentToken: started.agentToken, deviceToken: claimed.deviceToken };
}

async function openSocket(room: string, role: "device" | "agent" | "car", token: string | null): Promise<Response> {
  const headers = new Headers({ Upgrade: "websocket", Connection: "Upgrade" });
  if (token !== null) headers.set("Authorization", `Bearer ${token}`);
  return SELF.fetch(`http://relay.test/v1/room/${room}/${role}`, { headers });
}

function accept(response: Response): WebSocket {
  const socket = response.webSocket;
  if (!socket) throw new Error(`no websocket, status ${response.status}`);
  socket.accept();
  return socket;
}

function collector(socket: WebSocket) {
  const seen: string[] = [];
  const waiters: Array<() => void> = [];
  socket.addEventListener("message", (event) => {
    const data = event.data;
    seen.push(typeof data === "string" ? data : new TextDecoder().decode(data as ArrayBuffer));
    for (const wake of waiters.splice(0)) wake();
  });
  return {
    seen,
    async next(match: (frame: Record<string, unknown>) => boolean, ms = 2000): Promise<Record<string, unknown>> {
      const deadline = Date.now() + ms;
      let index = 0;
      for (;;) {
        for (; index < seen.length; index++) {
          const parsed = JSON.parse(seen[index]) as Record<string, unknown>;
          if (match(parsed)) return parsed;
        }
        const left = deadline - Date.now();
        if (left <= 0) throw new Error(`frame timeout; saw ${seen.join(" | ")}`);
        await new Promise<void>((resolve) => {
          const timer = setTimeout(resolve, left);
          waiters.push(() => {
            clearTimeout(timer);
            resolve();
          });
        });
      }
    },
  };
}

function inbox(room: string, token: string | null, after = 0): Promise<Response> {
  const headers = new Headers();
  if (token !== null) headers.set("Authorization", `Bearer ${token}`);
  return SELF.fetch(`http://relay.test/v1/room/${room}/car/inbox?after=${after}`, { headers });
}

function reply(room: string, token: string | null, body: unknown): Promise<Response> {
  const headers = new Headers({ "content-type": "application/json" });
  if (token !== null) headers.set("Authorization", `Bearer ${token}`);
  return SELF.fetch(`http://relay.test/v1/room/${room}/car/reply`, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
  });
}

describe("car frame parsing", () => {
  it("accepts a well-formed car.say and trims it", () => {
    expect(parseCarSay({ type: "car.say", id: "m1", bot: " Travel Bot ", text: " hi " })).toEqual({
      id: "m1",
      bot: "Travel Bot",
      text: "hi",
    });
  });

  it("rejects a wrong type, empty fields, and oversize text", () => {
    expect(parseCarSay({ type: "car.reply", id: "m1", bot: "b", text: "t" })).toBeNull();
    expect(parseCarSay({ type: "car.say", id: "", bot: "b", text: "t" })).toBeNull();
    expect(parseCarSay({ type: "car.say", id: "m1", bot: "b", text: "x".repeat(2001) })).toBeNull();
    expect(parseCarSay({ type: "car.say", id: "m1", bot: "b".repeat(65), text: "t" })).toBeNull();
    expect(parseCarSay("car.say")).toBeNull();
  });

  it("parses replies with and without replyTo", () => {
    expect(parseCarReply({ bot: "B", text: "t" })).toEqual({ bot: "B", text: "t" });
    expect(parseCarReply({ bot: "B", text: "t", replyTo: "m1" })).toEqual({ bot: "B", text: "t", replyTo: "m1" });
    expect(parseCarReply({ bot: "B", text: "t", replyTo: 5 })).toBeNull();
    expect(parseCarReply({ bot: "", text: "t" })).toBeNull();
  });
});

describe("car webhook request", () => {
  const say = { id: "m1", bot: "Travel Bot", text: "find a hotel" };
  const context = { room: "ROOMABC12345", origin: "https://relay.example", at: Date.UTC(2026, 0, 2, 3, 4, 5) };

  it("is off unless an https URL is configured", () => {
    expect(webhookTarget({} as Env)).toBeNull();
    expect(webhookTarget({ CAR_WEBHOOK_URL: "http://hooks.example/x" } as Env)).toBeNull();
    expect(webhookTarget({ CAR_WEBHOOK_URL: "not a url" } as Env)).toBeNull();
    expect(webhookTarget({ CAR_WEBHOOK_URL: "http://localhost:8787/hook" } as Env)?.toString()).toBe(
      "http://localhost:8787/hook",
    );
    expect(buildWebhookRequest({} as Env, say, context)).toBeNull();
  });

  it("posts JSON with a default Bearer header", async () => {
    const request = buildWebhookRequest(
      { CAR_WEBHOOK_URL: "https://hooks.example/car", CAR_WEBHOOK_KEY: "k123" } as Env,
      say,
      context,
    );
    expect(request).not.toBeNull();
    expect(request!.method).toBe("POST");
    expect(request!.url).toBe("https://hooks.example/car");
    expect(request!.headers.get("content-type")).toBe("application/json");
    expect(request!.headers.get("authorization")).toBe("Bearer k123");
    expect(await request!.json()).toEqual({
      type: "car.say",
      id: "m1",
      bot: "Travel Bot",
      text: "find a hotel",
      message: "[Car chat for Travel Bot] find a hotel",
      at: "2026-01-02T03:04:05.000Z",
      room: "ROOMABC12345",
      replyUrl: "https://relay.example/v1/room/ROOMABC12345/car/reply",
    });
  });

  it("supports a custom header name and a raw key", () => {
    const request = buildWebhookRequest(
      {
        CAR_WEBHOOK_URL: "https://hooks.example/car",
        CAR_WEBHOOK_KEY: "k123",
        CAR_WEBHOOK_AUTH_HEADER: "x-api-key",
        CAR_WEBHOOK_AUTH_SCHEME: "none",
      } as Env,
      say,
      context,
    );
    expect(request!.headers.get("x-api-key")).toBe("k123");
    expect(request!.headers.get("authorization")).toBeNull();
  });

  it("sends no auth header when no key is set", () => {
    const request = buildWebhookRequest({ CAR_WEBHOOK_URL: "https://hooks.example/car" } as Env, say, context);
    expect(request!.headers.get("authorization")).toBeNull();
  });
});

describe("car chat through the relay", () => {
  it("rejects the car socket without the device token", async () => {
    const paired = await pair();
    expect((await openSocket(paired.room, "car", null)).status).toBe(401);
    expect((await openSocket(paired.room, "car", paired.agentToken)).status).toBe(401);
    const ok = await openSocket(paired.room, "car", paired.deviceToken);
    expect(ok.status).toBe(101);
    accept(ok).close(1000, "done");
  });

  it("acks car.say, queues it for the box, and never forwards it to the agent socket", async () => {
    const paired = await pair();
    const agent = accept(await openSocket(paired.room, "agent", paired.agentToken));
    const agentFrames = collector(agent);
    const car = accept(await openSocket(paired.room, "car", paired.deviceToken));
    const carFrames = collector(car);
    car.send(JSON.stringify({ type: "car.say", id: "m1", bot: "Travel Bot", text: "find a hotel" }));
    const ack = await carFrames.next((f) => f.type === "car.ack");
    expect(ack).toEqual({ type: "car.ack", id: "m1", queued: true, webhook: "off" });

    const unauth = await inbox(paired.room, paired.deviceToken);
    expect(unauth.status).toBe(401);
    const listed = (await (await inbox(paired.room, paired.agentToken)).json()) as {
      messages: Array<{ seq: number; id: string; bot: string; text: string }>;
    };
    expect(listed.messages).toHaveLength(1);
    expect(listed.messages[0]).toMatchObject({ id: "m1", bot: "Travel Bot", text: "find a hotel" });
    const after = (await (await inbox(paired.room, paired.agentToken, listed.messages[0].seq)).json()) as {
      messages: unknown[];
    };
    expect(after.messages).toHaveLength(0);
    expect(agentFrames.seen).toHaveLength(0);
    car.close(1000, "done");
    agent.close(1000, "done");
  });

  it("answers a malformed car frame with car.error and stores nothing", async () => {
    const paired = await pair();
    const car = accept(await openSocket(paired.room, "car", paired.deviceToken));
    const frames = collector(car);
    car.send(JSON.stringify({ id: "1", method: "tap", params: { ref: "@s1:e1" } }));
    expect(await frames.next((f) => f.type === "car.error")).toEqual({ type: "car.error", error: "bad_frame" });
    const listed = (await (await inbox(paired.room, paired.agentToken)).json()) as { messages: unknown[] };
    expect(listed.messages).toHaveLength(0);
    car.close(1000, "done");
  });

  it("does not route agent RPC to the car socket or car frames to the device socket", async () => {
    const paired = await pair();
    const device = accept(await openSocket(paired.room, "device", paired.deviceToken));
    const deviceFrames = collector(device);
    const car = accept(await openSocket(paired.room, "car", paired.deviceToken));
    const carFrames = collector(car);
    const agent = accept(await openSocket(paired.room, "agent", paired.agentToken));
    agent.send(JSON.stringify({ id: "r1", method: "status", params: {} }));
    await deviceFrames.next((f) => f.method === "status");
    car.send(JSON.stringify({ type: "car.say", id: "m2", bot: "B", text: "hello" }));
    await carFrames.next((f) => f.type === "car.ack");
    expect(carFrames.seen.some((f) => f.includes('"method"'))).toBe(false);
    expect(deviceFrames.seen.some((f) => f.includes("car."))).toBe(false);
    device.close(1000, "done");
    car.close(1000, "done");
    agent.close(1000, "done");
  });

  it("delivers car.reply live, and queues it until the phone syncs", async () => {
    const paired = await pair();
    expect((await reply(paired.room, null, { bot: "B", text: "t" })).status).toBe(401);
    expect((await reply(paired.room, paired.deviceToken, { bot: "B", text: "t" })).status).toBe(401);
    expect((await reply(paired.room, paired.agentToken, { bot: "", text: "t" })).status).toBe(400);

    const queued = (await (
      await reply(paired.room, paired.agentToken, { bot: "Travel Bot", text: "Booked.", replyTo: "m1" })
    ).json()) as { delivered: string };
    expect(queued.delivered).toBe("queued");

    const car = accept(await openSocket(paired.room, "car", paired.deviceToken));
    const frames = collector(car);
    car.send(JSON.stringify({ type: "car.sync" }));
    expect(await frames.next((f) => f.type === "car.reply")).toMatchObject({
      type: "car.reply",
      bot: "Travel Bot",
      text: "Booked.",
      replyTo: "m1",
    });

    const live = (await (await reply(paired.room, paired.agentToken, { bot: "B", text: "live one" })).json()) as {
      delivered: string;
    };
    expect(live.delivered).toBe("live");
    expect(await frames.next((f) => f.type === "car.reply" && f.text === "live one")).toMatchObject({ bot: "B" });

    car.send(JSON.stringify({ type: "car.sync" }));
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(frames.seen.filter((f) => f.includes("Booked."))).toHaveLength(1);
    car.close(1000, "done");
  });

  it("keeps the env free of webhook config in tests", () => {
    expect(env.CAR_WEBHOOK_URL).toBeUndefined();
  });
});
