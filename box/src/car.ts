import { errorEnvelope, unauthorizedEnvelope, unreachableEnvelope, type Envelope } from "./client.js";
import { httpOrigin, tokenInUrl, type BridgeConfig } from "./config.js";

// Car chat over plain HTTPS with the agent token. It never opens the agent
// WebSocket, so it cannot replace a running `abridge mcp` session.

export interface CarMessage {
  seq: number;
  id: string;
  bot: string;
  text: string;
  at: string;
}

type Fetch = typeof fetch;

function carUrl(config: BridgeConfig, path: string): string {
  return `${httpOrigin(config.relayUrl)}/v1/room/${encodeURIComponent(config.room)}/car/${path}`;
}

async function carFetch(config: BridgeConfig, id: string, path: string, init: RequestInit, fetcher: Fetch): Promise<Envelope> {
  if (tokenInUrl(config.relayUrl)) {
    return errorEnvelope(id, "BAD_ARGS", "The relay URL carries a secret.", "never", "Put the token in the config, not the URL.");
  }
  let response: Response;
  try {
    response = await fetcher(carUrl(config, path), {
      ...init,
      headers: { ...(init.headers ?? {}), Authorization: `Bearer ${config.agentToken}` },
      signal: AbortSignal.timeout(15_000),
    });
  } catch {
    return unreachableEnvelope(id);
  }
  if (response.status === 401 || response.status === 403) return unauthorizedEnvelope(id);
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    body = undefined;
  }
  if (!response.ok) {
    return errorEnvelope(
      id,
      response.status === 400 ? "BAD_ARGS" : "UNSUPPORTED",
      `The relay answered ${response.status}.`,
      "never",
      response.status === 404 ? "Redeploy the relay; this one has no car chat endpoints." : "Check --bot and the text.",
    );
  }
  const data = body && typeof body === "object" ? { ...(body as Record<string, unknown>) } : {};
  delete data.ok;
  return { id, ok: true, data };
}

export function carReply(
  config: BridgeConfig,
  reply: { bot: string; text: string; replyTo?: string },
  fetcher: Fetch = fetch,
): Promise<Envelope> {
  return carFetch(
    config,
    "car_reply",
    "reply",
    { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(reply) },
    fetcher,
  );
}

export function carInbox(config: BridgeConfig, after: number, fetcher: Fetch = fetch): Promise<Envelope> {
  return carFetch(config, "car_inbox", `inbox?after=${Math.max(0, Math.floor(after))}`, { method: "GET" }, fetcher);
}

export function inboxMessages(envelope: Envelope): CarMessage[] {
  const data = envelope.data as { messages?: unknown } | undefined;
  return Array.isArray(data?.messages) ? (data.messages as CarMessage[]) : [];
}
