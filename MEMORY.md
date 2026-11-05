# MEMORY.md — PixelPal Codebase Audit (source of truth)

> **What this is:** the complete, code-verified audit of PixelPal captured on
> **2026-11-04** (session that built+installed the app on-device). Purpose:
> never re-analyze the codebase from scratch — start here. **Code is truth**;
> README/older docs may drift (see §6). Architecture summary lives in
> **AGENTS.md** (kept in sync); this file carries the detail: defects,
> dead code, drift, environment quirks, quick reference.
>
> **Maintenance rule:** any code change that touches an item below must update
> that item here (and AGENTS.md if architectural).
>
> **2026-11-04 (later same day) — REMEDIATION PASS SHIPPED.** A large fix batch
> landed on top of this audit: every data-safety defect in §2.A/B and most of
> §2.C, plus §3 dead-code items, README truth-up, R8/release, CI, crash logger
> and the first string-resource foundation. Section 2 items now carry
> **[FIXED]** markers with the approach used; the rest remain open. Verified:
> full compile clean, 11 unit suites green, APK installed, cold start OK, no
> crashes. The Batch-2 agent-monitoring roadmap items were explicitly left out
> of scope at the user's request.

---

## 1. Audit coverage & method (2026-11-04)

- 178 Kotlin files, ~18.5K lines, all inventoried; all services/overlay/worker/
  receiver/widget files + 26-file service layer traced to call sites.
- Read first-hand (Lead): manifest, Application, DI modules, Room v14 + all
  entities + migration chain, all 7 domain engines, PreferencesManager,
  CompanionBootstrapInitializer, BondEngine/CompanionEngine internals,
  CompanionReactionProvider, DialogueLoader, AnimationEngine/State,
  domain models, DAO query surfaces (tasks/reminders), repository contracts
  (AgentConnection + Companion), build.gradle deps, git history, README.
- Three fan-out subagent deep-dives: (a) presentation layer, (b)
  overlay/workers/receivers/widgets, (c) data/remote + repos + DI.
  Their findings were cross-checked and are merged below.

---

## 2. Ranked defect list (fix in this order if asked)

### A. Data loss / correctness
1. **[FIXED]** **Cloud inserts silently dropped when no primary companion exists yet.**
   `FireStoreSyncCoordinator.applyCloudTask/Reminder` do `?: return` when
   `getPrimaryDirect()` is null → early-login realtime pull loses tasks/
   reminders until a later pull. (FirestoreSyncCoordinator.kt:213, 224-225)
2. **[FIXED]** **Offline reminder/subtask writes are never retried.** Repos only
   fire-and-forget push (`pushXAsync`); `FirestorePushWorker`'s
   reminder/subtask/delete branches have **zero callers**. A failed offline
   write is lost despite the worker existing. (ReminderRepositoryImpl.kt:42,
   SubtaskRepositoryImpl.kt:40, TaskRepositoryImpl.kt:141-150)
3. **[FIXED]** **TasksWidget toggle/quick-write can be killed by process death** — bare
   `scope.launch` IO in BroadcastReceiver without `goAsync()`
   (TasksWidgetProvider.kt:69-71). Alarm/ReminderAction/AgentApproval receivers
   do it correctly — copy that pattern.
4. **[FIXED]** **Bond/milestone logic duplicated** in TasksWidgetProvider.markDone
   (+2 level, cap 100, %5 milestone, activity — inline, TasksWidgetProvider
   .kt:109-129) vs BondEngine.mutateLevel → drift risk on rule changes.
5. **[FIXED]** **Past-due one-shot reminders dropped on boot:** ReminderScheduler
   .rescheduleAll leaves next==null rows PENDING forever (alarm never re-set;
   stuck until user edits). (ReminderScheduler.kt:90-101)
6. **[FIXED]** **OverlaySession show/hide race:** `show()` suspends on the
   DataStore position read before `addView`; a `hide()` in that window used to
   leak a window nothing would remove. Fixed with a `showToken` bumped by both
   `show()` and `hide()`; the pending `addView` aborts when the token moved.
   (OverlaySession.kt)
