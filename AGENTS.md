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
> phone-side approval gate, Room v15 `agent_sessions`, the ConnectedAgents hub,
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
- **Clean**: `./gradlew clean`
- No lint/typecheck/formatter beyond the standard Gradle build.
- Gemini key: `GEMINI_API_KEY` in `local.properties` → `BuildConfig.GEMINI_API_KEY` (optional; app falls back to local dialogue when missing).

## Tech stack (verified)

Kotlin 2.0.0, JVM 17, AGP 8.5.0, **KSP** for Room+Hilt, Compose BOM + Material 3 +
Navigation Compose, WorkManager + hilt-work, **Room v15**, DataStore (Preferences),
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

## Database — Room **v15** (`PixelPalDatabase.kt`)

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
  DAO-matching composite indexes → **14_15 `agent_sessions`**.
- The Room version lives ONLY in `PixelPalDatabase.kt` (v15). The old
  `Constants.DATABASE_VERSION` was removed in the 2026-11-04 pass.

## Domain engines

- **BondEngine** — single mutation path `mutateLevel()`: task +2, reminder +3,
  tap +1 but only first `BOND_GRANTING_TAPS_PER_DAY`(3)/day grant level (max
  10 taps/day recorded); feed/play cosmetic (streak/recency only). Level cap
  100; activity events at level%5==0 and streaks {3,7,14,30,60,100}. Decay:
  −1 level per full day inactive, resets daily tap/feed counters.
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
- App side: `AgentSessionRepository` polls `/agents` and mirrors it into
  `agent_sessions` (Room v15); a **bridge that is unreachable returns -1 and
  leaves existing rows alone** (an asleep laptop must not erase the pets).
- CLI: **`setup`** (one command: hooks + daemon + relay + pairing code) plus
  `serve | install | uninstall | doctor | pair | status | simulate`.
  Config at `~/.config/pixelpal/bridge.json` (mode 0600).

### Zero-config connect (2026-11-04, third pass)

- **`pixelpal-bridge setup`** is the whole onboarding: installs hooks, starts a
  **daemon** (`service.py`: systemd `--user` / launchd / detached fallback, all
  reversible), registers the relay, and prints a **9-character code**.
- **`relay_crypto.py` / `RelayCrypto.kt` are byte-identical twins.** The pairing
  code is never transmitted: both sides derive
  `relayKey = PBKDF2-SHA256(code, "pixelpal-relay-v1", 260_000)` and Firestore
  only stores `HMAC(relayKey, "pixelpal-handshake")`. 9 chars × 27 unambiguous
  symbols = 42.8 bits (6 chars = 30 bits is crackable in ~39 GPU-hours — that is
  why it is not 6). **Changing either file requires re-running both test suites**;
  `RelayCryptoTest` pins vectors captured from the Python side.
- **`relay.py`** (bridge) and **`BridgeRelayClient.kt`** (app) share one
  Firestore layout: `relays/{sha256(code)[:32]}` + `state/current` (agents JSON
  string) + `inbox/current` (verdict). Cadence is **2 s while an approval is
  pending, 20 s idle** (`next_poll_delay`) to stay inside the 50k/day free tier;
  a test asserts the idle budget.
- **`AgentBridgeTransport.kt`** prefers direct LAN and falls back to the relay
  silently — the UI only ever says "Connected".
- **Rules** (`firestore.rules` `/relays` branch) are proven by 19 emulator tests
  in `test/rules/`: a third party who knows the doc id is denied; the phone can
  answer approvals but cannot forge agent state; **the bridge cannot approve its
  own requests**; one phone per relay.
  ⚠ Two Firestore rule traps that bit us and are now commented in the rules:
  an **absent field is `undefined`, not `null`** (use `keys().hasAny`, not
  `== null`), and a client `updateDoc` re-sends fields so
  `diff().affectedKeys().hasOnly([...])` is too strict for claims.
- **Deployment is manual by decision:** rules are written but NOT deployed.
  `firebase deploy --only firestore:rules` is required before the relay works.

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
  local→cloud pushes unconditional. `signOutSuspend`: epoch++ (aborts
  in-flight sync), cancel listeners, wipe ALL local tables **before**
  auth sign-out (cross-account leak guard).
- `FirestoreSyncWorker`: hourly periodic + one-shot "immediate", CONNECTED;
  no-op when logged out, retry on failure. Profile → "Sync Now" = fullSync.

## Presentation map

- **Navigation** (17 plain string routes, fade+slide): onboarding, auth, home,
  reminders, create_reminder, customize, settings, profile, overlay_settings,
  about, permissions, workspace (no id — single companion),
  **connected_agents** (the "Agent" bottom-tab — multi-agent hub backed by the
  bridge; `workspace` stays reachable for manual provider setup), tasks,
  new_task, task_detail/{taskId}, activity_center, qr_scan.
