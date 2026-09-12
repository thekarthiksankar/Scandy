# LLD — Compose UI Migration (Scandy)

**Status:** Proposed · **Scope:** Step 1 (Compose UI) and Step 2 (Compose Navigation), sequenced
separately so each can be measured in isolation
**Predecessor:** `scratch/compose-migration.md` (Step 0, framework-agnostic core) — landed as `c6e9f9d`

---

## 1. Purpose

Step 0 pulled camera orchestration out of `ScannerActivity` into `camera/Scanner.kt` and left a
deliberate seam: state as `StateFlow`, a `suspend fun scan()`, and CameraX/lifecycle types that
Compose satisfies identically. This document plans the steps that seam was built for.

The work is split into two independently shippable phases:

| Phase | Change | Navigation | Core changes |
|---|---|---|---|
| **1 — Compose UI** | View layer → Compose, screen by screen | unchanged: 3 Activities, `Intent` + `Serializable` extra | **zero** |
| **2 — Compose Navigation** | 3 Activities → 1 + `navigation-compose` | type-safe routes | drop `Serializable` |

Splitting here isolates the variables. Phase 1's delta is "View stack out, Compose in". Phase 2's
delta is "Intent navigation out, nav-compose in". Measured together they are one confounded number;
measured apart, each is attributable. §8 defines the measurement.

The phase boundary is cheap because of one discipline, adopted from the first Compose commit:
**every screen takes its navigation as hoisted callbacks** (`onAboutClick: () -> Unit`,
`onBarcode: (ScandyBarcode) -> Unit`, `onBack: () -> Unit`) and flattened value parameters — never a
`NavController`, never an `Activity`. In Phase 1 those callbacks call `startActivity`. In Phase 2 the
call sites change and the composables do not. See §5.6.

**Success criterion:** the seam table in the Step 0 doc (§11) stays empty through Phase 1 — no file
under `camera/` changes at all. Phase 2 makes exactly one core change, the one Step 0 predicted.

---

## 2. Current state

### 2.1 What exists

| Layer | Files | Fate |
|---|---|---|
| Core (Step 0) | `camera/Scanner.kt`, `BarcodeReader.kt`, `ScandyBarcode.kt`, `ScannerStatus.kt`, `FlashStatus.kt` | untouched in P1; one line in P2 |
| Activities | `ScannerActivity` (launcher, splash, toolbar+menu, permission UI, torch), `DisplayActivity`, `AboutActivity` | shells in P1, deleted in P2 |
| | `ui/BaseActivity.kt` | deleted in **P1** (§5.3) |
| Permissions | `permissions/PermissionWrapper.kt`, `PermissionHost.kt`, `extensions/FragmentExtensions.kt` | deleted in **P1**, replaced |
| Extensions | `ViewExtensions.kt` (gone/visible/click) | deleted in P1 |
| | `ContextExtensions.kt` (clipboard, app settings) | kept |
| Resource mapping | `ui/scanner/FlashUi.kt` | rewritten as Compose-local mapping, P1 |
| Layouts | `activity_scanner.xml`, `layout-land/activity_scanner.xml`, `activity_display.xml`, `activity_about.xml`, `menu_scanner.xml` | deleted in P1 |

Permissions and `BaseActivity` land in Phase 1, not Phase 2: neither has anything to do with
navigation, and leaving `PermissionWrapper` alive would mean writing `ScannerScreen` against an
Activity-bound API only to rewrite it weeks later.

### 2.2 The core's public API (the thing Compose consumes)

```kotlin
class Scanner(context: Context) {
    val status: StateFlow<ScannerStatus>
    val flash: StateFlow<FlashStatus>
    @MainThread suspend fun scan(viewFinder: Preview.SurfaceProvider, owner: LifecycleOwner): ScandyBarcode
    @MainThread fun toggleFlash()
}
```

`scan()` binds, suspends until the first barcode, and unbinds in a `finally`. One in-flight call at a
time. This shape is already the Compose shape — see §5.1.

### 2.3 Dead weight the build carries