7. **WebSocket single-slot race:** (open — Batch-2 agent-monitoring scope) `checkOnce`'s `finally { disconnect() }`
   kills any concurrently-live `connectAndStream` socket; `activeWebSocket`
   last-write-wins so `sendMessage` can target a stale socket; two concurrent
   streams clobber each other. (WebSocketAgentConnector.kt:36,79,127-129)
8. **[FIXED]** **Sync race window:** remote change landing between `pullAndApplyCloud`
   (:147) and `pushAllLocalToCloud` (:159) is clobbered — push is
   unconditional; LWW guards cloud→local only.
9. **[FIXED]** **Timestamp-only echo guard:** remote row applied when `updatedAt` strictly
   greater; equal timestamps with different content (clock skew) are
   permanently skipped. (FirestoreSyncCoordinator.kt:215, 226)
10. **Task photo URIs not persistable:** (open) TaskDetailScreen GetContent result
    stored without `takePersistableUriPermission` → photo may fail to load
    later. (TaskDetailScreen.kt:312)

### B. Cost / behavior correctness
11. **Gemini polling is billable:** (open — Batch-2 agent-monitoring scope, deliberately deferred) provider=gemini + pollingEnabled → every
    AgentStatusWorker tick runs a real `generateContent` ping
    (AgentConnectionRepositoryImpl.kt:170; GeminiAgentConnector.kt:84).
12. **PersonalityWorker fabricates stats** (open — needs real daily stats plumbing) (remindersCompleted=1, ignored=0,
    isMorningActive=true hardcoded) — daily personality drift is fake signal.
    (PersonalityWorker.kt:31-34)
13. **Provider beats URL scheme:** (open — Batch-2 scope) WEBSOCKET provider + http:// endpoint routed
    to ws checkOnce → hardcoded "must start with ws://" ERROR.
    (AgentConnectionRepositoryImpl.kt:172-173; WebSocketAgentConnector.kt:114-115)
14. **[FIXED]** **Duplicate push on task create:** addTask does pushTaskAsync AND enqueues
    FirestorePushWorker (TYPE_TASK only). Also non-unique enqueue → possible
    concurrent duplicate pushes. (TaskRepositoryImpl.kt:63-64, 141-150)
15. **[FIXED]** **Reminder save ignores exact-alarm outcome:** CreateReminderScreen saves
    even when the settings flow is cancelled → alarm silently inexact/fallback.
    (CreateReminderScreen.kt:189-207)
16. **[FIXED]** **"Silent" ringtone = default sound:** stores soundUri=null which means
    "use default" — a genuinely silent option doesn't exist.
    (CreateReminderScreen.kt:118-133)
17. **[FIXED]** **Reminder category hardcoded CUSTOM** in VM create (ReminderViewModel.kt:71);
    message field never saved (:180).
18. **[FIXED]** **Pull failures invisible:** (also now surfaced in Profile UI) coordinator logs pull errors only;
    `_lastSyncError` covers push fails only. (FirestoreSyncCoordinator.kt:166-169, 193-203)

### C. UI / polish
19. **QR scan alignment frame invisible:** (open) second `fillMaxSize` child of a
    Column → 0 height. (QrScanScreen.kt:149-154)
20. **Gemini key field re-initializes connector per keystroke** (open) and stores the
    raw key in DataStore. (CompanionWorkspaceScreen.kt:441-444 → VM :146-161)
21. **Avatar seed mutates forever:** (open) nextAvatarSeed appends "-variant" each
    tap. (ProfileViewModel.kt:63-66)
22. **[FIXED]** **Swipe-right task delete has no undo/confirm** (reminders do).
23. **POST_NOTIFICATIONS re-requested on every reminder-list entry** (open)
    (ReminderListScreen.kt:64-68) on top of MainActivity's launch request.
24. **Onboarding "Permission Granted" label doesn't refresh** (open) on return from
    settings (OnboardingScreen.kt ~permission page); mascot hardcoded 'cat'
    (AuthScreen.kt:109, OnboardingScreen.kt:202).
25. **[FIXED]** **Theme "system" (dynamic color) supported by theme code but not listed**
    in ThemePicker options.
26. **DynamicIsland x-position math fragile** (open) (absolute window x from
    cumulative dx; only correct because it starts centered at x=0).
    (OverlayManager.kt:193-202)
