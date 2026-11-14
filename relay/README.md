# abridge relay

This directory is the Cloudflare Worker for [Android Agent Bridge](https://github.com/brantley-creator/android-agent-bridge). One Durable Object (`Room`) per room checks tokens and forwards frames between a phone and an agent. It does not decide whether a tap is allowed. The phone does.

Deploying is your action on your own Cloudflare account. Nothing in this directory is a credential. `RELAY_ADMIN_TOKEN` is a secret. Local dev reads `.dev.vars` (gitignored). The Deploy to Cloudflare button prompts for it from `.dev.vars.example`. You can also set it with `npx wrangler secret put RELAY_ADMIN_TOKEN`.

If `RELAY_ADMIN_TOKEN` is unset, `POST /v1/pair/start` fails closed.

## URL

`wrangler.toml` sets `workers_dev = true` and names the Worker `abridge-relay`, so the deployed URL is:

```text
https://abridge-relay.<your-subdomain>.workers.dev
```

Opening Workers & Pages in the Cloudflare dashboard once creates the workers.dev subdomain. The free plan covers this SQLite-backed Durable Object (`new_sqlite_classes`).

```sh
curl https://abridge-relay.<your-subdomain>.workers.dev/health
```

`{"ok":true}` means the Worker is answering. `/health` does not check the admin token.

## Pair with the box CLI

The phone app and the `box/` CLI live in the main repository. This deployed copy is only the relay. From a checkout of the main repo:

```sh
cd box
ABRIDGE_ADMIN_TOKEN='the-admin-token' \
  npx tsx src/cli.ts pair --relay https://abridge-relay.<your-subdomain>.workers.dev
```

Open the printed `agentbridge://` link on the phone, check the host and the room, and tap Pair. Then tap Arm session. The agent token is saved to `~/.config/abridge/config.json` (mode `0600`) and is not part of the link.

The phone must use `https` for any host other than `localhost`, `127.0.0.1`, and `10.0.2.2`. Cloudflare can see frames. There is no end-to-end encryption yet.

## Car chat (optional)

The relay also carries Android Auto car chat: the phone sends `car.say` on `/v1/room/<room>/car`, the relay stores it for `GET /v1/room/<room>/car/inbox` and, if configured, POSTs it to a webhook. Agents answer with `POST /v1/room/<room>/car/reply`. Both HTTP routes need the room's agent token.

Webhook secrets are all optional. Set them with `npx wrangler secret put <NAME>`:

- `CAR_WEBHOOK_URL`: `https://` endpoint that receives each message. Unset means no webhook.
- `CAR_WEBHOOK_KEY`: secret for the receiver.
- `CAR_WEBHOOK_AUTH_HEADER`: header name, default `Authorization`.
- `CAR_WEBHOOK_AUTH_SCHEME`: prefix before the key, default `Bearer`; `none` sends the bare key.

The request is `POST` with JSON `{type:"car.say", id, bot, text, message, at, room, replyUrl}`. Full details are in `docs/CAR_CHAT.md` in the main repository.

## Local dev

```sh
cp .dev.vars.example .dev.vars
# set RELAY_ADMIN_TOKEN, for example: openssl rand -hex 32
npm ci
npm test
npm run dev
```

Do not commit `.dev.vars`. Do not commit a token or a personal relay URL.
