import { DurableObject } from "cloudflare:workers";
import {
  MAX_FRAME_BYTES,
  MAX_MESSAGES_PER_SECOND,
  PAIR_TTL_MS,
  bearerToken,
  randomBase64Url,
  randomPairCode,
  sha256Hex,
  timingSafeEqual,
  unauthorized,
} from "./crypto";
import type { Env } from "./env";
import {
  CAR_INBOX_KEEP,
  CAR_KEEP_MS,
  CAR_OUTBOX_KEEP,
  CAR_WEBHOOK_TIMEOUT_MS,
  buildWebhookRequest,
  parseCarReply,
  parseCarSay,
  webhookTarget,
  type CarReply,
  type CarSay,
} from "./car";

type Role = "device" | "agent" | "car";

interface Attachment {
  role: Role;
  windowStart: number;
  count: number;
  room?: string;
  origin?: string;
}

interface InboxRow {
  seq: number;
  id: string;
  bot: string;
  text: string;
  at: number;
  [key: string]: SqlStorageValue;
}

interface OutboxRow {
  seq: number;
  bot: string;
  text: string;
  reply_to: string | null;
  at: number;
  [key: string]: SqlStorageValue;
}

interface TokenRow {
  role: string;
  hash: string;
  [key: string]: SqlStorageValue;
}

interface PairRow {
  code_hash: string;
  expires_at: number;
  used: number;
  [key: string]: SqlStorageValue;
}

