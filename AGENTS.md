# AGENTS.md

> **DOC STATUS (audited against code 2026-11-04):** everything below was verified
> by a full code audit (178 Kotlin files, ~18.5K lines) on this date. Where any
> prose doc (README, old AGENTS.md, comments) conflicts with code, **code is
> truth**. The exhaustive audit — ranked defect list with file:line, dead-code
> inventory, doc-drift list, environment quirks — lives in **MEMORY.md**.
> When you change code, update the relevant section here + MEMORY.md.
>
> **2026-11-04 remediation pass:** P0 data-safety fixes shipped (sync tombstones,
> buffered cloud inserts, offline push retry, server-stamped LWW, widget goAsync,
> boot missed-reminder catch-up), plus task due-dates, reminder categories/silent
> sound, task-delete undo, speech-bubble actions, System theme, R8/CI/crash-logger,
> dead-code sweep and a strings.xml foundation. See MEMORY.md §2b for every new
> component and shape.
>
> **2026-11-04 multi-agent pass (second):** universal bridge `tools/agent-bridge`
> (Claude Code / agy / Codex / OMP / opencode / Hermes → one envelope), the
> phone-side approval gate, Room `agent_sessions`, the ConnectedAgents hub,
> WebSocket socket-ownership fix, and the Gemini health-ping de-billing. This
> supersedes the earlier "Batch 2 out of scope" note.

## Project

PixelPal — Android app: a pixel-art **floating pet that monitors your AI coding
agents** (Gemini / generic HTTP / WebSocket), plus companion mechanics (bond,
tasks, subtasks, reminders, personality, activity), Firebase cloud sync, and
two home-screen widgets. Kotlin 2.0 + Jetpack Compose, single Gradle module
`:app` under `app/`. Root project name `PixelPal`.

## Build & Run

- **Build**: `./gradlew :app:assembleDebug` (first cold build ≈ 16 min; later builds are warm)
- **Install/device**: `./gradlew :app:installDebug`
- **Unit tests**: `./gradlew :app:testDebugUnitTest`
- **Instrumented tests**: `./gradlew :app:connectedDebugAndroidTest` (migration/DAO/nav smoke)
- **Versioning**: debug builds derive `versionCode`/`versionName` from git
  (`900000+commitCount`, `dev-<describe>`); **release builds fail** without
  `-PPIXELPAL_VERSION_CODE/-PPIXELPAL_VERSION_NAME` **and** the release signing
  block (`-PRELEASE_STORE_*`, see RELEASING.md). Unsigned R8-only checks use
  `-PPIXELPAL_RELEASE_PRECHECK=true` (what android.yml passes).
- **Repo slug**: the in-app install one-liner comes from `BuildConfig.REPO_SLUG`
  (`-PPIXELPAL_REPO_SLUG`, default `riddhibantia/PixelPal`) via
  `util/BridgeSetup` — no GitHub URLs are hardcoded in screens.
- **Gemini key**: embedded in debug only; release ships an empty
  `BuildConfig.GEMINI_API_KEY` unless `-PPIXELPAL_EMBED_GEMINI_KEY=true`.
  Multi-machine release ops live in `docs/OPERATIONS.md`.
- **Clean**: `./gradlew clean`
- No lint/typecheck/formatter beyond the standard Gradle build.
- Gemini key: `GEMINI_API_KEY` in `local.properties` → `BuildConfig.GEMINI_API_KEY` (optional; app falls back to local dialogue when missing; release builds ship it **empty** unless `-PPIXELPAL_EMBED_GEMINI_KEY=true`).

## Tech stack (verified)

Kotlin 2.0.0, JVM 17, AGP 8.5.0, **KSP** for Room+Hilt, Compose BOM + Material 3 +
Navigation Compose, WorkManager + hilt-work, **Room v16**, DataStore (Preferences),
Firebase Auth + Firestore (Credential Manager for Google sign-in),
Google Generative AI SDK (Gemini `gemini-1.5-flash`), OkHttp, Lottie Compose,
CameraX + ML Kit barcode scanning (QR pairing), Coil (+gif), Timber.

## Architecture: ONE COMPANION

**A single active companion** — exactly one `companions` row; everything
(tasks, reminders, subtasks, bond, personality, agent connection, activity,
overlay) keys off it via `companionId`. Never reintroduce multi-companion UI.
`domain/engine/ActiveCompanionManager.kt` is THE authority
(`activeCompanion` = primary row via `companionRepository.getPrimary()`).
Legacy `active_companion_id` DataStore pref survives only as fold input +
repair target.

`data/local/datastore/CompanionBootstrapInitializer.kt` runs startup
reconciliation (both steps DataStore-flag-guarded, idempotent):
1. one-time SingleCompanionFold — picks primary (stored id → favorite → most
   recent), moves *pending* tasks / *pending* reminders / activity / agent
   connection (only if primary lacks one) from extras, deletes extras;