27. **[FIXED]** **HomeWidget swallows all exceptions** (empty catch) → silently stale
    widget; raw SQL string interpolation. (HomeWidgetProvider.kt:74-110,142)

---

## 2b. Remediation pass — what was ADDED (2026-11-04)

New/changed shapes a future session must know. Update this list when these evolve.

### Sync reliability
- `data/remote/firebase/SyncDecision.kt` — **`SyncPolicy`** internal object: pure,
  JVM-testable conflict rules. `effectiveUpdatedAt(writerUpdatedAt, serverTsMillis)`
  = `max(...)`; `shouldApplyRemote` (cloud strictly newer); `mergeAction(...)`
  (INSERT/UPDATE/TOMBSTONE/NONE); `canPushOverExisting(cloudDeleted, cloudEffective,
  localUpdatedAt)` — a tombstone only yields to a strictly newer edit;
  `shouldApplyPushTombstone`; `needsBufferInsert(...)`; `FIELD_SERVER_TS = "syncServerTs"`.
  **Single source of truth for LWW** — do not re-implement comparisons in the
  engine or coordinator.
- `FirebaseModels.kt` — `deleted: Boolean = false` tombstones on Task/Reminder/
  Subtask; `internal fun Firestore*.toMap(): Map<String, Any>` payload writers
  (exclude `@DocumentId`, drop nulls via `nonNullMapOf`, cover every field —
  a missing field silently stops that column syncing).
- Engine deletes are now **soft deletes**: `SET {deleted:true, updatedAt, syncServerTs}`
  merged inside a transaction with a `canPushOverExisting` guard, returning whether
  the tombstone landed. Refused pushes mean "cloud holds a newer tombstone" — on
  listener paths the local row is mirrored away instead of re-pushed forever.
- `FirestoreSyncCoordinator` buffers cloud inserts that can't attach yet (no primary
  companion / missing parent task) instead of `?: return` dropping them; buffer
  drains on later applies and in `fullSync`, and is cleared on sign-out.
- `worker/CloudPushScheduler.kt` — **the** way to enqueue retryable pushes:
  unique work per `(type, cloudId)` with `ExistingWorkPolicy.KEEP`, CONNECTED,
  exponential backoff. Repos keep the instant fire-and-forget push (snappy UI);
  the unique worker covers offline. Used by task + reminder + subtask repos.

### Reliability / UX
- `widget/TasksWidgetProvider` — `goAsync()` + `PendingResult.finish()`; toggles
  delegate to `CompleteTaskUseCase` (BondEngine rules) instead of inlined bond math;
  un-complete mirrors `toggleTask` (no bond).
- `worker/BootCatchUpWorker.kt` — boot-time aggregation of past-due ONE-SHOT
  reminders: marks them MISSED/TRIGGERED, records `ActivityType.MISSED_REMINDER`
  (no bond), posts one "you missed N reminders" notification
  (`NOTIFICATION_ID_MISSED_REMINDERS = 1005`). Recurring reminders re-arm to the
  next occurrence as before. Enqueued by `BootReceiver` after `rescheduleAll`.
- `worker/TaskDueWorker.kt` + `TaskDueScheduler` — per-task due notification,
  unique `TASK_DUE_WORK_PREFIX = "task_due_<cloudId>"`, `NOTIFICATION_ID_TASK_DUE = 1100`,
  `CHANNEL_TASK_DUE`. Cancelled/rescheduled on edit/complete/delete.
- Tasks UI: due-date picker in NewTask + TaskDetail (`TaskDueDateSection.kt`,
  `TaskDueFormat.kt` for relative labels), swipe-delete now offers **Undo**
  (`SnackbarEvent.TaskDeleted` → reinsert).