- **Start destination** (MainActivity): first-launch → onboarding; else no
  Firebase user → auth; else home. Reactive sign-out watcher resets to auth
  (`popUpTo(0)`). Overlay service restarted at activity start when enabled.
- **HomeScreen**: greeting + bell (unread badge) + gear, hero pet card,
  AgentStatusCard, Today chips, overlay row. Perf-sliced StateFlows
  (`headerSlice/heroSlice/...`) — collect those, per-section, so background
  polls don't recompose the Lottie hero (the old aggregate `uiState` was removed).
  No Feed/Play row; feed is reachable via overlay long-press only.
- **ConnectedAgentsScreen** (`presentation/screens/agents/`) = the **Agent tab**
  now: one row per live agent (status dot, message, project, "12s ago"), with
  **inline Approve/Deny** when an agent is `WAITING_FOR_INPUT`; pairing is one
  "Scan pairing code" action. Refreshes via `repeatOnLifecycle(RESUMED)` — no
  timers, no wake locks. Empty states distinguish "no bridge paired" from
  "bridge paired but no agents running".
- **CompanionWorkspaceScreen** = the **AI Agent hub** (provider chips, URLs,
  poll interval, Connect/Save+checkNow, Check Now, text+voice commands, Gemini
  key override, disconnect+Undo, favorite/rename ≤20 chars, QR result via
  previousBackStackEntry SavedStateHandle).
- Tasks/TasksDetail: subtask checklist, photo proof (photoUri via GetContent —
  note: no takePersistableUriPermission), **due dates** (picker + relative labels
  via `TaskDueDateSection`/`TaskDueFormat`, notification via `TaskDueWorker`),
  swipe-delete **with Undo** (`SnackbarEvent.TaskDeleted` → reinsert).
- Reminders: global lists (not companion-filtered); 3 time modes; **category
  picker** (`ReminderCategory` + icon + filter chips) and a message field;
  cancelling the exact-alarm flow now ABORTS the save; **Silent is a real mode**
  (`ReminderSound.NONE`, stored as a `soundUri` sentinel — `null` still means
  default for backward compatibility).
- Customize: **species picker + app theme only** (color/pattern pickers do
  not exist); name field writes DB per keystroke.
- ActivityCenter: bell target, live agent banner, markAllRead on open; DAO
  query excludes TAP/FEED legacy rows.
- AuthScreen: Google/email/guest + reset; nav gate = authenticated AND
  isDataReady AND syncError==null (retry / Continue-offline on sync failure).
- **Strings**: `res/values/strings.xml` now exists (convention documented in its
  header: `screen_feature_element`). Only About + ThemePicker are migrated; the
  rest of the UI is still hardcoded English — migrating more is open work.
- Components: LottiePetView (raw `pet_{type}_{state}.json`, process-wide
  res-id cache, draw-phase progress), PetRenderer wrapper, PixelPalBottomBar
  (5 tabs), PixelPalSnackbarHost/SnackbarEvent (Undo flows), settings kit
  (SettingsRow/Group…), ThemePicker (dark/light/spring/system/autumn — "system"
  uses dynamic color on API 31+ with a fallback below).

## Overlay subsystem

- `OverlayService` — foreground `specialUse` ("Screen Companion Overlay"),
  START_STICKY, notification 1001; combines (overlayEnabled, primary, app
  foreground) → syncs sessions; parks bubble while app foregrounded (lifecycle
  callbacks in Application). Starters: MainActivity, HomeViewModel,
  OnboardingScreen, SettingsViewModel, BootReceiver.
- Cap: `MAX_SIMULTANEOUS_OVERLAYS = 1` (soft — desired set only ever contains
  the primary; slot-stagger system is vestigial/no-op).
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
  next reminder/agent line) and TasksWidget (6 rows + toggle intents).
  TasksWidget toggles use **`goAsync()` + `CompleteTaskUseCase`** — the same
  bond/activity path as the app (no duplicated bond math). Read-only queries may
  still touch the DB directly.

## DI modules (SingletonComponent)

- `AppModule`: DataStore + OkHttpClient (10s timeouts, debug logging).
- `DatabaseModule`: Room v15 + migration chain + DAO providers.
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

**Bridge follow-ups (deliberate, not defects):** the relay requires a one-time
`firebase deploy --only firestore:rules` (user chose not to let me deploy);
OMP/opencode/Hermes are status-only (no approval channel, see the table in
`tools/agent-bridge/README.md`); `pixelpal-bridge` is not yet published to PyPI,
so install is from the repo (`pipx install .`).