2. fresh-install seeding (name from prefs, `rabbit`→**panda** swap),
   bond/personality row guarantees, pref re-point.

## Database — Room **v16** (`PixelPalDatabase.kt`)

Entities: CompanionEntity (species/color/pattern appearance, hat/outfit/accessory,
isFavorite/isArchived), BondEntity (PK=companionId; level/tapsToday/streaks),
PersonalityEntity (6 float traits), TaskEntity (+description, photoUri),
SubtaskEntity (checklist rows under a task, sortOrder),
ReminderEntity (hour/minute, soundUri, recurrence ONCE/DAILY/WEEKLY/MONTHLY +
interval, category, status PENDING/TRIGGERED/COMPLETED/SNOOZED/DISMISSED,
snoozeCount; FK companionId **SET NULL** — others CASCADE),
AgentConnectionEntity (PK=companionId, merged config+status, commandUrl),
**AgentSessionEntity** (PK=`"${source}:${sessionId}"`, one row per live agent
session from the bridge — observed state, NOT configuration),
ActivityEventEntity (typed, isRead).

Conventions & facts:
- **Cloud identity**: `cloudId` (UUID) keys Firestore docs; Room autoincrement
  ids are device-local and must never key cloud docs. `updatedAt` = LWW timestamp.
- Migration chain (all hand-written in `DatabaseMigrations.kt`,
  registered in `di/DatabaseModule.kt`, destructive-on-downgrade):
  1_3/2_3 reminder fields → 3_4 reminder rebuild → 4_5 **companions** + re-key
  all tables → 5_6 activity isRead → 6_7 appearance columns + merge
  agent_config+agent_status → agent_connection → 7_8 cloudId/updatedAt on
  tasks/reminders/companions/bond → 8_9 **subtasks** → 9_10 task description →
  10_11 commandUrl → 11_12 perf indexes → 12_13 tasks.photoUri → 13_14
  DAO-matching composite indexes → **14_15 `agent_sessions`** → **15_16
  reminders.recurrenceDayOfMonth** (monthly series anchor).
- The Room version lives ONLY in `PixelPalDatabase.kt` (v16). The old
  `Constants.DATABASE_VERSION` was removed in the 2026-11-04 pass.

## Domain engines

- **BondEngine** — single mutation path `mutateLevel()`: task +2, reminder +3,
  tap +1 but only first `BOND_GRANTING_TAPS_PER_DAY`(3)/day grant level (max
  10 taps/day recorded); feed/play cosmetic (streak/recency only). Level cap
  100; activity events at level%5==0 and streaks {3,7,14,30,60,100}. Decay:
  −1 level per **calendar day** inactive (`ChronoUnit.DAYS` between local dates,
  so a phone off for a month loses a month and a retried worker can't double
  subtract), resets daily tap/feed counters.
  Streak: +1 when last interaction ≤1 day ago, reset to 1 after ≥2-day gap
  (`yyyyMMdd` keys). `bond` Flow follows the primary; `getBondDirect(id)` elsewhere.
- **EmotionEngine** — maps Emotion→AnimationState with timed decay to CALM
  (default 30s, reminders 60s).
- **AnimationEngine** — global pet state machine: blinks, random idle
  micro-animations (WAVE/WALK/JUMP/EXCITED), screen-off ⇒ SLEEP (state var fed
  by callers; idle loop self-heals stale sleep).
- **PersonalityEngine** — daily recalc nudges 6 traits from interaction stats
  (see Known defects: Worker currently fabricates stats).
- **CompanionReactionProvider** — line priority: (1) live-state text
  ("N tasks left", 50% on tap), (2) **Gemini** (4s timeout, dominant-trait
  persona, 180-char cap), (3) local DialogueLoader packs, (4) hardcoded fallback.
- **CompanionEngine** — orchestrates tap/double-tap/feed (bond + emotion +
  speech bubble, bubbles only when overlay visible) and reminder-triggered
  Dynamic Island actions.

## AI Agent connection (the core feature)

- Providers (normalized string on AgentConnectionEntity): `generic` |
  `gemini` | `websocket` (`AgentProviders.normalize`; unknown → generic).
- Routing in `AgentConnectionRepositoryImpl.checkNow()`: gemini → a **local,
  free** readiness check (no API call — the old billable `generateContent`
  health-ping is gone; a real round-trip only happens via explicit
  `GeminiAgentConnector.verifyNow()`); blank endpoint → DISCONNECTED;
  websocket provider or `ws://` URL → `WebSocketAgentConnector.checkOnce`
  (12s timeout; open-but-silent → ONLINE); else HTTP poll.
- HTTP envelope contract: `{status, message?, currentTask?, progress?,
  pendingApproval?}` (kotlinx.serialization, ignoreUnknownKeys; progress
  clamped 0–100). Error mapping: blank URL → DISCONNECTED; non-HTTPS → ERROR
  **except** loopback / 10.0.2.2 / localhost / RFC1918 LAN (private-LAN http
  allowed on purpose for laptop agents); HTTP≠2xx → ERROR; IOException →
  OFFLINE; malformed JSON → ERROR (not OFFLINE). Poll cadence = WorkManager,
  never the connector.