### Reminders
- `domain/model/ReminderCategory.kt` — `ReminderCategory` enum (MEETING/MEDICINE/
  BIRTHDAY/SHOPPING/ASSIGNMENT/CUSTOM) with icon + `soundHint`, `fromId()` falls
  back to CUSTOM; `ReminderSound` enum (DEFAULT/ALARM/NOTIFICATION/CHIME/**NONE**).
  `NONE` is a real silent mode (`soundUri` sentinel) — `null` still means "default"
  for backward compatibility.
- Category picker + message field in CreateReminder; category icon/filter in the
  list; cancelling the exact-alarm flow now ABORTS the save.
- Speech bubble: `OverlayManager.showSpeechBubble(...)` has an actions overload;
  `SpeechBubbleOverlayView.showActions` is finally wired (Complete / Snooze 10 min
  for reminders), mirroring `CompanionEngine.onReminderTriggered` semantics.

### Platform / release
- `res/values/strings.xml` created (30 entries) — convention documented in the
  file header: `screen_feature_element`. Only About + ThemePicker are migrated;
  the rest of the UI is still hardcoded English (open work).
- Theme picker offers **System** (dynamic color API 31+, sensible fallback below).
- About no longer claims "nothing is uploaded anywhere" (honest Firebase copy).
- `app/build.gradle.kts`: versionCode **3** / versionName **1.2.0**; release =
  R8 minify + resource shrink (unsigned without signing props — see RELEASING.md).
- `.github/workflows/android.yml` — debug APK + unit tests + unsigned release APK,
  artifact upload, no secrets committed.
- Crash visibility: `PixelPalApplication` installs a **file-based CrashLogger**
  (`filesDir/crashes/crash_log.jsonl`, capped) that delegates to the platform
  handler — Crashlytics was deliberately NOT added (no signing/plugin secrets in CI).
- Profile screen surfaces `FirestoreSyncCoordinator.lastSyncError` so failed syncs
  are visible instead of log-only.
- `README.md` corrected against code; `RELEASING.md` documents signing + release.

### Tests added
- `SyncPolicyTest` (16 cases: LWW, tombstone merge decisions, push guards,
  buffering, server-field collision) — and `ReminderCategoryTest`. Unit suites now
  11 files. Still no tests for the coordinator's I/O orchestration, connector
  routing, or ReminderScheduler re-arm (open).

### 2026-11-04 — "core loop actually works" pass

Shipped after the audit above; each item was verified by compile + unit tests +
install over the existing v15 database (migration ran on device: user_version 16).

- **Bridge polling left the UI.** `AgentBridgeSync` (one fetch→mirror path),
  `AgentBridgePoller` (process-life poll: 4 s while approving, 20 s idle),
  `AgentBridgeWorker` (15-min WorkManager fallback, `pixelpal_bridge_poll`),
  `BridgeApprovalNotifier` (dedupes by `notified_bridge_approvals`, cancels
  resolved asks). `AgentApprovalReceiver` gained `EXTRA_SOURCE`/`SOURCE_BRIDGE`
  so notification Approve/Deny reaches `AgentBridgeTransport`. Before this the
  Agents screen was the only poller and bridge sessions never notified.
- **One agent data source.** `HomeViewModel.AgentSlice` (paired/working/waiting/
  headline/detail/progress) replaces the legacy `AgentConnection` slice; the
  home widget reads `agent_sessions` instead of `agent_connection`.
- **Recurring reminders exist.** Repeat picker (Once/Daily/Weekly/Monthly) in
  CreateReminder, `recurrenceDayOfMonth` (Room **15_16**, entity+model+
  Firestore `toMap`), and pure `domain/engine/RecurrenceMath.kt` with
  `RecurrenceMathTest` (month-end clamp 31→28→31, DST, interval jump).
- **QR pairing was inert and unguarded.** The Agents screen never read
  `SCANNED_URL_KEY`, so scanning from the Agent tab did nothing; and
  `parsePairingUri` accepted any host. Now the screen consumes the result and
  the host must be loopback/RFC1918 (`PairingUriTest` updated + 2 new cases).
- **Forms survive rotation/process death**: `rememberSaveable` in CreateReminder
  (all fields), NewTask (title/description/due/subtask drafts via `listSaver`),
  CompanionWorkspace (endpoint/key/command). The app previously had zero
  `rememberSaveable` calls.
- **Sign-out fails closed** when the local wipe fails (retry once, then abort
  and surface `lastSyncError`).
- **Auto Backup no longer carries secrets**: both XML rule files include only
  `database`. The prefs file (bridge key, Gemini key) is excluded.
- **Bond decay is calendar-day based** (`ChronoUnit.DAYS` between local dates,
  cursor advanced by whole days so a retried worker can't double-subtract).
- **Bridge server compares `X-PixelPal-Key` with `hmac.compare_digest`.**
- **Onboarding page 5** ("Watch Your Agent Work") shows the install commands
  with a copy button.

Known limit of this pass: `cleartextTrafficPermitted="true"` stays in
`network_security_config.xml` because Android cannot express RFC1918 ranges in
`domain-config`; flipping the base-config to false would break LAN pairing. The
host allowlist (`isAllowedEndpoint` + the new pairing check) is the real gate.

### 2026-11-05 — ⚠️ THE FIREBASE RELAY IS REMOVED (supersedes the two 2026-11-04 relay entries below)

Everything from here to "Deliberately NOT done" describing `relay.py`,
`relay_crypto.py`, `BridgeRelayClient`, pairing codes, `/relays` rules and
`test/rules/` is **history of deleted code**. Reason: ~17,280 Firestore
reads/user/day vs a 50k/day free tier (dead at ~2 users) plus a mandatory
manual rules deploy. Off-Wi-Fi is Tailscale now; transport is direct-only.
Current truth lives in `AGENTS.md` → "Direct connect (2026-11-05)". The
daemon-interpreter, port-collision and hook-safety lessons below still apply.

### 2026-11-04 — relay actually works end to end

Found while trying to pair a real phone against the deployed rules. The relay
was *registered* by `setup` and never used again: `RelayClient` had
`publish_agents`/`fetch_verdict` but nothing called them, so a pairing either
succeeded into an empty screen or failed at the first write.

- **`RelayLoop`** (`relay.py`): one `step()` = read verdict → publish state if
  it changed → return `next_poll_delay()`. `run_forever(stop)` never dies on a
  transport error (backs off 3×20 s). Wired into `serve` by `_start_relay`,
  which retries every 30 s if the relay is unreachable.
- **Stable bridge identity**: the rules tie writes to `bridgeUid`, and every
  process used to mint a new anonymous account → HTTP 403 on the daemon's first
  publish (observed in the journal). The refresh token is now persisted
  (`relayRefreshToken`, 0600) and rotation is written back.
- **`register()` is idempotent**: create once; afterwards only
  `lastSeen`/`version` through `updateMask` (a full re-PATCH is denied by the
  rules). `_owns_existing()` refuses to adopt a relay whose verifier or
  `bridgeUid` is someone else's.
- **Daemon interpreter**: the systemd unit used `/usr/bin/python3`, which has no
  `pixelpal_bridge` when installed by pipx/uv → crash-loop with
  `ModuleNotFoundError`. Now `sys.executable`.
- **Port collisions**: setup picks the first free port and saves it (8787 was
  held by an unrelated dev server here, so the daemon would have crash-looped).
- **google-services discovery**: an installed CLI lives outside the repo, so the
  source-tree walk found nothing. Now `--google-services` /
  `PIXELPAL_GOOGLE_SERVICES` / CWD tree / source tree, and setup persists
  `googleServicesPath` so the daemon (CWD=/) can find it.
- **`doctor`** grew a Relay block (registered? readable? which project) and
  `status` prints the code it is publishing to.

Tests: `test_relay_loop.py`, `test_bridge_identity.py`, `test_serve_relay.py`
plus setup ordering/persistence cases — 187 python tests total. Proven live:
`relay registered (pixel-pet-a1cc6)` in the journal and `state/current` holding
the agent payload pushed by a real `/events` POST.

### 2026-11-04 — pairing was impossible: the handshake read rule

Reproduced live against the deployed rules, not guessed:

```
GET relays/{lookupId}  as an anonymous phone account  ->  403
{"error":{"code":403,"message":"Missing or insufficient permissions."}}
```

`BridgeRelayClient.pair()` reads the handshake document to verify the code and
*then* claims `phoneUid`. The rule was `allow read: if isParty()`, and a phone
that has not claimed yet is not a party — so pairing could never succeed, and
the user saw the raw Firebase text.

- `firestore.rules`: handshake read is now `if signedIn()`. This leaks nothing
  new: the doc id is `sha256(code)[:32]`, so reading the document already
  implies knowing the 42.8-bit code, and the verifier is an HMAC of that same
  code. Writes, `state` reads and `inbox` writes still require being a party.
- `test/rules/relay.rules.test.mjs`: the old "third party is denied everything"
  assertion became "can read the handshake, cannot change it, cannot read
  state", plus a new test that walks the app's real order (verify → claim →
  read state). 20 rules tests, e2e script still green.
- **The app now says what to do**: `BridgeRelayClient.describe()` turns
  `PERMISSION_DENIED` into "the project's Firestore rules are out of date — run
  `firebase deploy --only firestore:rules`". Unit-tested
  (`RelayPairErrorMessageTest`) with the denial flag injected, because
  constructing `FirebaseFirestoreException` runs its static initializer and
  needs a Firebase app.

Not caused by the relay loop work: the phone never got that far.

**Then made the deploy unnecessary.** `pair()` now claims `phoneUid` *first* and
reads the handshake second. The claim rule is unchanged by the fix, so the app
pairs against a project that still runs the old ruleset — verified in the
emulator with `git show HEAD:firestore.rules` as the ruleset: read-before-claim
denied, claim succeeded, handshake readable afterwards, state readable after
publish. Both orders are now pinned in `relay.rules.test.mjs` (21 tests). The
rules edit stays because it is the better contract (nothing is written on a
typo), but it is no longer a prerequisite.

### Deliberately NOT done (user scope decision)
Batch-2 agent-monitoring roadmap: adaptive/frequent polling, foreground fast-refresh,
WebSocket socket-ownership fix, agent events in the Activity feed, agent state on
the Dynamic Island, Gemini health-ping de-billing, secret storage for the Gemini key.

---

## 3. Dead code — REMOVED in the remediation pass

All items below were removed (compile-verified, zero references). Kept as a
record so nobody re-adds them; if a future feature needs one, reintroduce it
deliberately with a caller.

**REMOVED:** ScreenStateReceiver; WorkerScheduler.triggerImmediateSync;
PreferencesManager.overlayCompanionIds + setOverlayCompanionIds +
Constants.KEY_OVERLAY_COMPANION_IDS; GenericHttpAgentConnector.isAllowedScheme;
Constants.DATABASE_VERSION (stale 12); HomeViewModel aggregate
`uiState`/`HomeUiState`/`stateLabel`/`isMaxBond`/`markActivitiesSeen`;
FirestorePushWorker reminder/subtask/delete branches (now WIRED — see §2.2);
SnackbarEvent.TaskDeleted (now emitted by tasks delete/undo).

**STILL PRESENT (deliberate or owned elsewhere):** `normalizeSlots()` no-op in
OverlayManager; slot-stagger constants; `ACTION_ADD_TASK`;
`ActivityType.TAP`/`FEED` (legacy rows only). Re-check before another sweep.

Original inventory (for reference):

| Item | Location |
|---|---|
| ScreenStateReceiver (never registered) | receiver/ScreenStateReceiver.kt |
| WorkerScheduler.triggerImmediateSync (0 callers) | WorkerScheduler.kt:50-59 |
| overlayCompanionIds flow + setOverlayCompanionIds (service only syncs primary) | PreferencesManager.kt:58-61,136-140 |
| normalizeSlots() no-op stub + slot stagger constants | OverlayManager.kt:103-105 |
| SpeechBubbleOverlayView.showActions/animateOut (never invoked) | SpeechBubbleOverlayView.kt |
| ACTION_ADD_TASK constant (add button just opens app) | TasksWidgetProvider.kt:34 |
| HomeViewModel aggregate `uiState` (HomeUiState), stateLabel/isMaxBond, markActivitiesSeen | HomeViewModel.kt:119-137, 48-53, 209-211 |
| GenericHttpAgentConnector.isAllowedScheme | GenericHttpAgentConnector.kt:108 |
| SnackbarEvent.TaskDeleted (never emitted) | SnackbarEvent.kt:11 |
| Constants.DATABASE_VERSION=12 (stale; nothing reads) | Constants.kt:78 |
| ActivityType.TAP / FEED (legacy rows only; DAO excludes) | domain/model/ActivityEvent.kt |
| FirestorePushWorker TYPE_REMINDER/SUBTASK/DELETE branches (no callers) | FirestorePushWorker.kt |
| HomeScreen speciesLabel (unused), Workspace screen unused imports (Delete/Checkbox/SwipeToDismiss*/animateColorAsState + `scope`) | HomeScreen.kt:741-742; CompanionWorkspaceScreen.kt:22-40,115 |