`dataBinding true` and `viewBinding true` (no `<layout>` tag exists anywhere — dataBinding is pure
cost), Room + KSP, `navigation-fragment-ktx` / `navigation-ui-ktx`, `constraintlayout`, `material`,
`appcompat`. Nothing in `kotlin/` references Room or Navigation. `Fragment` appears only in
`BaseActivity` and the dead `PermissionHost.Fragment` subclass.

Room, KSP and the two navigation artifacts are **provably unused today** — they are not a cost of the
View stack and must not be removed inside a Compose commit, or they will contaminate the Phase 1
number. They come out in commit C0, before the baseline is taken (§7).

### 2.4 Constraints discovered while reading the build

- **No Kotlin Gradle Plugin is applied.** `app/build.gradle:5-8` declares only `android.application`
  (AGP 9.3.1) and `ksp`. AGP 9 supplies Kotlin built-in; stdlib resolves to 2.2.20. How the Compose
  compiler plugin attaches to built-in Kotlin is the biggest unknown here — C1 is a spike.
- **`namespace 'scandy'`** ≠ applicationId, so generated symbols import as `scandy.R`. Preserve.
- **`res.srcDirs`** is split three ways (`common`, `launcher`, `scanner`). Layouts and menus leave;
  drawables, fonts and the launcher/splash themes stay. Keep the split.
- **`core-splashscreen` needs an XML theme.** `Theme.Scandy.Splash` cannot become a Compose theme.
  A minimal XML theme survives both phases (§6.3).
- **`layout-land/activity_scanner.xml` has no toolbar** — hence `binding.toolbar?` being nullable in
  `ScannerActivity`. This orientation difference must be reproduced deliberately (§6.4), not lost.

---

## 3. Goals and non-goals

### 3.1 Phase 1 goals

- **G1** — No file under `camera/` changes. Zero. (Seam validation in its strongest form.)
- **G2** — Permission handling becomes testable state, with no `Fragment` dependency and no Activity
  subclass hierarchy.
- **G3** — `Scanner` outlives configuration change (ViewModel), so rotation no longer tears down and
  rebuilds the camera session.
- **G4** — dataBinding, viewBinding, ConstraintLayout, Material Components, AppCompat and Fragment
  all leave the dependency list.
- **G5** — Screens are navigation-agnostic (§5.6), so Phase 2 touches call sites only.

### 3.2 Phase 2 goals

- **G6** — One Activity; `ScandyBarcode` stops being `Serializable`.
- **G7** — `navigation-fragment-ktx` / `navigation-ui-ktx` replaced by `navigation-compose`.
- **G8** — No composable written in Phase 1 changes, only the code that calls it.

### 3.3 Non-goals (both phases)

Scan history (the reason Room was added, still unbuilt), new barcode types, Crashlytics/Analytics
(the two TODOs atop `app/build.gradle`), Navigation 3, baseline profiles, dark theme (§6.2),
screenshot testing infrastructure, and any redesign of the visual language beyond what M3 forces.

---

## 4. Target architecture

**End of Phase 1** — three Activities, each a `setContent` shell:

```
ScannerActivity          DisplayActivity            AboutActivity
  installSplashScreen()    reads Serializable extra
  enableEdgeToEdge()       │                        │
  setContent {             setContent {             setContent {
    ScandyTheme {            ScandyTheme {            ScandyTheme {
      ScannerScreen(           DisplayScreen(           AboutScreen(
        onAboutClick = {…},      text=, url=, title=,     onBack = { finish() })
        onBarcode = {…})         onBack = { finish() })
```

**End of Phase 2** — the shells collapse; the composables are byte-for-byte the same:

```
MainActivity → ScandyTheme { NavHost(Scanner → Display, Scanner → About) }
```

Package layout under `dev.thedukerchip.scandy`:

```
ui/
  theme/ScandyTheme.kt, Color.kt, Type.kt          [NEW, P1]
  permission/PermissionState.kt                    [NEW, P1] replaces permissions/
  scanner/ScannerScreen.kt, ScannerViewModel.kt, FlashUi.kt  [P1]
  display/DisplayScreen.kt                         [P1]
  about/AboutScreen.kt                             [P1]
  navigation/ScandyNavHost.kt                      [NEW, P2]
MainActivity.kt                                    [NEW, P2]
extensions/ContextExtensions.kt                    [KEPT]
camera/                                            [UNTOUCHED in P1]
```

