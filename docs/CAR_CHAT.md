# Car chat (Android Auto)

Talk to your agents by voice in the car. Each agent ("Travel Bot", "Work Email Bot") shows up in Android Auto as its own conversation. You dictate a message, it goes through your own relay to your agent, and the agent's answer comes back as a message Android Auto reads aloud.

Car chat uses Android Auto's **messaging notifications**. It does not use the Car App Library and draws no screens in the car, so there is nothing to submit to Google and no Play review. Sideloaded builds work after one Android Auto developer setting (below).

You need your own relay (see [Relay choices](../README.md#relay-choices)) and something on your side that answers messages: a webhook, `abridge car listen`, or an agent with the `car_inbox` / `car_reply` MCP tools.

## How a message moves

1. You say "reply" to a car notification (or start a conversation, see [Starting hands-free](#starting-hands-free)) and dictate.
2. Android Auto hands the text to Agent Bridge. The phone sends `car.say {id, bot, text}` on its car socket (`/v1/room/<room>/car`, device token).
3. The relay acks it, stores it in that room's inbox (last 200 messages, 24 hours), and POSTs it to `CAR_WEBHOOK_URL` if you set one.
4. Your agent answers with `abridge car reply`, the `car_reply` MCP tool, or a POST to the relay's reply URL.
5. The relay pushes `car.reply {bot, text, replyTo?}` to the phone, or queues it (last 50, 24 hours) until the phone reconnects.
6. The phone posts it in that bot's conversation. Android Auto announces it and reads it aloud.

Car messages only carry text. A car message is never parsed as a phone command. Agents can still use the normal phone tools during a drive; those go through the armed session and its usual checks (Arm, kill switch, confirm prompts, denylist, redaction, timeouts or "Stay armed until I disarm").

## Phone setup

1. Install the APK and pair as in the [README](../README.md).
2. In Agent Bridge **Settings**, turn on **Car chat** and allow notifications. A small "Car chat on" notification stays up while it is connected. "Turn off" on that notification stops it.
3. Under **Bot names, one per line**, list the agents you want in the car (default: `Grok Bot`, up to 10). Tap **Save bot names**. The names are what Android Auto reads and what your webhook receives as `bot`.
4. Enable unknown sources in Android Auto, because a sideloaded app's notifications are hidden in the car by default:
   - Open Android Auto settings on the phone (Settings, search "Android Auto").
   - Scroll to **Version** and tap it about 10 times until it offers developer settings. Accept.
   - Open the three-dot menu, **Developer settings**, and turn on **Unknown sources**.
   - Disconnect and reconnect the phone to the car.
5. In Android Auto settings, make sure message notifications are on (**Show message notifications**, and optionally **Show first line of conversations**).
6. Arming is separate. Car chat works while disarmed. Arm only when you want an agent to use the phone screen.

## Relay setup

Car chat needs a relay built from this branch or later. Redeploy yours (`npx wrangler deploy` in `relay/`). No new secret is required: without a webhook, messages wait in the relay inbox for `abridge car listen` or `car_inbox`.

Optional webhook secrets (set with `npx wrangler secret put <NAME>` in `relay/`; never commit them):

| Secret | Default | Meaning |
|---|---|---|
| `CAR_WEBHOOK_URL` | unset (webhook off) | Where each `car.say` is POSTed. Must be `https://` (plain `http://` only for `localhost` / `127.0.0.1`). |
| `CAR_WEBHOOK_KEY` | unset (no auth header) | Secret the receiver checks. |
| `CAR_WEBHOOK_AUTH_HEADER` | `Authorization` | Header that carries the key, for example `x-api-key`. |
| `CAR_WEBHOOK_AUTH_SCHEME` | `Bearer` | Word before the key. Set to `none` to send the bare key. |

With the defaults the header is `Authorization: Bearer <CAR_WEBHOOK_KEY>`.

### Webhook request

```http
POST <CAR_WEBHOOK_URL>
Content-Type: application/json
User-Agent: abridge-relay/car
Authorization: Bearer <CAR_WEBHOOK_KEY>

{
  "type": "car.say",
  "id": "5d0c…",
  "bot": "Travel Bot",
  "text": "find a hotel near the airport for tonight",
  "message": "[Car chat for Travel Bot] find a hotel near the airport for tonight",
  "at": "2026-11-14T16:05:00.000Z",
  "room": "<room>",
  "replyUrl": "https://<your-relay>/v1/room/<room>/car/reply"
}
```

`message` is a single readable line for receivers that take one text field. Any 2xx counts as delivered. On failure or a 10-second timeout the phone says "I couldn't reach your agent" in the first bot's conversation; the message stays in the relay inbox either way. The relay logs only the status code, never the URL, key, or text.

### Replying

From the box (uses `~/.config/abridge/config.json` or the `ABRIDGE_*` env vars; nothing is printed except the result):

```bash
abridge car reply --bot "Travel Bot" "Booked the Hyatt, confirmation is in your email."
abridge car reply --bot "Travel Bot" --reply-to <id> "Done."
abridge car listen            # prints one JSON line per car.say, polls every 3 s
abridge car inbox --after 41  # one-shot read
```

In MCP (`abridge mcp`): `car_reply {bot, text, replyTo?}` and `car_inbox {after?}`.

Over HTTP, from anything holding the room's agent token:

```bash
curl -X POST "$REPLY_URL" -H "Authorization: Bearer $ABRIDGE_AGENT_TOKEN" \
  -H 'content-type: application/json' -d '{"bot":"Travel Bot","text":"Booked."}'
# -> {"ok":true,"delivered":"live"}   or "queued" when the phone is offline
```

The car commands use plain HTTPS. They never open the agent WebSocket, so they do not replace a running `abridge mcp` session.

Write replies for the ear: short sentences, no links, no tables. For long jobs, send a quick "On it" first.

## Starting hands-free

Replying to an existing conversation always works: Android Auto reads the message and offers "Reply". Starting a new conversation by voice is less certain. Agent Bridge does everything Android currently documents for it:

- One **long-lived conversation shortcut per bot**, with a `Person`, the conversation category, and a `actions.intent.CREATE_MESSAGE` capability binding for the bot name.
- An App Actions `shortcuts.xml` capability for `actions.intent.CREATE_MESSAGE` (`message.recipient.name`, `message.text`), so "Hey Google, send a message to Travel Bot on Agent Bridge" can resolve to the app.
- A share target, so text shared from any app can go to a bot conversation.
- When Android Auto connects (a projected head unit or a native car), each bot gets a silent "Talk to {name}." notification with Reply and Mark as read. It is posted once per connection, with no sound, and it is skipped if that bot already has unread lines. Tap it and say your message. This is the reliable path. Android Auto only shows notifications that arrive during the current session, so the prompt is posted on connect rather than when Car chat turns on.
- After you mark the conversation read, or after you send a message and nothing is waiting, that silent "Talk to {name}." line stays up while the car is still connected, so you can start another message. Previously read lines are added with `addHistoricMessage`. Android Auto reads only the new lines (`addMessage`).
- Unplugging the car removes those standing prompts. Unread replies stay. Turning Car chat off in Settings, or Turn off on the "Car chat on" notification, clears the car notifications.

Caveats:

- Google decides which third-party apps the Assistant or Gemini will send messages through, and that has changed over time. On some phones it will answer "I can't send messages with that app yet" or try Google Messages instead. App Actions are only fully live for Play Store apps; a sideloaded app may need the App Actions test tool in Android Studio, or may not be matched at all.
- If the voice start does not work, use the "Talk to {name}." notification, or add an Android Auto launcher shortcut: Android Auto settings, **Customize launcher**, **Add a shortcut to the launcher**, **An Assistant action**, with a command like "send a message to Travel Bot on Agent Bridge".
- A launch the system reports as coming from Google's assistant or Android Auto sends right away. Any other app that opens the compose entry point gets an on-phone "Send to …?" confirmation, so another app cannot put words in your mouth.

## Testing without a car

Google's Desktop Head Unit (DHU) emulates a car screen on a computer:

1. In Android Studio's SDK Manager, install **Android Auto Desktop Head Unit Emulator** (SDK Tools).
2. On the phone: Android Auto developer settings, **Start head unit server**.
3. `adb forward tcp:5277 tcp:5277`, then run `desktop-head-unit` from `$ANDROID_HOME/extras/google/auto/`.
4. Send a reply with `abridge car reply --bot "Grok Bot" "Hello from the box"` and watch it appear. Type `mic reply` style commands in the DHU console, or click the notification and use the on-screen reply.

## Limits

- Delivery is best effort. A reply sent while the car socket is half-open can be lost; the relay keeps queued replies for 24 hours and the inbox for 24 hours.
- One phone per room, so one car chat per room. The car socket replaces an older car socket.
- The relay sees message text. There is no end-to-end encryption yet.
- Android may stop the car chat connection under heavy battery saving. Exempt Agent Bridge from battery optimization (Settings has a button).
- Debug APKs from different machines are signed with different keys. Installing one over another fails; uninstall first and pair again.