---

## 4. Quick reference (numbers/facts worth memorizing)

- Bond: task +2, reminder +3, tap +1 (first 3/day), cap 100; milestones %5;
  streak milestones {3,7,14,30,60,100}; decay −1/day.
- Agent poll intervals: 15/30/60/120 min (WorkManager floor 15).
- Notification IDs: overlay FGS 1001, companion 1002, reminder 1003+id,
  agent 1004+companionId+approvalHash.
- Worker unique names: pixelpal_bond_decay, pixelpal_personality,
  pixelpal_firestore_sync, agent_poll_<id>.
- Firestore: paths `users/{uid}/companion/primary`,
  `tasks|reminders|subtasks/{cloudId}`, `metrics/bond`; batch 400; cache 100MB.
- DB name: `pixelpal_database_v2`; DataStore name: `pixelpal_preferences`.
- Gemini model: `gemini-1.5-flash`; reaction prompt timeout 4s; reply cap 180 chars.
- Overlay defaults: 64dp bubble at (80,200); islands top 6dp; keyboard dodge
  threshold 100dp; long-press 500ms; double-tap 300ms; drag threshold 10px.
- AnimationState durations must equal Lottie `op/fr*1000` (see enum KDoc).
- Test inventory — unit (11): AnimationEngine, ApprovalEnvelope, FirebaseModels,
  BondEngine, PersonalityEngine, AgentConnectionLogic, SpeciesRoster,
  CompanionColors, DarkTheme (+notes) ≈ 488 lines; androidTest (3):
  MigrationTest, ReminderDaoTest, TaskDaoTest, NavSmokeTest (file count 4 incl nav).
  Species all unlocked (SpeciesRosterTest expects 0 gates).