---

## 5. Component design

### 5.1 Viewfinder — the crux of the seam

`Scanner.scan()` takes a `Preview.SurfaceProvider`. `androidx.camera:camera-compose` inverts control:
`CameraXViewfinder(surfaceRequest)` consumes a `SurfaceRequest` the caller captures. These reconcile
without touching the core, because `Preview.SurfaceProvider` is a functional interface:

```kotlin
var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }

LaunchedEffect(granted) {
    if (!granted) return@LaunchedEffect
    val barcode = scanner.scan(
        viewFinder = { request -> surfaceRequest = request },   // SAM, called on main
        owner = lifecycleOwner
    )
    onBarcode(barcode)
}

surfaceRequest?.let { CameraXViewfinder(it, modifier = Modifier.fillMaxSize()) }
```

`Scanner.scan()` is `@MainThread` and sets `surfaceProvider` synchronously, so the state write is
main-thread. G1 holds.

**Fallback if `androidx.camera:camera-compose` at 1.6.1 is unavailable or unstable:**
`AndroidView(factory = { PreviewView(it) })` and pass `previewView.surfaceProvider`. Also zero core
change, guaranteed to work, marginally less idiomatic. Decide during C1 — do not let it block.

### 5.2 `ScannerViewModel`

```kotlin
class ScannerViewModel(app: Application) : AndroidViewModel(app) {
    val scanner = Scanner(app)             // application context — no Activity leak
    val flash: StateFlow<FlashStatus> = scanner.flash
    val status: StateFlow<ScannerStatus> = scanner.status
    fun toggleFlash() = scanner.toggleFlash()
}
```

Ownership of `Scanner` moves Activity → ViewModel, exactly as Step 0 §8 anticipated. `Scanner` holds
no `Closeable` of its own (`BarcodeReader` is scoped inside `scan()` via `use`), so `onCleared()`
needs nothing today. If that changes, it changes here, not in the core.

`scan()` is driven from `LaunchedEffect` in the composable rather than `viewModelScope`, because it
is tied to composition and the viewfinder, not to the ViewModel's lifetime.

In Phase 1 the ViewModel is obtained with `viewModel()` inside `ScannerActivity`'s `setContent` and
is Activity-scoped. In Phase 2 it scopes to the `Scanner` nav back stack entry. No code change in
`ScannerScreen` either way — it receives the ViewModel or its state as a parameter.

### 5.3 Permissions — replacing `PermissionWrapper` (Phase 1)

**Recommendation: do not use `accompanist-permissions`.** Step 0 §15/R5 flagged Accompanist's
wind-down as worth confirming; the confirmation is that it should not be adopted now for ~40 lines of
behaviour we already own.

```kotlin
@Stable
class PermissionState internal constructor(
    val isGranted: Boolean,
    val shouldShowRationale: Boolean,
    private val onRequest: () -> Unit,
) { fun request() = onRequest() }

@Composable
fun rememberPermissionState(permission: String): PermissionState
```

Implemented with `rememberLauncherForActivityResult(RequestPermission())`, re-reading
`ContextCompat.checkSelfPermission` on `ON_START` via a `LifecycleEventObserver` — that ON_START
re-read is what today's `ScannerActivity.onStart` does, and it is what makes "user grants in
Settings, returns to app" work.

The one piece of real logic — "first refusal shows rationale, second sends you to app settings" —
becomes a pure function and the first genuinely unit-testable thing in the UI layer:

```kotlin
enum class PermissionAction { Request, Rationale, OpenSettings }
fun nextAction(isGranted: Boolean, shouldShowRationale: Boolean, hasAsked: Boolean): PermissionAction
```

Note this replaces `PermissionWrapper.askingFirstTime`, a `var` on a class recreated per Activity —
i.e. it does not currently survive process death. The composable version hoists it into
`rememberSaveable`, which is a behaviour fix, not a regression. Record it as such.