export class Room extends DurableObject<Env> {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.blockConcurrencyWhile(async () => {
      this.ctx.storage.sql.exec(`
        CREATE TABLE IF NOT EXISTS token (
          role TEXT PRIMARY KEY,
          hash TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS pair (
          code_hash TEXT PRIMARY KEY,
          expires_at INTEGER NOT NULL,
          used INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS car_inbox (
          seq INTEGER PRIMARY KEY AUTOINCREMENT,
          id TEXT NOT NULL,
          bot TEXT NOT NULL,
          text TEXT NOT NULL,
          at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS car_outbox (
          seq INTEGER PRIMARY KEY AUTOINCREMENT,
          bot TEXT NOT NULL,
          text TEXT NOT NULL,
          reply_to TEXT,
          at INTEGER NOT NULL
        );
      `);
    });
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;
    if (request.method === "POST" && path.endsWith("/pair/start")) {
      return this.pairStart(request);
    }
    if (request.method === "POST" && path.endsWith("/pair/claim")) {
      return this.pairClaim(request);
    }
    if (request.method === "POST" && path.endsWith("/revoke")) {
      return this.revoke(request);
    }
    if (request.method === "POST" && path.endsWith("/car/reply")) {
      return this.carReplyHttp(request);
    }
    if (request.method === "GET" && path.endsWith("/car/inbox")) {
      return this.carInboxHttp(request);
    }
    if (request.method === "GET" && (path.endsWith("/device") || path.endsWith("/agent") || path.endsWith("/car"))) {
      const role: Role = path.endsWith("/device") ? "device" : path.endsWith("/agent") ? "agent" : "car";
      return this.upgrade(request, role);
    }
    return new Response("not found", { status: 404 });
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const bytes = messageByteLength(message);
    if (bytes > MAX_FRAME_BYTES) {
      console.log(JSON.stringify({ event: "drop", reason: "frame", bytes }));
      ws.close(1009, "frame too large");
      return;
    }
    const attachment = (ws.deserializeAttachment() as Attachment | null) ?? {
      role: "agent" as const,
      windowStart: 0,
      count: 0,
    };
    const now = Date.now();
    if (now - attachment.windowStart >= 1000) {
      attachment.windowStart = now;
      attachment.count = 0;
    }
    attachment.count += 1;
    ws.serializeAttachment(attachment);
    if (attachment.count > MAX_MESSAGES_PER_SECOND) {
      console.log(JSON.stringify({ event: "drop", reason: "rate", bytes, method: "" }));
      return;
    }
    const text = typeof message === "string" ? message : new TextDecoder().decode(message);
    if (attachment.role === "car") {
      // Car frames carry text only; they are never parsed as phone RPC.
      this.carFrame(ws, attachment, text);
      return;
    }
    const method = methodName(text);
    console.log(JSON.stringify({ event: "fwd", method, bytes }));
    const other = attachment.role === "device" ? "agent" : "device";
    for (const peer of this.ctx.getWebSockets(other)) {
      try {
        peer.send(text);
      } catch {
        console.log(JSON.stringify({ event: "drop", reason: "closed", bytes, method }));
      }
    }
  }

  async webSocketClose(_ws: WebSocket, code: number, reason: string, wasClean: boolean): Promise<void> {
    console.log(JSON.stringify({ event: "close", code, clean: wasClean, reason: reason.slice(0, 32) }));
    // The runtime has already closed the socket. Closing again with 1005 or 1006
    // throws, because those codes are reserved and must not be sent.
  }

  async webSocketError(_ws: WebSocket, _error: unknown): Promise<void> {
    console.log(JSON.stringify({ event: "ws_error" }));
  }

  private async pairStart(request: Request): Promise<Response> {
    const room = request.headers.get("x-room-id") ?? "";
    if (!room) return Response.json({ error: "bad_request" }, { status: 400 });
    const agentToken = randomBase64Url(32);
    const pairCode = randomPairCode();
    const agentHash = await sha256Hex(agentToken);
    const codeHash = await sha256Hex(pairCode);
    this.ctx.storage.sql.exec("DELETE FROM token");
    this.ctx.storage.sql.exec("DELETE FROM pair");
    this.ctx.storage.sql.exec("DELETE FROM car_inbox");
    this.ctx.storage.sql.exec("DELETE FROM car_outbox");
    this.ctx.storage.sql.exec("INSERT INTO token (role, hash) VALUES (?, ?)", "agent", agentHash);
    this.ctx.storage.sql.exec(
      "INSERT INTO pair (code_hash, expires_at, used) VALUES (?, ?, 0)",
      codeHash,
      Date.now() + PAIR_TTL_MS,
    );
    return Response.json({ room, pairCode, agentToken });
  }

  private async pairClaim(request: Request): Promise<Response> {
    let body: { pairCode?: unknown; deviceName?: unknown };
    try {
      body = (await request.json()) as { pairCode?: unknown; deviceName?: unknown };
    } catch {
      return Response.json({ error: "bad_request" }, { status: 400 });
    }
    if (typeof body.pairCode !== "string" || body.pairCode.length === 0) {
      return unauthorized();
    }
    if (body.deviceName !== undefined && typeof body.deviceName !== "string") {
      return Response.json({ error: "bad_request" }, { status: 400 });
    }
    const codeHash = await sha256Hex(body.pairCode);
    const rows = this.ctx.storage.sql
      .exec<PairRow>("SELECT code_hash, expires_at, used FROM pair WHERE code_hash = ?", codeHash)
      .toArray();
    if (rows.length === 0) return unauthorized();
    const row = rows[0];
    if (Number(row.used) !== 0) return Response.json({ ok: false, error: "used" }, { status: 401 });
    if (Number(row.expires_at) < Date.now()) {
      return Response.json({ ok: false, error: "expired" }, { status: 401 });
    }
    this.ctx.storage.sql.exec("UPDATE pair SET used = 1 WHERE code_hash = ?", codeHash);
    const deviceToken = randomBase64Url(32);
    const deviceHash = await sha256Hex(deviceToken);
    this.ctx.storage.sql.exec(
      "INSERT OR REPLACE INTO token (role, hash) VALUES (?, ?)",
      "device",
      deviceHash,
    );
    return Response.json({ deviceToken });
  }

  private async revoke(request: Request): Promise<Response> {
    const token = bearerToken(request);
    if (!token) return unauthorized();
    const hash = await sha256Hex(token);
    const rows = this.ctx.storage.sql.exec<TokenRow>("SELECT role, hash FROM token").toArray();
    const match = rows.find((row) => timingSafeEqual(String(row.hash), hash));
    if (!match) return unauthorized();
    this.ctx.storage.sql.exec("DELETE FROM token");
    this.ctx.storage.sql.exec("DELETE FROM pair");
    this.ctx.storage.sql.exec("DELETE FROM car_inbox");
    this.ctx.storage.sql.exec("DELETE FROM car_outbox");
    for (const socket of this.ctx.getWebSockets()) socket.close(4000, "revoked");
    return Response.json({ ok: true });
  }

  private carFrame(ws: WebSocket, attachment: Attachment, text: string): void {
    let parsed: unknown;
    try {
      parsed = JSON.parse(text);
    } catch {
      parsed = null;
    }
    const type = parsed && typeof parsed === "object" ? (parsed as { type?: unknown }).type : undefined;
    if (type === "car.sync") {
      this.flushOutbox(ws);
      return;
    }
    const say = parseCarSay(parsed);
    if (!say) {
      console.log(JSON.stringify({ event: "car_drop", reason: "bad_frame" }));
      safeSend(ws, JSON.stringify({ type: "car.error", error: "bad_frame" }));
      return;
    }
    const at = Date.now();
    this.ctx.storage.sql.exec(
      "INSERT INTO car_inbox (id, bot, text, at) VALUES (?, ?, ?, ?)",
      say.id,
      say.bot,
      say.text,
      at,
    );
    this.pruneCar(at);
    const webhook = webhookTarget(this.env) ? "pending" : "off";
    console.log(JSON.stringify({ event: "car_say", bytes: say.text.length, webhook }));
    safeSend(ws, JSON.stringify({ type: "car.ack", id: say.id, queued: true, webhook }));
    if (webhook === "pending") {
      this.ctx.waitUntil(this.sendWebhook(say, attachment.room ?? "", attachment.origin ?? "", at));
    }
  }

  private async sendWebhook(say: CarSay, room: string, origin: string, at: number): Promise<void> {
    const request = buildWebhookRequest(this.env, say, { room, origin, at });
    if (!request) return;
    let status = 0;
    try {
      const response = await fetch(request, { signal: AbortSignal.timeout(CAR_WEBHOOK_TIMEOUT_MS) });
      status = response.status;
      await response.body?.cancel();
    } catch {
      status = 0;
    }
    const ok = status >= 200 && status < 300;
    // Log the status only. Never log the URL, key, or message text.
    console.log(JSON.stringify({ event: "car_webhook", status }));
    for (const socket of this.ctx.getWebSockets("car")) {
      safeSend(socket, JSON.stringify({ type: "car.status", id: say.id, webhook: ok ? "sent" : "failed", status }));
    }
  }

  private flushOutbox(ws: WebSocket): void {
    const rows = this.ctx.storage.sql
      .exec<OutboxRow>("SELECT seq, bot, text, reply_to, at FROM car_outbox ORDER BY seq")
      .toArray();
    for (const row of rows) {
      const reply: CarReply = { bot: String(row.bot), text: String(row.text) };
      if (row.reply_to) reply.replyTo = String(row.reply_to);
      if (!safeSend(ws, carReplyFrame(reply, Number(row.at)))) return;
      this.ctx.storage.sql.exec("DELETE FROM car_outbox WHERE seq = ?", row.seq);
    }
  }

  private pruneCar(now: number): void {
    const cutoff = now - CAR_KEEP_MS;
    this.ctx.storage.sql.exec("DELETE FROM car_inbox WHERE at < ?", cutoff);
    this.ctx.storage.sql.exec(
      "DELETE FROM car_inbox WHERE seq NOT IN (SELECT seq FROM car_inbox ORDER BY seq DESC LIMIT ?)",
      CAR_INBOX_KEEP,
    );
    this.ctx.storage.sql.exec("DELETE FROM car_outbox WHERE at < ?", cutoff);
    this.ctx.storage.sql.exec(
      "DELETE FROM car_outbox WHERE seq NOT IN (SELECT seq FROM car_outbox ORDER BY seq DESC LIMIT ?)",
      CAR_OUTBOX_KEEP,
    );
  }

  private async carReplyHttp(request: Request): Promise<Response> {
    if (!(await this.tokenOk(request, "agent"))) return unauthorized();
    let body: unknown;
    try {
      body = await request.json();
    } catch {
      return Response.json({ ok: false, error: "bad_request" }, { status: 400 });
    }
    const reply = parseCarReply(body);
    if (!reply) return Response.json({ ok: false, error: "bad_request" }, { status: 400 });
    const at = Date.now();
    const frame = carReplyFrame(reply, at);
    let delivered = false;
    for (const socket of this.ctx.getWebSockets("car")) {
      if (safeSend(socket, frame)) delivered = true;
    }
    if (!delivered) {
      this.ctx.storage.sql.exec(
        "INSERT INTO car_outbox (bot, text, reply_to, at) VALUES (?, ?, ?, ?)",
        reply.bot,
        reply.text,
        reply.replyTo ?? null,
        at,
      );
      this.pruneCar(at);
    }
    console.log(JSON.stringify({ event: "car_reply", delivered }));
    return Response.json({ ok: true, delivered: delivered ? "live" : "queued" });
  }

  private async carInboxHttp(request: Request): Promise<Response> {
    if (!(await this.tokenOk(request, "agent"))) return unauthorized();
    const url = new URL(request.url);
    const after = Number.parseInt(url.searchParams.get("after") ?? "0", 10);
    const limitRaw = Number.parseInt(url.searchParams.get("limit") ?? "50", 10);
    const limit = Number.isFinite(limitRaw) ? Math.min(Math.max(limitRaw, 1), 200) : 50;
    this.pruneCar(Date.now());
    const rows = this.ctx.storage.sql
      .exec<InboxRow>(
        "SELECT seq, id, bot, text, at FROM car_inbox WHERE seq > ? ORDER BY seq LIMIT ?",
        Number.isFinite(after) ? after : 0,
        limit,
      )
      .toArray();
    const messages = rows.map((row) => ({
      seq: Number(row.seq),
      id: String(row.id),
      bot: String(row.bot),
      text: String(row.text),
      at: new Date(Number(row.at)).toISOString(),
    }));
    return Response.json({ ok: true, messages });
  }

  /** True when the bearer matches the stored token for that role. */
  private async tokenOk(request: Request, role: "device" | "agent"): Promise<boolean> {
    const token = bearerToken(request);
    if (!token) return false;
    const presented = await sha256Hex(token);
    const rows = this.ctx.storage.sql
      .exec<{ hash: string }>("SELECT hash FROM token WHERE role = ?", role)
      .toArray();
    return rows.length > 0 && timingSafeEqual(String(rows[0].hash), presented);
  }

  private async upgrade(request: Request, role: Role): Promise<Response> {
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("expected websocket", { status: 426 });
    }
    // The car socket is opened by the paired phone, so it uses the device token.
    if (!(await this.tokenOk(request, role === "agent" ? "agent" : "device"))) return unauthorized();
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    const previous = this.ctx.getWebSockets(role);
    this.ctx.acceptWebSocket(server, [role]);
    const url = new URL(request.url);
    const attachment: Attachment = { role, windowStart: 0, count: 0 };
    if (role === "car") {
      attachment.room = roomFromPath(url.pathname);
      attachment.origin = url.origin;
    }
    server.serializeAttachment(attachment);
    for (const existing of previous) existing.close(4001, "replaced");
    return new Response(null, { status: 101, webSocket: client });
  }
}

function carReplyFrame(reply: CarReply, at: number): string {
  return JSON.stringify({ type: "car.reply", ...reply, at: new Date(at).toISOString() });
}

function safeSend(ws: WebSocket, text: string): boolean {
  try {
    ws.send(text);
    return true;
  } catch {
    return false;
  }
}

function roomFromPath(path: string): string {
  return path.match(/\/v1\/room\/([^/]+)\//)?.[1] ?? "";
}

function messageByteLength(message: string | ArrayBuffer): number {
  if (typeof message === "string") return new TextEncoder().encode(message).byteLength;
  return message.byteLength;
}

function methodName(text: string): string {
  try {
    const parsed = JSON.parse(text) as { method?: unknown };
    if (typeof parsed.method === "string") return parsed.method.slice(0, 64);
  } catch {
    return "";
  }
  return "";
}
