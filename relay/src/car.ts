import type { Env } from "./env";

// Car frames carry text between the driver and their agents. They ride their own
// socket role, so a car message is never itself parsed as a phone RPC. Agents can
// still use the normal phone tools, gated by the phone's existing safety controls.

export const CAR_MAX_BOT = 64;
export const CAR_MAX_TEXT = 2000;
export const CAR_MAX_ID = 64;
export const CAR_INBOX_KEEP = 200;
export const CAR_OUTBOX_KEEP = 50;
export const CAR_KEEP_MS = 24 * 60 * 60 * 1000;
export const CAR_WEBHOOK_TIMEOUT_MS = 10_000;

export interface CarSay {
  id: string;
  bot: string;
  text: string;
}

export interface CarReply {
  bot: string;
  text: string;
  replyTo?: string;
}

const CONTROL = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g;

function cleanString(value: unknown, max: number): string | null {
  if (typeof value !== "string") return null;
  const cleaned = value.replace(CONTROL, "").trim();
  if (cleaned.length === 0 || cleaned.length > max) return null;
  return cleaned;
}

export function parseCarSay(value: unknown): CarSay | null {
  if (!value || typeof value !== "object") return null;
  const v = value as Record<string, unknown>;
  if (v.type !== "car.say") return null;
  const id = cleanString(v.id, CAR_MAX_ID);
  const bot = cleanString(v.bot, CAR_MAX_BOT);
  const text = cleanString(v.text, CAR_MAX_TEXT);
  if (!id || !bot || !text) return null;
  return { id, bot, text };
}

export function parseCarReply(value: unknown): CarReply | null {
  if (!value || typeof value !== "object") return null;
  const v = value as Record<string, unknown>;
  const bot = cleanString(v.bot, CAR_MAX_BOT);
  const text = cleanString(v.text, CAR_MAX_TEXT);
  if (!bot || !text) return null;
  if (v.replyTo === undefined || v.replyTo === null) return { bot, text };
  const replyTo = cleanString(v.replyTo, CAR_MAX_ID);
  if (!replyTo) return null;
  return { bot, text, replyTo };
}

export function webhookTarget(env: Env): URL | null {
  const raw = (env.CAR_WEBHOOK_URL ?? "").trim();
  if (!raw) return null;
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return null;
  }
  if (url.protocol === "https:") return url;
  if (url.protocol === "http:" && (url.hostname === "localhost" || url.hostname === "127.0.0.1")) return url;
  return null;
}

/**
 * Builds the outbound webhook request for one car.say. Pure, so tests can check
 * the exact wire format without network access.
 */
export function buildWebhookRequest(
  env: Env,
  say: CarSay,
  context: { room: string; origin: string; at: number },
): Request | null {
  const target = webhookTarget(env);
  if (!target) return null;
  const headers = new Headers({ "content-type": "application/json", "user-agent": "abridge-relay/car" });
  const key = (env.CAR_WEBHOOK_KEY ?? "").trim();
  if (key) {
    const header = (env.CAR_WEBHOOK_AUTH_HEADER ?? "").trim() || "Authorization";
    const schemeRaw = env.CAR_WEBHOOK_AUTH_SCHEME;
    const scheme = schemeRaw === undefined ? "Bearer" : schemeRaw.trim();
    const value = scheme === "" || scheme.toLowerCase() === "none" ? key : `${scheme} ${key}`;
    headers.set(header, value);
  }
  const body = {
    type: "car.say",
    id: say.id,
    bot: say.bot,
    text: say.text,
    message: `[Car chat for ${say.bot}] ${say.text}`,
    at: new Date(context.at).toISOString(),
    room: context.room,
    replyUrl: context.origin ? `${context.origin}/v1/room/${context.room}/car/reply` : "",
  };
  return new Request(target.toString(), { method: "POST", headers, body: JSON.stringify(body) });
}