Once `PermissionWrapper` is gone, nothing needs `FragmentActivity`: `permissionWrapperFor` was the
only reason `BaseActivity` existed. The three Activities extend `ComponentActivity` directly for the
rest of Phase 1, and `androidx.fragment` leaves the graph (verify — §6.1).

### 5.4 Screens

| Screen | Parameters | Notes |
|---|---|---|
| `ScannerScreen` | `viewModel`, `onAboutClick: () -> Unit`, `onBarcode: (ScandyBarcode) -> Unit` | `Scaffold` + `TopAppBar` (portrait only, §6.4) with overflow "About". Torch `FloatingActionButton` bottom-centre. Permission-denied state replaces the two `Group` visibility toggles with a plain `if`. |
| `DisplayScreen` | `text: String?`, `url: String?`, `title: String?`, `onBack: () -> Unit` | **Flattened parameters from day one**, not a `ScandyBarcode` — this is what lets Phase 2 swap the Intent extra for a nav route without touching the composable (§5.6). Copy FAB always; Browse FAB only when the URL resolves. |
| `AboutScreen` | `onBack: () -> Unit` | Black background, logo, description, privacy link. `LinkMovementMethod` becomes an `AnnotatedString` with `LinkAnnotation.Url`. |

The XML toolbar in `activity_display.xml` is `visibility="invisible"` and contains a dead
`ic_back` + title `LinearLayout`. Reproduce it as *absent*; do not port the dead subtree.

`DisplayActivity.showBrowse()` currently both shows the Browse button *and* immediately
`startActivity`s — the browser auto-opens on scanning a URL. Port as-is; flagged separately (§9/R6)
rather than silently changed during a migration.

### 5.5 Navigation, Phase 2

Single `MainActivity` + `navigation-compose`. The catalog already pins `androidxNavigation = 2.9.8`
for the fragment artifacts; swap the artifacts, keep the version. Two edges only:
`Scanner → Display`, `Scanner → About`. Type-safe routes via `kotlinx-serialization`:

```kotlin
@Serializable data object Scanner
@Serializable data object About
@Serializable data class Display(val text: String?, val url: String?, val title: String?)
```

`ScandyBarcode` is **not** the route type — a sealed class with a `java.io.Serializable` supertype is
the wrong currency for nav args, and `DisplayScreen` already takes flattened values (§5.4). With the
Intent extra gone, `ScandyBarcode : Serializable` drops from the domain model. That is the single
core change in either phase, and Step 0 scheduled it here.

> **Decision, deferred to Phase 2 — not needed yet.** Two screens and two edges is also serviceable
> with a `rememberSaveable` screen enum + `BackHandler`, avoiding `navigation-compose` and
> `kotlinx-serialization` entirely. I lean nav-compose (it is the shape scan history would need, and
> it is offset by removing navigation-fragment/ui), but your sequencing means this choice can be made
> *with the Phase 1 numbers in hand* rather than guessed at now. Worth revisiting at C8.

### 5.6 The phase seam

What Phase 2 changes, per file:

| File | Phase 2 change |
|---|---|
| `ScannerScreen.kt`, `DisplayScreen.kt`, `AboutScreen.kt` | **none** |
| `ScannerViewModel.kt`, `PermissionState.kt`, `theme/*` | **none** |
| `ScannerActivity`, `DisplayActivity`, `AboutActivity` | deleted; their `setContent` bodies move into `ScandyNavHost` |
| `ScandyBarcode.kt` | drop `: Serializable` |
| `AndroidManifest.xml` | three `<activity>` entries → one |

If a composable does need editing at C8, the callback-hoisting discipline was violated somewhere in
Phase 1. That is the check to run, not a thing to work around.

---

## 6. Cross-cutting

### 6.1 Dependencies

**Phase 1 adds:** `compose-bom`, `material3`, `ui`, `ui-graphics`, `ui-tooling-preview`
(+ `ui-tooling` debug), `activity-compose`, `lifecycle-runtime-compose` (for
`collectAsStateWithLifecycle`), `lifecycle-viewmodel-compose`, `camera-compose`.

