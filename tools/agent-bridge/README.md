# pixelpal-bridge

Connect **any terminal coding agent** to your PixelPal pet.

```
Claude Code ─┐
agy ─────────┤
Codex ───────┼─ hooks ─→ normalize ─→ POST /events ─┐
OMP ─────────┤                    (AgentEvent)       │
opencode ────┤                                        ├─→ reducer ─→ /agents → 📱 pet
Hermes ──────┘                    (DB poll) ─────────┘
```

One companion. Many agents. The pet reacts to whichever is working, and can
**approve or deny tool permissions from your phone**.

---

## Install

```bash
pipx install pixelpal-bridge    # once, ever
pixelpal-bridge setup           # once, ever
```

`setup` does everything: detects your agents, installs their hooks, starts the
bridge as a **background service** (so it survives reboot — no terminal to
keep open), registers the relay, and prints a **9-character code**:

```
  Pair your phone — type this in the app:

    ┌─────────────────┐
    │   ABC-DEF-GHI   │
    └─────────────────┘
```

Type that code in the app. Done. No IP address, no port, no QR required.

### Why a code, and why 9 characters

The code is the *entire* handshake, so it has to be both typable and strong.

We never encrypt a secret under the code and ship it (an attacker could grind
that offline forever). Instead **both sides derive the relay key from the code**:

```
relayKey = PBKDF2-HMAC-SHA256(code, "pixelpal-relay-v1", 260_000)
```

Firestore only ever holds `HMAC(relayKey, "pixelpal-handshake")`. The code itself
is never transmitted, logged, or stored anywhere.

9 characters from a 27-symbol alphabet with every ambiguous glyph removed
(no `0/O`, `1/I/L`, `2/Z`, `5/S`) = **42.8 bits**, still read-and-typed in one
glance. 6 characters would have been only 30 bits — brute-forceable in ~39
GPU-hours — which is why the code is longer than it looks like it needs to be.

## How the connection works

```
                ┌── same Wi-Fi ──→  direct HTTP (fastest, no third party)
laptop bridge ──┤
                └── anywhere else ─→ Firestore relay (encrypted, rules-scoped)

phone  ─────────────────────────────────────────────→ pet
```

The transport is chosen automatically and silently; you never configure a
network. The UI just says "Connected".

### Relay setup (one-time, only if you want off-Wi-Fi support)

The relay needs the security rules deployed once:

```bash
firebase deploy --only firestore:rules
```

The rules in `firestore.rules` add a `/relays` branch and leave `/users`
untouched. Security properties, each covered by a test in `test/rules/`:

- both sides must be **authenticated**, and must already be named in the document;
- **knowing the document id is not enough** — a third party is denied everything;
- the **phone can answer approvals but cannot forge agent state**;
- the bridge **cannot approve its own requests** (an agent can't self-approve);
- a relay can be claimed by **exactly one phone**.

### Cost

The bridge polls Firestore only as often as it must: **2 s while an approval is
pending** (someone is blocked, so latency matters) and **20 s when idle**
(~4.3k reads/day). Firestore's free tier is 50k/day, so normal use stays inside
it. This is enforced by `next_poll_delay()` and pinned by a test.

## Supported agents

| Agent | Mechanism | Latency | Approve from phone |
|---|---|---|---|
| **Claude Code** | `PreToolUse`/`PostToolUse`/`PermissionRequest` hooks | instant | ✅ |
| **agy** | same hook contract as Claude Code | instant | ✅ |
| **Codex** | same hook contract | instant | ✅ |
| **OMP** | native TypeScript extension (`pi.on(...)`) | instant | ⛔ status only |
| **opencode** | `GET /session` from `opencode serve` | ~1s | ⛔ status only |
| **Hermes** | read-only poll of `~/.hermes/state.db` | ~1s | ⛔ status only |

Latency is on the same Wi-Fi. Off-Wi-Fi (relay) adds roughly one poll interval.

Anything else that can POST JSON can be wired up by hand — see
[Custom agents](#custom-agents).

### Why the mechanisms differ

Each agent exposes a different surface, and the bridge does not pretend
otherwise. Hermes, for example, has no lifecycle event API at all: its
`--accept-hooks` flag only auto-approves *externally declared shell hooks*, it
does not emit events. So the Hermes adapter reads Hermes' own SQLite state
instead — opened **read-only**, so it can never corrupt or lock a running
session. The trade-off is latency (~1s) and no approval support; that limit is
documented rather than hidden.

---

## How it works

### 1. Adapters are dumb translators

Every adapter converts its agent's native payload into one normalized event
and nothing else:

```python
AgentEvent(
    source=AgentSource.CLAUDE_CODE,
    kind=EventKind.TOOL_START,
    session_id="abc",
    project="my-app",
    tool="Edit",
    detail="Main.kt",
    approval_id=None,
    at=1762345678.0,
    id="claude_code:abc:tool_start:Edit:1762345678",
)
```

### 2. One pure reducer owns all the logic

`reducer.py` turns events into `AgentState` — status, message, pending
approval, tool counts. It is pure: same events in, same state out, no I/O. That
is why the whole state machine is testable without spawning an agent, and why
`pixelpal-bridge simulate` can replay a realistic session in one process.

Adding an agent therefore touches **only** an adapter. It can never regress
the state machine.

### 3. The wire

| Endpoint | Method | Purpose |
|---|---|---|
| `/agents` | GET | All agents, attention-ranked (waiting > error > working) |
| `/status` | GET | Single-agent shape (back-compat with older app builds) |
| `/events` | POST | Ingest one `AgentEvent` |
| `/approvals/wait` | POST | **Long-poll** until the phone answers |
| `/approvals/resolve` | POST | Answer an approval |
| `/health` | GET | Liveness + pairing state |
| `/commands` | GET | Command log |

---

## The approval round-trip

This is the feature that matters — unblocking an agent without touching the
laptop:

```
Claude Code                    hook.py                  server            phone
    │                             │                       │                 │
    ├─ PermissionRequest ────────→│                       │                 │
    │  (blocks the tool)          ├─ POST /events ───────→│                 │
    │                             │                       ├─ push ─────────→│ show sheet
    │                             ├─ POST /approvals/wait→│                 │
    │                             │  (long-poll, 600s)    │←── tap Approve ─┤
    │                             │←─ {"decision":"approve"}                │
    │←─ hookSpecificOutput ───────┤                       │                 │
    │   .decision.behavior=allow  │                       │                 │
    ▼                             │                       │                 │
  tool runs                                                                   
```

Two failure modes are handled explicitly, because both bit us during
development and now have regression tests:

- **Process instability**: approval ids use SHA-256, never Python's `hash()`,
  which is randomized per process (`PYTHONHASHSEED`) and would make the hook
  and the server disagree about which request is being answered.
- **Race on a fast answer**: a verdict that arrives before anyone is waiting is
  stored, not dropped.

---

## Safety rules

**A dead bridge must never break an agent.** Every hook:

- gives up after **1 second** on the status POST;
- always **exits 0** — a hook can never fail your session;
- skips work entirely if the bridge isn't listening (0.15 s socket probe);
- only long-polls on `PermissionRequest`, where the agent is blocked anyway;
- can be disabled per-shell with `PIXELPAL_HOOK_DISABLED=1`.

**Never send code.** Adapters send tool names and a short human summary: file
*basenames*, command *first lines* (stripped of boilerplate like `exit code 1`),
prompts truncated to 60 characters. File contents, diffs and full prompts never
leave the machine.

**LAN-only by default.** `pair` generates a key stored at
`~/.config/pixelpal/bridge.json` (mode `0600`); the app sends it as
`X-PixelPal-Key`. Loopback calls skip the key so local hooks need no config.
Without a key the bridge warns that anyone on your network can connect.

---

## Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `PIXELPAL_BRIDGE` | `http://127.0.0.1:8787` | Base URL (OMP extension) |
| `PIXELPAL_BRIDGE_HOST` / `_PORT` | `127.0.0.1` / `8787` | Hook target |
| `PIXELPAL_HOOK_DISABLED` | unset | Set to `1` to disable all hooks |
| `PIXELPAL_APPROVAL_TIMEOUT` | `600` | Seconds a hook waits for your answer |
| `PIXELPAL_ANSWER_APPROVALS` | unset | `deny`/`allow`: answer automatically (CI) |
| `PIXELPAL_VERBOSE` | unset | Log every request |

---

## Custom agents

If your agent can run a command on state changes, it's already supported:

```bash
echo '{"session_id":"1","hook_event_name":"PreToolUse","tool_name":"Bash",
       "tool_input":{"command":"npm test"}}' \
  | python3 -m pixelpal_bridge.hook --source claude_code
```

Or POST directly:

```bash
curl -X POST http://127.0.0.1:8787/events -H 'Content-Type: application/json' -d '{
  "source":"generic","kind":"tool_start","session_id":"s1",
  "tool":"Bash","detail":"npm test","id":"e1"
}'
```

Event kinds: `session_start`, `session_end`, `prompt`, `tool_start`, `tool_end`,
`tool_failure`, `approval_request`, `approval_resolved`, `turn_end`, `error`.

---

## Troubleshooting

Run `pixelpal-bridge doctor` first — it names the broken thing.

| Symptom | Cause |
|---|---|
| Pet stays idle, doctor is all ✓ | Agent started before `install` — restart it |
| `bridge not running` on 8787 | Another process holds the port, or `serve` isn't running |
| Agents appear then vanish | Bridge stopped; last-seen rows are dropped after 3 days |
| Hermes shows stale "working" | Nothing new in 180 s ⇒ reported as idle (DB has no "stopped" event) |
| opencode missing | Start `opencode serve` (default port 4096) |
| Approval prompt never appears | Agent's `PermissionRequest` event isn't firing — check `PIXELPAL_VERBOSE=1` |

---

## Tests

```bash
python3 -m pytest pixelpal_bridge/tests/ -q     # 79 tests
```

Covers the reducer state machine, the Claude adapter against **verbatim
documented payloads**, cross-process id stability, the approval race, auth, and
that `install`/`uninstall` restores a config file byte-for-byte.