- Git: branch `main`, working tree clean (only untracked pixelpal-on-phone.png);
  recent commits = perf (recomposition split, indexes) + cloud-restore fixes
  (c8e7f66, 06192e7).

---

## 5. Environment notes (this machine)

- **Device**: Motorola Edge 50 Fusion (`192.0.0.2:5555`, wireless ADB, Android
  16), connected + verified working; PixelPal installed & launched 2026-11-04
  (cold start 1.6s, no crashes). Screenshot: `pixelpal-on-phone.png` (workspace
  root, untracked).
- **Gradle on this box has a network quirk:** the wrapper's JVM download from
  services.gradle.org redirect times out (`Connect timed out` after 10s) even
  though the network is fine — **curl works**. Workaround applied: Gradle 8.9
  dist manually downloaded + unzipped into
  `~/.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/` with
  `.ok` marker. If a future wrapper upgrade fails the same way, curl the zip
  into the new hash dir and `touch <zip>.ok`.
- Deps fully cached (~6.5GB); cold build 16m34s; subsequent builds warm.
- DSH session note: approval policy switched to danger-full-access mid-session
  (sandbox blocked `~/.gradle` writes initially).

---

## 6. Doc-drift ledger (checked against code on 2026-11-04)

| Claim (source) | Reality |
|---|---|
| AGENTS.md "Room v7" | **v14** (chain 1→14, see AGENTS.md §Database) — FIXED in AGENTS.md |
| AGENTS.md "unlimited" Firestore cache (FirebaseModule) | **100 MB cap** — FIXED in AGENTS.md |
| AGENTS.md workspace = Profile/Bond/Personality/Tasks/Reminders/AI/Activity | Workspace route = **AI Agent hub only** — FIXED in AGENTS.md |
| AGENTS.md home hero "Interact/Feed/Play" row | Only tap interactions; **feed exists solely via overlay long-press** — FIXED in AGENTS.md |
| AGENTS.md "sprites full set only for cat; others idle vectors" | **All 7 species × 12 Lottie states present** — FIXED in AGENTS.md |
| Customize "Species×Color×Pattern" (old AGENTS + old README wording) | **Species picker (+ theme) only**; color/pattern pickers don't exist — FIXED in AGENTS.md |
| AuthScreen "Guest mode anonymous auth, Email/Password" | Also **Google via Credential Manager** — FIXED in AGENTS.md |
| LottiePetView "fallback to vector drawables" | **No vector fallback** (blank while loading); PetRenderer is a thin wrapper — FIXED in AGENTS.md |
| README "nothing is uploaded anywhere" (AboutScreen.kt:109-110) | Contradicted by Firestore sync — README/About NOT yet fixed |
| PixelPalBottomBar KDoc labels (says Customize tab) | 5-tab bar; doc stale in-code — NOT yet fixed |
| Constants.DATABASE_VERSION=12 | Dead/stale — real version in PixelPalDatabase.kt — NOT yet removed |