**Phase 1 removes:** `androidx-appcompat`, `material`, `androidx-constraintlayout`, the `dataBinding`
and `viewBinding` build features, and `androidx.fragment` (transitively, via `BaseActivity` — confirm
with `./gradlew :app:dependencies` that `activity-compose` does not pull it back in).

**Phase 2 adds:** `navigation-compose`, `kotlinx-serialization-json` + the serialization plugin.
**Phase 2 removes:** `androidx-navigation-fragment`, `androidx-navigation-ui` — *if* they were not
already removed in C0. See the note below.

**C0 (pre-baseline) removes:** `androidx-room-runtime`, `androidx-room-compiler`, the `ksp` plugin,
`androidx-navigation-fragment`, `androidx-navigation-ui`. All five are unreferenced by any Kotlin
source today. Removing them before the baseline keeps their cost out of both phase deltas.

`androidx-lifecycle-livedata` is used by `Scanner` (`torchState.asFlow()`) — keep it.

### 6.2 Theming (Phase 1)

`Theme.MaterialComponents.DayNight.NoActionBar` → M3 `ScandyTheme`. Existing palette carries over:
primary `#3ddb85`, light `#7affb5`, dark `#00a857`, secondary `#e3f2fd`. `values-night/themes.xml` is
effectively empty and the base theme hardcodes black-on-white with `windowLightStatusBar` — the app
is light-only today. **Port it as light-only** (`darkColorScheme` deferred), so the migration does not
smuggle in a dark theme nobody has designed, and so the Phase 1 diff stays a migration.

Fonts `lato_regular` / `lobster_regular` stay as resources, wired through an M3 `Typography`.

### 6.3 Splash

`installSplashScreen()` stays in `ScannerActivity` through Phase 1 and moves to `MainActivity` in
Phase 2. `Theme.Scandy.Splash` stays as XML throughout. Reparent it to `Theme.SplashScreen` from
`core-splashscreen` so `com.google.android.material` can actually be dropped in C7 — verify the splash
still renders, since the current parent chain runs through Material Components.

### 6.4 Insets and orientation (Phase 1)

Three hand-rolled `ViewCompat.setOnApplyWindowInsetsListener` blocks (one per Activity, added in
`8c44d63`) collapse into `enableEdgeToEdge()` + `Scaffold`'s `contentPadding` /
`Modifier.windowInsetsPadding`. targetSdk 37 enforces edge-to-edge regardless.

The landscape variant drops the toolbar. In Compose that becomes a single orientation check inside
`ScannerScreen`, and both `layout` and `layout-land` XMLs die. Verify in landscape explicitly
(§9.4) — this is the easiest parity detail to lose.

### 6.5 R8

`optimization.enable = true` on release. Compose is R8-friendly, but `proguard-rules.pro` may hold
keeps for classes these phases delete. Re-run the `r8-analyzer` skill at C7 and C9, and build a
release APK at each benchmark point — debug-only verification is not sufficient, and the benchmark
requires release builds anyway (§8).

---

## 7. Implementation sequence

Green at every commit. Benchmark points marked **B0/B1/B2**.

### Phase 0 — baseline hygiene

| # | Commit | Contents |
|---|---|---|
| C0 | drop unused deps | Room, KSP, navigation-fragment/ui, `dataBinding false` (no `<layout>` exists). No code change. → **measure B0** |

`dataBinding` goes here rather than in Phase 1 because it is unused today; `viewBinding` stays until
C7, since the Activities genuinely use it until then.

### Phase 1 — Compose UI (3 Activities retained)