- AgentState mapping: `FAILED`→ERROR, `STOPPED`→IDLE, unknown non-blank →
  ERROR (surfaced as needs-attention, deliberately).
- **Approval gate**: envelope carries `pendingApproval {id, action, detail?}`;
  notify **once per id** (DataStore `last_approval_id_<companionId>`),
  Approve/Deny notification actions → `AgentApprovalReceiver` →
  `respondToApproval()` POSTs `{approvalId, decision}` to `commandUrl`
  (fallback endpointUrl). Notification always cancelled afterwards.
- Activity + notifications: `AGENT_STATUS_CHANGED` on state change, "Agent
  started <task>" on task change; notification only when state changed AND
  `needsAttention`.
- Polling: `AgentStatusWorker` periodic, unique name `agent_poll_<id>`,
  15-min floor (choices 15/30/60/120), CONNECTED + battery-not-low, ≤2 retries;
  re-armed at app start for every connection with `pollingEnabled` and on
  save via `SaveAgentConnectionUseCase`.
- Gemini: key from BuildConfig or DataStore override; `isUsableKey` rejects
  placeholders; streaming chat (`streamChatResponse`) with personality-injected
  system instruction; `generateReply` returns null → caller falls back.
- `sendCommand()`: gemini → generateReply (reply shown in workspace); ws →
  live socket send; else POST `{"command"}`.

## Multi-agent bridge (`tools/agent-bridge`) — the universal connector

Any terminal coding agent can drive the pet through **one** integration, so a
new agent never needs an app release.

```
Claude Code / agy / Codex ── hooks (JSON stdin) ─┐
OMP ── native TS extension ─────────────────────┤→ AgentEvent → reducer
opencode ── GET /session ───────────────────────┤   (pure, all logic)      → /agents → app
Hermes ── read-only ~/.hermes/state.db ─────────┘
```

- **Adapters are dumb translators**: each converts a native payload into one
  normalized `AgentEvent(source, kind, session_id, project, tool, detail,
  approval_id, at, id)`. Adding an agent touches only an adapter.
- **ALL logic lives in `pixelpal_bridge/reducer.py`** (`AgentRegistry`,
  `reduce_event`, `decay`). It is pure, so `pixelpal-bridge simulate` replays a
  realistic session with no agent installed. Do not put state rules in adapters.
- **Wire**: `GET /agents` (attention-ranked multi-agent), `GET /status`
  (back-compat single shape), `POST /events`, `POST /approvals/wait`
  (long-poll), `POST /approvals/resolve`, `GET /health`.
- **Approval round-trip** is the OS-level feature: `PermissionRequest` hook →
  `/approvals/wait` → phone taps Approve → hook prints
  `{"hookSpecificOutput":{"hookEventName":"PermissionRequest",
  "decision":{"behavior":"allow"|"deny"}}}`. Approval ids are **SHA-256**,
  never `hash()` (which is salted per process — this was a real bug).
- **Hard rule: a dead bridge must never slow or hang an agent.** Hooks: 1 s
  POST timeout, 0.15 s socket probe, always exit 0,
  `PIXELPAL_HOOK_DISABLED=1` kill switch.
- **Redaction**: tool names + short human summaries only (basenames, first line
  of commands, prompts ≤60 chars). File contents and diffs never leave the box.