Items marked FIXED were corrected in the AGENTS.md rewrite; the rest are open
nitpicks to address opportunistically (README is user-facing; touch only when
asked).

---

## 7. Notable design intent (don't "fix" these away)

- **Keyboard dodge via AccessibilityService** — deliberate (implementation_plan.md):
  TYPE_APPLICATION_OVERLAY windows get no IME insets on Android 11+; the
  accessibility service is the only reliable system-wide keyboard-height source.
  Requires user to enable it in Settings → Accessibility → PixelPal.
- Realtime listeners apply through DAOs (not repos) to avoid push echo — any
  refactor through repositories reintroduces the loop.
- Taps/feeds intentionally create NO ActivityEvents (only bond/emotion) — the
  timeline stays meaningful; DAO excludes legacy TAP/FEED rows.
- Taps stop granting bond after 3/day — deliberate anti-spam; keep counters.
- One-shot WS polling with read-timeout 0 only in the streaming client;
  checkOnce uses its own 12s timeout — per-connector timeouts are intentional.
- Cleartext HTTP allowed for loopback/LAN endpoints on purpose (QR pairing with
  laptop agents on same Wi-Fi); everything else must be HTTPS.
- The app foregrounds-parks the overlay bubble to avoid a second infinite
  Lottie loop over the in-app hero card.