| # | Commit | Contents | Risk |
|---|---|---|---|
| C1 | **spike: compose toolchain** | Apply `org.jetbrains.kotlin.plugin.compose`, Compose BOM, `buildFeatures.compose true`. One throwaway `@Preview` to prove compilation under **AGP 9 built-in Kotlin**. Decide `camera-compose` vs `AndroidView(PreviewView)`. | **high — §9/R1** |
| C2 | theme | `ScandyTheme`, `Color.kt`, `Type.kt`. Not yet used. | low |
| C3 | about → compose | `AboutScreen(onBack)` + `setContent` inside the existing `AboutActivity`. Delete `activity_about.xml`. Leaf screen, no camera, no permissions — the real toolchain shakedown. | low |
| C4 | display → compose | `DisplayScreen(text, url, title, onBack)`; `DisplayActivity` reads the Serializable extra and flattens it. Delete `activity_display.xml`. | low |
| C5 | permissions | `rememberPermissionState` + `nextAction` + unit tests. Delete `permissions/`, `FragmentExtensions.kt`, `BaseActivity`; Activities extend `ComponentActivity`. | med |
| C6 | scanner → compose | `ScannerViewModel`, `ScannerScreen`, viewfinder, torch. Delete both scanner layouts, `menu_scanner.xml`, `ViewExtensions.kt`. **Full §9 device pass here.** | **high** |
| C7 | drop View stack | Remove appcompat, material, constraintlayout, `viewBinding`. Splash reparent (§6.3). R8 pass, release build. → **measure B1** | med |

C3 and C4 are deliberately "Compose inside the old Activity" — they prove theme, fonts, insets and
previews where failure is cheap, before C6 puts a camera behind it.

### Phase 2 — Compose Navigation

| # | Commit | Contents | Risk |
|---|---|---|---|
| C8 | single activity | Revisit the §5.5 decision with B1 in hand. `MainActivity` + `ScandyNavHost`; delete the three Activities; manifest down to one entry; drop `Serializable` from `ScandyBarcode`. | med |
| C9 | cleanup | Remove navigation-fragment/ui if still present. R8 pass, release build. Full §9 device pass. → **measure B2** | low |

---

## 8. Benchmark protocol

The point of the split is that B0→B1 and B1→B2 are separately attributable. That only holds if the
measurements are controlled, so:

**Controls.** Same physical device, same AGP/Kotlin/R8 versions, release build type with
`optimization.enable = true`, same `proguard-rules.pro` (audit it at C7/C9 but keep the *effective*
rule set comparable), airplane mode off but no other app churn. Record the commit SHA with each
number.

**Measure at B0, B1, B2:**

| Metric | How | Why it matters here |
|---|---|---|
| APK size, total | `ls -l` on the release APK | headline number for "simple app, no ads" |
| APK size, breakdown | `apkanalyzer apk file-size` / `apk summary`; `apkanalyzer files list` for dex vs res vs lib | shows *where* Compose costs and where the View stack refunded |
| DEX method + class count | `apkanalyzer dex packages --defined-only` | the 64k-adjacent number, and the clearest View-stack-vs-Compose signal |
| Resource count | count of surviving files under `res/` | layouts/menus/styles leaving is a real part of the P1 win |
| Cold start (TTID) | `adb shell am start -W -S` × 10, take median `TotalTime`; force-stop between runs | Compose's most-suspected regression |
| Clean build time | `./gradlew clean :app:assembleRelease --profile` × 3 | the Compose compiler plugin has a real compile cost; C0 removing KSP has a real refund |
| Source LOC | `cloc app/src/main/kotlin` + `res/` | the maintenance claim, quantified |

**Expectation-setting, so the numbers are not misread:**

- **B0→B1 should be the interesting delta.** Compose runtime + material3 land; appcompat, material,
  constraintlayout, viewBinding and every layout XML leave. Net APK direction is genuinely uncertain
  for an app this small — Compose has a floor cost that a three-screen app does not amortise. A
  regression here is a legitimate finding, not a failure of the migration.
- **B1→B2 will be small, and possibly negative on size.** `navigation-compose` +
  `kotlinx-serialization` are added; three Activity entries and `Serializable` are removed. The Phase
  2 case is back-stack correctness, a single Activity, and dropping `Serializable` from the domain
  model — *not* size. If Phase 2 is being justified on size, B1 will say so and the §5.5 fallback
  (screen enum + `BackHandler`, no new deps) becomes the better answer. This is exactly the decision
  your sequencing buys.
- **Cold start** may improve at B2 purely from one Activity instead of three, independent of Compose.
  Attribute it to the phase, not to the toolkit.