- **Installer is additive and reversible**: appends a marked group, writes a
  `.bak-pixelpal-<ts>` backup first, `uninstall` removes only its own entries.
  Never clobber the pre-existing tooling (`~/.claude/helpers/graft-hooks.cjs`,
  `~/.orca/agent-hooks/*.sh`, moshi-hook, OMP's `orca-agent-status.ts`).
- **Hermes has no lifecycle event API** (`--accept-hooks` only gates *shell*
  hooks) → its adapter polls `~/.hermes/state.db` read-only and downgrades
  `WORKING` to `IDLE` after 180 s of no rows. Documented limitation, not a bug.
- App side: **`AgentBridgeSync`** is the single "fetch → mirror into Room" path
  (direct only — the Firebase relay was removed 2026-11-05, see below); a
  **bridge that is unreachable returns `reachable = false` and leaves existing
  rows alone** (an asleep laptop must not erase the pets). It also refreshes the
  home widget when the session set actually changes.
- **Polling has an owner outside the UI**: `AgentBridgePoller` runs for the life
  of the app process (4 s while an approval is pending, 20 s idle) and
  `AgentBridgeWorker` covers process death at WorkManager's 15-min floor
  (`pixelpal_bridge_poll`, scheduled on app start and on pairing, cancelled on
  unpair). `BridgeApprovalNotifier` diffs the pending-approval set and posts one
  Approve/Deny notification per approval id — deduped through the
  `notified_bridge_approvals` pref — and cancels asks that resolved elsewhere.
  Notification verdicts come back through `AgentApprovalReceiver`'s
  `SOURCE_BRIDGE` branch → `AgentBridgeTransport.respondToApproval`.
- **Every agent surface reads `agent_sessions`**: Home's card and Today chip
  (`HomeViewModel.AgentSlice`) and the home widget. The legacy
  `agent_connection` row is configuration only, never shown as status.
- **Pairing URIs validate the host**: `parsePairingUri` / `isAllowedHost` (in
  `ConnectedAgentsViewModel`) accept loopback, RFC1918 LAN, **Tailscale's
  `100.64.0.0/10` + `*.ts.net`, and `*.local`** — everything else is rejected.
  The Agents screen consumes the scanner result (`scannedUrl` on its back-stack
  entry) — it used to be dropped, so scanning from the Agent tab did nothing.
- `setup` installs hooks, saves the LAN key/token, (re)installs the daemon and
  prints the address to paste into the Agent tab (LAN URL, or the Tailscale
  hostname when `tailscale status` reports Running). It also picks the first
  free port when 8787 is taken. The systemd unit runs `sys.executable`, not
  `/usr/bin/python3`.
- CLI: **`setup`** (one command: hooks + daemon + address) plus
  `serve | install | uninstall | doctor | pair | status | simulate`.
  Config at `~/.config/pixelpal/bridge.json` (mode 0600).

### Direct connect (2026-11-05 — the Firebase relay is GONE)

- **Decision (do not re-litigate):** the relay was deleted on both sides. It
  cost ~17,280 Firestore reads/user/day against a 50k/day free tier (broke at
  ~2 users) and required a manual rules deploy before it worked at all.
  **Off-Wi-Fi is Tailscale's job now**, not a Firestore mirror.
- **Install is one line, no repo needed:** `curl -fsSL
  https://github.com/riddhibantia/PixelPal/releases/latest/download/install.sh
  | bash` — resolves the newest release, downloads its bridge tarball (pure
  stdlib), verifies it against the release's `SHA256SUMS`, extracts to
  `~/.local/share/pixelpal-bridge` and symlinks `pixelpal-bridge` +
  `pixelpal-hook` into `~/.local/bin`. `install.sh` is committed at the repo
  root AND staged into every release, so the URL resolves two ways. It can be
  skipped entirely: the tarball's root holds `pyproject.toml`, so
  `pipx install <tarball>` works too.
  ⚠️ **Requires a published release.** If `install.sh` 404s or the release has
  no `pixelpal-bridge-*.tar.gz` + `SHA256SUMS` asset, the workflow has never
  run — add the four signing secrets and push a `v*` tag first.
- **Python side deletions:** `relay.py`, `relay_crypto.py`, `test_relay*.py`;
  `cli.py` lost `_start_relay()`, `_print_code()`, `--no-relay`,
  `--google-services`; gained `_tailscale_hostname()` (parses
  `tailscale status --json`). Pairing key is `secrets.token_hex(32)`.
  103 tests pass.
- **Kotlin side deletions:** `RelayCrypto.kt`, `BridgeRelayClient.kt`,
  `CodePairingCard.kt` + their tests; `AgentBridgeTransport` is direct-only
  with `Snapshot(agents)` (renamed from `Result` — it collided with
  `kotlin.Result`); `ConnectedAgentsViewModel` lost `pairWithCode`/`usingRelay`;
  `PreferencesManager`/`Constants` lost `AGENT_RELAY_ID`; `firestore.rules`
  lost the `/relays` branch (and `test/rules/` + `DEPLOY-RULES.md` are gone).
- `serve` still publishes `/agents` on change + 60 s heartbeat and long-polls
  `/approvals/wait` — that machinery never depended on the relay.

## Cloud sync (Firebase)

- **Auth** (`FirebaseAuthManager`): anonymous guest, email/password (+reset),
  Google via Credential Manager (12500 → SHA-1 hint). `authStateFlow` drives
  session: `PixelPalApplication` starts realtime sync + calls
  `syncCoordinator.onUserAuthenticated()` on restore (pull BEFORE UI reads
  Room so cloud wins over fresh seeds); AuthViewModel awaits
  `onUserAuthenticated()` before navigating.
- **Paths**: `users/{uid}/companion/primary`, `users/{uid}/tasks|reminders|
  subtasks/{cloudId}`, `users/{uid}/metrics/bond`; writes `.set(..., merge)`;
  deletes by cloudId; push = batched 400-write chunks; realtime = snapshot
  listeners. Firestore persistent cache **capped at 100 MB** (not unlimited).
- **Conflict rules live in `SyncPolicy`** (`data/remote/firebase/SyncDecision.kt`):
  pure, unit-tested, single source of truth. `effectiveUpdatedAt(writerUpdatedAt,
  serverTsMillis)` = max of device clock and Firestore's `syncServerTs`
  (`FieldValue.serverTimestamp()` written with every payload via `toMap()`).
  Never re-implement LWW/tombstone logic in the engine or coordinator.
- **Deletes are tombstones (soft deletes)**: docs get `deleted = true` merged in a
  transaction guarded by `canPushOverExisting`; absence is NEVER treated as
  deletion. A refused push means the cloud holds a newer tombstone — listener
  paths mirror that locally. This is what stops a device resurrecting rows.
- **Write payloads**: `Firestore*.toMap()` in `FirebaseModels.kt` (excludes
  `@DocumentId`, drops nulls). Keep each map in sync with its data class — a
  missing key silently stops that column syncing.
- **FirestoreSyncCoordinator**: pins current uid; 3 realtime listeners
  (tasks/reminders/subtasks) apply remote rows **through DAOs directly — never
  through repositories** (avoids push echo). `fullSync` = epoch snapshot →
  `pullAndApplyCloud` → epoch re-check → push all local. Conflict rule =
  last-write-wins on `updatedAt`, enforced **only cloud→local**
  (companion/bond additionally: fresh seeds always lose to cloud);
  local→cloud pushes unconditional. `signOutSuspend` returns Boolean and
  **fails closed**: epoch++ (aborts in-flight sync), cancel listeners, wipe ALL
  local tables **before** auth sign-out (cross-account leak guard); if the wipe
  throws twice the sign-out is aborted and the error goes to `lastSyncError`
  instead of handing the next account the previous user's rows.
- `FirestoreSyncWorker`: hourly periodic + one-shot "immediate", CONNECTED;
  no-op when logged out, retry on failure. Profile → "Sync Now" = fullSync.

## Presentation map

- **Navigation** (16 plain string routes, fade+slide): onboarding, auth, home,
  reminders, create_reminder, customize, settings, profile, overlay_settings,
  about, permissions,
  **connected_agents** (the "Agent" bottom-tab — bridge sessions plus collapsed
  Gemini/HTTP/WebSocket provider setup), tasks,
  new_task, task_detail/{taskId}, activity_center, qr_scan.
- **Onboarding is 5 pages** (welcome, species, name, overlay permission, and a
  "Watch Your Agent Work" page with the one-line `install.sh` curl +
  `pixelpal-bridge setup` + a copy button; the setup output is a
  `pixelpal://pair?...` payload (includes host, port, and key), which can be
  scanned as terminal QR when optional `qrcode` is installed or pasted into
  Agent tab; `CONNECT_COMMANDS` in
  `OnboardingScreen.kt`). The one-liner resolves the newest **published
  release**, so it only works once a `v*` tag has produced one — it is not a
  raw-branch URL. Overlay permission state refreshes when returning from system
  settings, and the onboarding species preview follows the selected pet.
  Launcher/splash art is the real companion art, not a hand-drawn mark:
  `res/drawable/splash_icon.xml` and `ic_launcher_foreground.xml` are frame 0
  of `res/raw/pet_cat_idle.json` baked to flat vector paths (cat = default
  species). Android 31+ shows it via the SplashScreen API; older versions
  center it at 168dp on the obsidian launch window. Rebake from the Lottie
  JSON for another species (`tools/lottie_bake.py` fit/size/art-height
  options); the icon carries its own padding, so change `art-height` rather
  than scaling the whole canvas.
- **Start destination** (MainActivity): first-launch → onboarding; else no
  Firebase user → auth; else home. Reactive sign-out watcher resets to auth
  (`popUpTo(0)`). Overlay service restarted at activity start when enabled.
- **HomeScreen**: greeting + bell (unread badge) + gear, hero pet card,
  AgentStatusCard, Today chips, overlay row. Perf-sliced StateFlows
  (`headerSlice/heroSlice/...`) — collect those, per-section, so background
  polls don't recompose the Lottie hero (the old aggregate `uiState` was removed).
  No Feed/Play row; feed is reachable via overlay long-press only.
  **2026-11-05 de-slop pass (impeccable critique, 23/40 → fixes):** the
  greeting/tagline stack is replaced by the two-tone wordmark + ONE live
  pet-voice line (`{name}, N tasks left` / agents-attention first, driven by
  `todaySlice`+`agentSlice`); the fabricated Focus-time module is DELETED
  (`FocusTimerEngine`, `HomeFocusTimeCard`, `FocusCircularProgress`, its test —
  all removed; `HomeViewModel` no longer injects the engine); card subtitles
  are stripped (Chat/Workspace cards, streak, agent card shows `detail` only
  when real); Today row lost its fake checkboxes; stats are numeric ("1"/"0",
  never "None"); "Interact"→"Chat", "Task Manager"→"Tasks". Empty states are
  pet-first: `EmptyState` pet is 180dp, tasks-empty = SLEEP, pairing state has
  the pet + real `SecondaryButton`s (no bare TextLinks). Reminders FAB hides
  while the list is empty (single create-affordance).
- **ConnectedAgentsScreen** (`presentation/screens/agents/`) = the **Agent tab**
  now: one row per live agent (status dot, message, project, "12s ago"), with
  **inline Approve/Deny** when an agent is `WAITING_FOR_INPUT`; pairing is one
  "Scan pairing code" action. The scanned bridge URL/key are verified against
  `/agents` before being stored. Successful inline decisions are recorded in
  Activity Center; failures keep the request visible and show retry guidance.
  Refreshes via `repeatOnLifecycle(RESUMED)` — no
  timers, no wake locks. Empty states distinguish "no bridge paired" from
  "bridge paired but no agents running".
- **Agent tab** is the single agent surface: bridge sessions and approvals lead;
  a collapsed **Custom provider** section retains Gemini/HTTP/WebSocket setup,
  polling controls, commands, Gemini key override, disconnect+Undo, and QR
  endpoint scanning. Home's Agent card opens this same hub.
- Home surfaces live agent status and approvals directly below the greeting.
  Agent setup is unified in the Agent tab; Home's Agent card opens it directly.
- The Permissions screen explains Camera (QR pairing), notifications
  (approvals/reminders), overlay, and exact-alarm purposes. Notification
  permission is not prompted on every MainActivity creation.
- Tasks/TasksDetail: subtask checklist, photo proof (photoUri via GetContent —
  note: no takePersistableUriPermission), **due dates** (picker + relative labels
  via `TaskDueDateSection`/`TaskDueFormat`, notification via `TaskDueWorker`),
  swipe-delete **with Undo** (`SnackbarEvent.TaskDeleted` → reinsert).
- Reminders: global lists (not companion-filtered); 3 time modes; **repeat
  picker** (Once/Daily/Weekly/Monthly — until 2026-11-04 the UI always wrote
  `ONCE`, so recurring reminders did not exist); **category
  picker** (`ReminderCategory` + icon + filter chips) and a message field;
  cancelling the exact-alarm flow now ABORTS the save; **Silent is a real mode**
  (`ReminderSound.NONE`, stored as a `soundUri` sentinel — `null` still means
  default for backward compatibility). Monthly series store
  `recurrenceDayOfMonth`, and all recurrence math lives in the pure
  `domain/engine/RecurrenceMath.kt` (unit-tested: month-end clamp, DST, jump).
- Customize: **species picker + app theme only** (color/pattern pickers do
  not exist); name field writes DB per keystroke.
- ActivityCenter: bell target, live agent banner, markAllRead on open; DAO
  query excludes TAP/FEED legacy rows.
- AuthScreen: Google/email/guest + reset; nav gate = authenticated AND
  isDataReady AND syncError==null (retry / Continue-offline on sync failure).
- **Strings**: full migration complete across every screen and shared
  component. Layout: `values/strings.xml` (manifest/widgets + About +
  ThemePicker, the original) plus per-area files `strings_home.xml`,
  `strings_agents.xml`, `strings_tasks.xml`, `strings_settings.xml`,
  `strings_auth.xml` — all following the `<area>_<feature>_<element>`
  convention documented in `strings.xml`'s header; positional `%1$s`/`%1$d`
  placeholders, safe apostrophe/quote escaping. Deliberate non-resources
  (identifiers, not copy): route names, DataStore keys, stored enum ids
  (`ONCE`/`DAILY`…), species/appearance ids, scheme strings, `pixelpal://pair`
  payloads, format patterns, and text produced by non-composable helpers —
  `TaskDueFormat`, `formatAge()`/`relativeTimestamp()` relative-time strings,
  `RepeatOption` display labels (enum constructor), the two ViewModel-held
  `AuthViewModel`/`ConnectedAgentsViewModel` message corpora (ViewModels were
  out of extraction scope; `stringResource` is composable-only), and
  `onBackPressed`-era semantics lambdas in onboarding step labels. Those
  remaining non-composable corpora are the only open string work.
- Components: LottiePetView (raw `pet_{type}_{state}.json`, process-wide
  res-id cache, draw-phase progress), PetRenderer wrapper, PixelPalBottomBar
  (5 tabs), PixelPalSnackbarHost/SnackbarEvent (Undo flows), settings kit
  (SettingsRow/Group…), ThemePicker (dark/light/spring/system/autumn — "system"
  uses dynamic color on API 31+ with a fallback below), glass kit
  (`GlassCard`, `GlassChip`, `GlassSegmentedRow` — the redesign's quiet-chrome
  language; chips = pill, selected = violet gradient; EmptyState pet = 180dp).

## Overlay subsystem

- `OverlayService` — foreground `specialUse` ("Screen Companion Overlay"),
  START_STICKY, notification 1001; combines (overlayEnabled, primary, app
  foreground) → syncs sessions; parks bubble while app foregrounded (lifecycle
  callbacks in Application). Starters: MainActivity, HomeViewModel,
  OnboardingScreen, SettingsViewModel, BootReceiver.
- Cap: `MAX_SIMULTANEOUS_OVERLAYS = 1` (soft — desired set only ever contains
  the primary; slot-stagger system is vestigial/no-op).
- **Show/hide race guarded by `showToken`**: `show()` suspends on the position
  read before `addView`, so a `hide()` in that window bumps the token and the
  pending `addView` aborts instead of leaking an unowned window.
- Touch: 500ms long-press = feed, 300ms double-tap = excited + open app,
  short tap = speech bubble with live agent status. Drag persists per-companion
  (`overlay_x_<id>`/`overlay_y_<id>`; legacy global fallback; defaults 80,200).
- `SpeechBubbleOverlayView` — typewriter 25ms/char, 4s auto-dismiss, anchored
  to the pet; `showActions()` is wired for reminder **Complete / Snooze 10 min**
  via the `OverlayManager.showSpeechBubble(...)` actions overload.
  `DynamicIslandView` — single global instance: reminder capsule top-center,
  tap expands, swipe right = complete / left = snooze 10 min.
- **Keyboard dodge**: overlay windows get no IME insets, so
  `KeyboardAccessibilityService` (window-state events) measures the input-method
  window → `KeyboardStateManager` (StateFlow<Int>) → `OverlaySession`
  lifts the pet above the keyboard. Do not "simplify" this away.
- Screen-off sleep is handled by AnimationEngine state (the old, never-registered
  `ScreenStateReceiver` was deleted).

## Workers / receivers / widgets

- `WorkerScheduler.schedulePeriodicWork()`: BondDecayWorker daily,
  PersonalityWorker daily, FirestoreSyncWorker hourly (all UPDATE policy).
- **`CloudPushScheduler`** is the only way to enqueue a retryable entity push:
  unique work per `(type, cloudId)`, `KEEP` policy, CONNECTED constraint,
  exponential backoff. Repos keep an instant fire-and-forget push for UI
  snappiness; the worker covers offline. Applies to tasks, reminders, subtasks.
- **`BootCatchUpWorker`**: at boot, past-due ONE-SHOT reminders are marked
  missed (`ActivityType.MISSED_REMINDER`), one aggregated notification is posted
  (id 1005), and recurring ones re-arm to their next occurrence.
- **`TaskDueWorker` + `TaskDueScheduler`**: per-task due-date notification,
  unique `task_due_<cloudId>`, channel `CHANNEL_TASK_DUE`, id 1100; rescheduled on
  edit, cancelled on complete/delete.
- Reminder pipeline: create → `ReminderScheduler` (exact
  `setExactAndAllowWhileIdle` when allowed; inexact fallback incl.
  SecurityException; requestCode = reminder.id) → `AlarmReceiver` (goAsync, IO:
  ringtone ≤5s + vibra + full-screen notification → Complete/Snooze-10min via
  `ReminderActionReceiver`) → advance: recurring re-armed, one-shot → TRIGGERED.
  `rescheduleAll` runs only from `BootReceiver` (also restarts overlay).
- Widgets (EntryPointAccessors via Hilt): HomeWidget (companion/streak/tasks/
  next reminder/agent line) and TasksWidget (6 rows + toggle intents). Their
  RemoteViews use the app's lilac/violet palette, rounded checklist rows, and
  a violet checked state; keep widget colors in sync with the Compose theme.
  TasksWidget toggles use **`goAsync()` + `CompleteTaskUseCase`** — the same
  bond/activity path as the app (no duplicated bond math). Read-only queries may
  still touch the DB directly.

## DI modules (SingletonComponent)

- `AppModule`: DataStore + OkHttpClient (10s timeouts, debug logging).
- `DatabaseModule`: Room v16 + migration chain + DAO providers.
- `FirebaseModule`: FirebaseAuth, FirebaseFirestore (100MB persistent cache).
- `RepositoryModule`: binds 8 repos; `@AgentConnector` ← GenericHttpAgentConnector
  only (gemini/ws injected as concrete types, routed manually).

## Dialogue & assets

- `data/dialogue/DialogueLoader.kt`: 5 raw JSON packs
  (`dialogue_{reminders,reactions,greetings,general,bond}`) →
  DialogueLine{id,text,emotion,context,minBond,maxBond,personality}; filter =
  context + emotion + bond range + not-in-last-20-used; empty match → random
  same-context (ignores filters; not recorded as recently used); `{var}`
  substitution.
- `res/raw`: 89 files = **7 species (cat,dog,panda,bunny,whale,llama,axolotl)
  × 12 states** full Lottie sets + 5 dialogue packs. New pet state = enum entry
  + `pet_{type}_{state}.json`. Lottie filenames win; missing → `lottie_pet_{state}`.

## Gotchas (code-verified; audit additions marked ✱)

- **CI R8 needs the 4GB heap** — `gradle.properties` pins
  `-Xmx4096m`: at 2GB `minifyReleaseWithR8` throws
  `ConcurrentModificationException` on CI (v1.3.1's first run died there).
  Debug builds never notice; only release-on-CI does.
- **Two GitHub identities on this repo** — remote is `riddhibantia/PixelPal`
  but the active `gh` account is `shaikhakramshakil` (read-only here). Pushes
  need the riddhibantia token-header trick; **draft releases are invisible to
  shaikhakramshakil's `gh api`**, so after a release workflow runs, verify
  drafts with riddhibantia's token (`gh auth token --user riddhibantia`).
  softprops also leaves the release as an `untagged-…` **draft**: someone with
  write access must flip `draft=false` for it to go live (done once for
  v1.3.1).
- **`companion object {}` inside a class with a `companion` property shadows it**
  — never declare a companion object in classes holding a `companion` field
  (twice caused silent type chaos).
- **Smart casts on `by viewModel.uiState.collectAsState()` fail** — copy to a
  local `val state = uiState` first.
- **`combine` supports max 5 typed flows** — nest via intermediate data classes;
  dynamic lists via `flatMapLatest { combine(list.map{...}) }`.
- **Migrations**: NOT NULL columns need explicit DEFAULT/values in hand-written
  SQL (also mirrored in `@ColumnInfo(defaultValue=...)`); failures roll back
  the transaction.
- Realtime cloud listeners must apply rows **through DAOs, never
  repositories** (repo writes trigger pushes → echo loop).
- Retryable pushes go through **`CloudPushScheduler`** (unique + KEEP) for tasks,
  reminders and subtasks; the old task-only wiring and duplicate enqueue are fixed.
- Any new synced entity must write a `Firestore*.toMap()` payload + a tombstone
  path, or it will not sync/deletes will resurrect it.
- ~~WebSocket connector had a single socket slot~~ **FIXED**: now one socket per
  URL with a reader refcount; `checkOnce` only closes a URL it exclusively owns,
  so a one-shot poll can no longer kill a live stream (`WebSocketAgentConnector`).
- Widget broadcast handlers must `goAsync()` + `finish()` (fixed in TasksWidget —
  copy that pattern for any new widget action).
- HomeViewModel: collect the per-section slices (the dead aggregate `uiState` was removed).
- Workers with zero constraints (bond/personality) run regardless of network ✱.
- `PersonalityWorker` still fabricates stats — don't trust personality drift
  as a real signal ✱ (open).

## Known defects & open work

Full ranked list with **[FIXED]** markers and file:line in **MEMORY.md §2**;
every new component/shape added by the 2026-11-04 pass is catalogued in
**MEMORY.md §2b**. Status now:

**Fixed** (verified by compile + unit tests + on-device install): cloud-insert
buffering, reminder/subtask offline retry, widget `goAsync` + BondEngine reuse,
sync tombstones + race window + clock-skew guard, boot missed-reminder catch-up,
task due dates + notifications, task-delete undo, reminder categories/silent
sound/exact-alarm-cancel, speech-bubble actions, System theme, sync-error UI,
dead-code sweep, README truth-up, R8 + CI + crash logger.

**Fixed in the multi-agent pass (2026-11-04, second pass):** WebSocket
socket-ownership race (refcounted per-URL sockets); **Gemini health polling is no
longer billable** (`checkNow` is now a local, free check; a real API round-trip
only happens via explicit `verifyNow()`); multi-agent monitoring via
`tools/agent-bridge` (Claude Code / agy / Codex / OMP / opencode / Hermes).

**Still open** (deliberately deferred / future work): PersonalityWorker uses fabricated stats;
provider-beats-URL-scheme quirk; OverlaySession show/hide race; QR scan frame
0-height; Gemini key re-init per keystroke + plaintext storage; avatar seed
drift; POST_NOTIFICATIONS re-request; onboarding permission label refresh;
DynamicIsland x math; task photo persistable URI; `normalizeSlots()` no-op;
`ACTION_ADD_TASK`; remaining UI not yet string-resourced; no tests for the
coordinator's I/O orchestration, connector routing, or ReminderScheduler re-arm.

**Still not done from the old "Batch 2" list** (superseded parts are now
shipped): adaptive/frequent polling, agent events in the activity feed, agent
state on the Dynamic Island, FCM-based approvals (approvals currently require
the app to be open or the WorkManager poll to fire), and Kafka-style secret
storage for the Gemini key.

**Bridge follow-ups (deliberate, not defects):** the Firebase relay is removed
(2026-11-05) — off-Wi-Fi is Tailscale's job now, no rules deploy involved;
OMP/opencode/Hermes are status-only (no approval channel, see the table in
`tools/agent-bridge/README.md`); `pixelpal-bridge` is not published to PyPI —
install is the GitHub-Release tarball via `install.sh` (or `pipx install
<tarball>`).