---

## 2c. UI Overhaul & Glassmorphism Redesign Pass (2026-11-04)

Complete frontend overhaul matching the approved frosted violet glassmorphism mockups (`media_1793796050484.jpg`):
- **GlassCard Component** (`presentation/components/GlassCard.kt`): Frosted glass container with configurable translucency, gradient borders (`PixelPalGradients.glassBorder`), and optional click ripple. Rolled out to Home cards, Task rows, Reminder cards, Settings groups, and Agent cards.
- **Floating Pill Bottom Dock** (`presentation/components/PixelPalBottomBar.kt`): 5 navigation tabs (Home, Tasks, Agent, Reminders, Settings) with an animated violet gradient active pill and system navigation bar padding.
- **Redesigned Home Dashboard** (`presentation/screens/home/HomeScreen.kt`):
  - Top header with grid customize icon, notification bell, greeting, and compact mascot inside an organic violet glow blob reacting to taps with spring physics and sparkles.
  - Action cards row: "Interact" (Gemini chat) in rich violet gradient and "Workspace" (dev space) in frosted glass.
  - Weekly activity streak bar chart (Mon-Fri) with dynamic height intensity.
  - Quick Stats row for Tasks left and Reminders.
  - Live AI Agent card with inline Approve/Deny buttons for pending approvals.
  - Today task checklist preview with direct toggle.
  - Focus Time card with circular progress ring (`FocusCircularProgress`) integrated with `FocusTimerEngine`.
- **Domain Engines & Models**:
  - `domain/engine/FocusTimerEngine.kt`: Singleton focus timer with start/pause/toggle, goal tracking, and formatted elapsed/goal strings. Fully unit-tested in `FocusTimerEngineTest.kt`.
  - `domain/model/WeeklyActivity.kt`: Mon-Fri activity model tracking intensity and status.
- **Secondary Screens Consistency**:
  - `TasksScreen`, `ReminderListScreen`, `SettingsScreen`, `SettingsGroup`, and `ConnectedAgentsScreen` updated to use `GlassCard` and given `100.dp` bottom content padding to avoid occlusion by the floating bottom dock.