If TTFD or jank numbers are wanted rather than TTID, that needs a `macrobenchmark` module. I would
skip it for a three-screen app unless a B0→B1 startup regression actually shows up — then it earns
its keep, and only then.

---

## 9. Verification

Per commit: debug build + install. Full pass on a physical device at **C6, C7, C8, C9**:

1. Cold start → splash → scanner. Scan a URL barcode → Display, browse works. Scan text → text
   shown, copy works.
2. **Back out of Display, scan again.** The Step 0 re-arm path; a stuck scan surfaces here.
3. Deny camera permission → rationale copy. Deny again → app settings. Grant in Settings, return →
   scanner starts without a restart (the ON_START re-read, §5.3).
4. **Rotate while scanning**, portrait ↔ landscape. Toolbar present in portrait, absent in landscape
   (§6.4). Camera does not restart (G3).
5. Torch on/off; on a device without a flash unit, tapping it toasts rather than crashing.
6. Background/foreground while scanning; scan survives.
7. Process death from Developer Options while on Display → back returns sanely.
8. **Release build** with R8: repeat 1, 2, 5.

Unit tests: `nextAction` (§5.3), `FlashStatus.toFlashStatus` (existing). Compose UI tests for
`DisplayScreen` and `AboutScreen` are cheap once `ui-test-junit4` is present — both are pure
state → UI, and both are parameter-driven by design (§5.4). `ScannerScreen` is not worth
instrumenting; it is verified on-device.

---

## 10. Risks

**R1 — Compose compiler plugin under AGP 9's built-in Kotlin.** No KGP is applied (§2.4). The usual
`org.jetbrains.kotlin.plugin.compose` instructions assume `org.jetbrains.kotlin.android` is present.
This may work unchanged, may need the plugin version aligned to Kotlin 2.2.20, or may require
applying KGP explicitly. **This is why C1 is a spike and not part of a feature commit.** If it
resists, applying KGP explicitly alongside AGP is the escape hatch and costs nothing architecturally.

**R2 — `camera-compose` maturity.** `CameraXViewfinder` spent a long time in alpha. Confirm
`androidx.camera:camera-compose:1.6.1` publishes and is at least beta; otherwise take the
`AndroidView(PreviewView)` fallback (§5.1). Either way the core is untouched.

**R3 — Splash regression.** Reparenting `Theme.Scandy.Splash` away from Material Components (§6.3) is
the only way `material` actually leaves at C7. If it misbehaves, keeping `material` is an acceptable
outcome — but note it against B1, since it changes what that number means.

**R4 — Benchmark contamination.** The main threat to the whole exercise. Guard rails: C0 is
code-free, C7 and C9 are the only commits that touch dependencies within their phases, and no
feature work or dependency version bump happens between B0 and B2. If a version bump becomes
unavoidable mid-flight, re-measure the previous checkpoint on the new versions rather than comparing
across them.

**R5 — Scope creep.** Room and the navigation artifacts exist for a scan-history feature that was
never built. Removing them at C0 is correct; *building* history because Compose makes it easy is out
of scope (§3.3).

**R6 — Browser auto-open.** `DisplayActivity.showBrowse()` launches the browser immediately on
scanning a URL, in addition to showing the button (§5.4). Existing behaviour, ported as-is. Worth a
separate decision, but not during a migration.

---

## 11. Seam validation

The Step 0 doc claimed no core class would need to change. This plan's audit:

| Class | Phase 1 | Phase 2 | Why |
|---|---|---|---|
| `Scanner` | none | none | `SurfaceProvider` is a SAM; `LifecycleOwner` from `LocalLifecycleOwner`; `suspend` fits `LaunchedEffect` (§5.1) |
| `BarcodeReader` | none | none | never touched the UI |
| `ScannerStatus` | none | none | `collectAsStateWithLifecycle()` |
| `FlashStatus` | none | none | mapping to resources always lived in the UI layer |
| `ScandyBarcode` | none | **drop `: Serializable`** | unblocked by removing Intent navigation (§5.5) |

Phase 1 changes nothing under `camera/` at all. Phase 2 makes one change, and it is the one Step 0
predicted and deferred to exactly this point. The seam held.
