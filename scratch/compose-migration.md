# LLD — Framework-Agnostic Core Extraction (Scandy)

**Status:** Proposed · **Scope:** Step 0 of the Compose migration · **Author:** design doc for review

---

## 1. Purpose

Extract the UI-toolkit-independent camera logic of Scandy — barcode domain model, detection, camera
session lifecycle — out of `ScannerActivity` and into a designed core. Rewire the existing
View-based Activity onto it.

This is **Step 0** of a planned Compose migration. Nothing Compose-related is introduced here. The
success criterion is that when Compose work begins, the classes defined in this document do not need
to change.

**Observable outcome of this step: none.** The app runs on the same XML layouts, looks identical, and
behaves identically except for the defect fixes traced in §14.

**Permissions are explicitly out of scope.** The permission layer (`permissions/PermissionWrapper.kt`,
`permissions/PermissionHost.kt`, `extensions/FragmentExtensions.kt`) will be replaced wholesale by
`accompanist-permissions` during the Compose phase, so it is left untouched here rather than being
redesigned twice.

---

## 2. Current state analysis

### 2.1 Structure

`ScannerActivity.kt` is 205 lines and holds camera provider management, use-case construction,
permission flow, torch state, resource mapping and navigation. There is no layer beneath it —
no ViewModel, no repository, no domain service.

```
ScannerActivity ──┬── LifecycleCameraController      (stack A)
                  ├── ProcessCameraProvider          (stack B)
                  ├── ImageAnalysis + BarcodeAnalyzer
                  ├── PermissionWrapper → PermissionHost   [out of scope]
                  ├── FlashState (R.color / R.drawable)
                  └── startActivity(DisplayActivity)
```

### 2.2 Defect catalogue

| ID | Location | Description | Addressed |
|---|---|---|---|
| D1 | `ScannerActivity.kt:82-85`, `:164` | Two camera stacks bound to one `PreviewView`. `LifecycleCameraController` is bound, then `ProcessCameraProvider.unbindAll()` tears it down. | ✅ |
| D2 | `ScannerActivity.kt:94`, `:97` | Torch toggle and `torchState` observer target `cameraController`, detached by D1. | ✅ |
| D3 | `ScannerActivity.kt:118-124` | `throw RuntimeException("Invalid TorchState")` on the `else` branch; `torchState.value` is `null` when unbound. | ✅ |
| D4 | `BarcodeAnalyzer.kt:19`, `:32-35` | `imageProxy.close()` only inside MLKit listeners. A null-image frame is never released → CameraX stops delivering frames permanently. | ✅ |
| D5 | `BarcodeAnalyzer.kt:22` | `BarcodeScanning.getClient()` called per frame; no instance ever closed. | ✅ |
| D6 | `ScannerActivity.kt:200` | `setAnalyzer` uses `ContextCompat.getMainExecutor` → per-frame work on the UI thread. | ✅ |
| D7 | `BarcodeAnalyzer.kt:23-31` | Callback fires per barcode per frame; in-flight frames after unbind can re-trigger `startActivity`. | ✅ |
| D8 | `PermissionWrapper.kt:35-36` | `else if (askingFirstTime) { askingFirstTime = false }` performs no action — silent no-op on first rationale-required request. | ❌ out of scope |
| D9 | `ScannerActivity.kt:171-173` | `bindToLifecycle` failure is logged and swallowed; UI shows a dead viewfinder with no indication. | ✅ |

D8 is recorded for completeness and should carry forward to the Accompanist migration — that library
surfaces `shouldShowRationale` as state, which makes the dead branch straightforward to resolve there.

### 2.3 Dead code

| Symbol | File | Call sites | Removed here |
|---|---|---|---|
| `FlashState.other()` | `ui/scanner/FlashState.kt:9` | 0 | ✅ (file deleted) |
| `FlashState.enabled()` | `ui/scanner/FlashState.kt:11` | 0 | ✅ |
| `FlashState.value()` | `ui/scanner/FlashState.kt:13` | 0 | ✅ |
| `PermissionHost.Fragment` | `permissions/PermissionHost.kt:29-43` | 0 (app has no Fragments) | ❌ out of scope |
| `Window.setNoLimitsToLayout()` | `extensions/WindowExtensions.kt:6` | 0 | ❌ cleanup, not extraction |
| `Context.openUrl()` | `extensions/ContextExtensions.kt:23` | 0 | ❌ cleanup, not extraction |

---

## 3. Goals and non-goals

### 3.1 Goals

- **G1** — Single, authoritative camera binding path.
- **G2** — Core classes have zero references to `android.view.*`, `androidx.appcompat.*`, View Binding
  or `R`. Dependencies on `android.content.Context`, `androidx.lifecycle.LifecycleOwner` and
  `androidx.camera.*` are permitted, as all are satisfied identically by Compose.
- **G3** — Deterministic resource ownership: every acquired resource has exactly one owner and one
  release path.
- **G4** — State exposed as `StateFlow`/`SharedFlow`, so the later move to ViewModels requires no
  change to the core's public API.
- **G5** — Per-frame work off the main thread.

### 3.2 Non-goals

Compose dependencies, composables, Material 3 theming, ViewModels, Navigation changes, Activity
consolidation, **any change to the permission layer**, removal of unused dependencies (Room, KSP,
navigation-fragment/ui, `dataBinding`), removal of the dead code marked ❌ in §2.3, scan history, new
barcode types.

---

## 4. Architecture

```
┌──────────────────────────────────────────────────────────────┐
│ UI LAYER  (View today, Compose later — replaceable)          │
│   ScannerActivity · DisplayActivity · AboutActivity          │
│   · maps TorchState → R.color / R.drawable                   │
│   · collects flows via repeatOnLifecycle                     │
│   · PermissionWrapper  [unchanged, replaced later]           │
└───────────────┬──────────────────────────────────────────────┘
                │ Preview.SurfaceProvider, LifecycleOwner
                │ StateFlow / SharedFlow
┌───────────────▼──────────────────────────────────────────────┐
│ CORE  (framework-agnostic — survives the migration)          │
│   ScannerSession ──owns──> BarcodeImageAnalyzer              │
│         │                        └──uses──> BarcodeDetector  │
│         └──exposes──> StateFlow<TorchState>, <SessionState>  │
│                       SharedFlow<ScandyBarcode>              │
└───────────────┬──────────────────────────────────────────────┘
                │
┌───────────────▼──────────────────────────────────────────────┐
│ DOMAIN                                                        │
│   ScandyBarcode · TorchState · SessionState                   │
└───────────────────────────────────────────────────────────────┘
```

**Dependency rule:** UI → Core → Domain. No arrow points upward. The core never imports from `ui/`.

### 4.1 Package layout

```
dev.thedukerchip.scandy/
  camera/
    ScandyBarcode.kt          [RENAMED from Barcode.kt]   domain model + MLKit mapper
    TorchState.kt             [NEW]                       domain enum
    SessionState.kt           [NEW]                       domain enum
    BarcodeDetector.kt        [NEW]                       MLKit client wrapper
    BarcodeImageAnalyzer.kt   [REWRITTEN from BarcodeAnalyzer.kt]
    ScannerSession.kt         [NEW]                       camera orchestration
  permissions/                [UNTOUCHED — replaced by accompanist-permissions later]
    PermissionHost.kt
    PermissionWrapper.kt
  extensions/
    ContextExtensions.kt      [UNCHANGED]
    ConcurrentExtensions.kt   [UNCHANGED — still used by ScannerSession]
    FragmentExtensions.kt     [UNCHANGED — permission layer, out of scope]
    ViewExtensions.kt         [UNCHANGED — still used by View UI]
    WindowExtensions.kt       [UNCHANGED — dead, out of scope]
  ui/
    scanner/FlashState.kt     [DELETED — replaced by TorchState]
```

Net file change: 4 new, 1 rewritten, 1 renamed, 1 deleted.

---

## 5. Component design

### 5.1 `ScandyBarcode` — domain model

**Change:** file rename only. `camera/Barcode.kt` → `camera/ScandyBarcode.kt`.

`ScandyBarcode` and `Barcode.toScandyBarcode()` are unchanged. `: Serializable` is retained — it
carries the `Intent` payload, and removing it requires the navigation change, which is out of scope.

### 5.2 `TorchState` — domain enum

Replaces `ui/scanner/FlashState.kt` (which mixes state with `@ColorRes`/`@DrawableRes` accessors and
carries three dead members).

```kotlin
package dev.thedukerchip.scandy.camera

enum class TorchState {
    On,
    Off,
    Unavailable;   // no camera bound, or device has no flash unit

    companion object {
        fun fromCameraX(value: Int?): TorchState = when (value) {
            CameraTorchState.ON  -> On
            CameraTorchState.OFF -> Off
            else                 -> Unavailable
        }
    }
}
```

`Unavailable` collapses the two "cannot toggle" cases into one total, exhaustive type. This is what
eliminates D3: there is no `else` branch left to throw from.

Resource mapping is **not** in the core. It lives in `ScannerActivity` as a private `when` (§10.2),
and later becomes a composable's `when`.

### 5.3 `SessionState` — domain type

```kotlin
sealed interface SessionState {
    data object Idle : SessionState
    data object Binding : SessionState
    data object Ready : SessionState
    data class Failed(val cause: Throwable) : SessionState
}
```

Replaces the log-and-swallow at `ScannerActivity.kt:171-173` (D9) with an observable outcome.

### 5.4 `BarcodeDetector` — MLKit client wrapper

**Responsibility:** own exactly one MLKit `BarcodeScanner` for the lifetime of a session, and map
results into the domain type. Fixes D5.

```kotlin
class BarcodeDetector(
    private val callbackExecutor: Executor,
) : Closeable {

    private val client: BarcodeScanner = BarcodeScanning.getClient()

    /**
     * @param onResult invoked on [callbackExecutor] with mapped domain barcodes; not invoked on failure.
     * @param onDone   invoked on [callbackExecutor] exactly once per call, on every terminal outcome.
     */
    fun detect(
        image: InputImage,
        onResult: (List<ScandyBarcode>) -> Unit,
        onDone: () -> Unit,
    ) {
        client.process(image)
            .addOnSuccessListener(callbackExecutor) { onResult(it.map(Barcode::toScandyBarcode)) }
            .addOnFailureListener(callbackExecutor) { Log.e(TAG, "Unable to parse the barcode", it) }
            .addOnCompleteListener(callbackExecutor) { onDone() }
    }

    override fun close() = client.close()

    private companion object { const val TAG = "BarcodeDetector" }
}
```

**Design notes**

- `callbackExecutor` is injected rather than defaulted. MLKit's no-executor `addOnSuccessListener`
  overloads dispatch to the **main looper**; passing the session's background executor keeps frame
  handling — including `ImageProxy.close()` — off the UI thread. This is half of the D6 fix and is
  easy to lose accidentally.
- `addOnCompleteListener` runs after success *and* failure, giving callers exactly one release hook.
- `close()` is delegated to the caller (`ScannerSession`), not tied to any Android lifecycle.

**Invariants**
- I1: exactly one `BarcodeScanner` per instance.
- I2: `onDone` is invoked exactly once per `detect` call.

### 5.5 `BarcodeImageAnalyzer` — frame bridge

**Responsibility:** convert an `ImageProxy` to an `InputImage`, delegate to the detector, guarantee
frame release, and emit at most one detection per arming. Fixes D4 and D7.

```kotlin
class BarcodeImageAnalyzer(
    private val detector: BarcodeDetector,
    private val onDetected: (ScandyBarcode) -> Unit,
) : ImageAnalysis.Analyzer {

    @Volatile private var handled = false

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (handled || mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        detector.detect(
            image = image,
            onResult = { barcodes -> barcodes.firstOrNull { it !is ScandyBarcode.Unknown }?.let(::emit) },
            onDone = { imageProxy.close() },
        )
    }

    private fun emit(barcode: ScandyBarcode) {
        if (handled) return
        handled = true
        onDetected(barcode)
    }

    /** Re-arms the analyzer after a detection. */
    fun arm() { handled = false }
}
```

**Design notes**

- Every path through `analyze` closes the proxy exactly once: the early return closes directly, the
  detect path closes in `onDone`. This is the D4 fix.
- `handled` is `@Volatile`: written on the detector's callback executor, read on the CameraX analysis
  executor. Both are the same single-thread executor in the current design (§7), but `@Volatile`
  documents the cross-thread contract and survives that assumption changing.
- `emit` double-checks `handled` because several barcodes may appear in one frame.
- `arm()` is **required, not optional**. `ScannerActivity.onResume` resumes scanning when the user
  returns from `DisplayActivity`; without re-arming, the latch permanently suppresses the next scan.

**Invariants**
- I3: `onDetected` fires at most once per `arm()`.
- I4: `onDetected` never receives `ScandyBarcode.Unknown`.
- I5: every `ImageProxy` is closed exactly once.

### 5.6 `ScannerSession` — camera orchestration

The central class. Owns the **single** camera binding path, replacing both stacks in
`ScannerActivity`. Fixes D1, D2, D6, D9.

```kotlin
class ScannerSession(private val context: Context) : Closeable {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val detector = BarcodeDetector(executor)

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _torch = MutableStateFlow(TorchState.Unavailable)
    val torch: StateFlow<TorchState> = _torch.asStateFlow()

    private val _barcodes = MutableSharedFlow<ScandyBarcode>(extraBufferCapacity = 1)
    val barcodes: SharedFlow<ScandyBarcode> = _barcodes.asSharedFlow()

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analyzer: BarcodeImageAnalyzer? = null
    private var torchObserver: Observer<Int>? = null
    private var closed = false

    @MainThread fun bind(owner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider)
    @MainThread fun armScanning()
    @MainThread fun toggleTorch()
    @MainThread override fun close()
}
```

#### 5.6.1 Public API contract

| Method | Precondition | Behaviour | Postcondition |
|---|---|---|---|
| `bind` | main thread; not closed | Resolves the provider asynchronously, unbinds all, binds `Preview` + `ImageAnalysis` to `owner`. Idempotent while `Binding`/`Ready` with the same owner. | `state` → `Binding` then `Ready` or `Failed` |
| `armScanning` | main thread | Re-arms the analyzer latch. No-op if unbound. | next detection will emit |
| `toggleTorch` | main thread | `On`→off, `Off`→on, `Unavailable`→**no-op** | `torch` updates via the CameraX observer |
| `close` | main thread | Idempotent. Unbinds all, removes the torch observer, closes the detector, shuts the executor down. | `state` → `Idle`; instance unusable |

#### 5.6.2 State machine

```
                 bind()                    provider resolved + bindToLifecycle OK
   ┌──────┐  ─────────────►  ┌─────────┐  ──────────────────────────────►  ┌───────┐
   │ Idle │                  │ Binding │                                   │ Ready │
   └──────┘  ◄─────────────  └─────────┘  ──────────────────────────────►  └───────┘
       ▲        close()            │        bindToLifecycle throws              │
       │                           ▼                                            │
       │                     ┌──────────┐                                       │
       └─────────────────────│  Failed  │◄──────────────────────────────────────┘
                 close()     └──────────┘
```

Transitions are driven only by `bind()`, the provider future's completion, and `close()`. `Ready` is
terminal for a successful session; CameraX handles START/STOP internally via the bound
`LifecycleOwner`, so there is deliberately no manual pause/resume state.

#### 5.6.3 Binding sequence

```
ScannerActivity        ScannerSession       ProcessCameraProvider     CameraX
      │                       │                       │                  │
      │ bind(owner, surface)  │                       │                  │
      ├──────────────────────►│                       │                  │
      │                       │ state = Binding       │                  │
      │                       │ getInstance(context)  │                  │
      │                       ├──────────────────────►│                  │
      │                       │                       │                  │
      │                       │◄──── future (main) ───┤                  │
      │                       │ unbindAll()           │                  │
      │                       ├──────────────────────►│                  │
      │                       │ bindToLifecycle(owner, BACK, preview, analysis)
      │                       ├──────────────────────────────────────────►│
      │                       │◄────────── Camera ────────────────────────┤
      │                       │ hasFlashUnit()? → observeForever(torchState)
      │                       │ state = Ready         │                  │
      │◄── StateFlow emit ────┤                       │                  │
```

#### 5.6.4 Use-case construction

Carried over from `ScannerActivity` unchanged, so imaging behaviour is identical:

- `Preview.Builder().build()` with `surfaceProvider` assigned from the caller's argument.
- `ImageAnalysis.Builder()` with `ResolutionSelector` targeting `Size(1280, 720)` and
  `FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER`, and `STRATEGY_KEEP_ONLY_LATEST`.
- `analysis.setAnalyzer(executor, analyzer)` — **the session's executor**, not the main executor. D6.
- `CameraSelector.DEFAULT_BACK_CAMERA`.

The provider future is resolved with the existing `ListenableFuture.addListener` helper in
`extensions/ConcurrentExtensions.kt`, which therefore **survives this step**. Converting to `await()`
would require `kotlinx-coroutines-play-services`, which is not on the classpath.

#### 5.6.5 Torch handling

After `bindToLifecycle` returns a `Camera`:

```kotlin
if (!camera.cameraInfo.hasFlashUnit()) {
    _torch.value = TorchState.Unavailable
} else {
    torchObserver = Observer<Int> { _torch.value = TorchState.fromCameraX(it) }
        .also { camera.cameraInfo.torchState.observeForever(it) }
}
```

`observeForever` is used rather than lifecycle-scoped observation because the session, not the
Activity, owns the subscription — this is precisely what decouples it from the UI. The observer is
removed in `close()`.

`LiveData.asFlow()` would be tidier but lives in `lifecycle-livedata-ktx`, which is not on the
resolved classpath; adding a dependency for one observer is unjustified.

`toggleTorch()` is an exhaustive `when` over `TorchState` with `Unavailable` as a no-op — no `else`,
therefore no throw. D3.

#### 5.6.6 Invariants

- I6: at most one `ProcessCameraProvider` binding is live per session. **This is the D1/D2 fix and the
  central invariant of the class.**
- I7: `close()` is idempotent and releases executor, detector and torch observer.
- I8: no method touches `android.view.*`, View Binding, or `R`.

### 5.7 Deletions

`ui/scanner/FlashState.kt` (40 lines) — replaced by `TorchState`. It is the only file deleted in this
step.

---

## 6. Data flow

```
CameraX analysis executor
        │  ImageProxy
        ▼
BarcodeImageAnalyzer ──InputImage──► BarcodeDetector ──Task──► MLKit
        ▲                                   │
        │       callbacks on session executor│
        └───────────────────────────────────┘
        │ onDetected(ScandyBarcode)
        ▼
ScannerSession._barcodes (MutableSharedFlow, extraBufferCapacity = 1)
        │
        │ collected in repeatOnLifecycle(STARTED)  [main thread]
        ▼
ScannerActivity ──► startActivity(DisplayActivity)
```

`MutableSharedFlow` rather than `StateFlow`: a scan is an **event**, not state. `StateFlow` would
replay the last barcode on every re-collection (e.g. after rotation), re-launching `DisplayActivity`.
`extraBufferCapacity = 1` with the default `SUSPEND` overflow lets `tryEmit` succeed from the
background executor without blocking it.

---

## 7. Threading model

| Thread | Work |
|---|---|
| **Main** | `bind`, `armScanning`, `toggleTorch`, `close`; `ProcessCameraProvider` future callback; `observeForever` registration and torch emissions; all flow collection in the UI. |
| **Session executor** (single-thread) | `ImageAnalysis` frame delivery; `InputImage` construction; MLKit result callbacks; `ImageProxy.close()`; `_barcodes.tryEmit`. |
| **MLKit internal** | Detection itself; not owned here. |

**Rules**

- R1 — `ScannerSession`'s public methods are `@MainThread`. `bindToLifecycle` and `observeForever`
  both require it.
- R2 — A single-thread executor is used deliberately: `STRATEGY_KEEP_ONLY_LATEST` already drops stale
  frames, and serialising analysis keeps the `handled` latch simple.
- R3 — `MutableStateFlow`/`MutableSharedFlow` are thread-safe; `_torch` is written from main,
  `_barcodes` from the executor.
- R4 — `handled` is `@Volatile` (§5.5).
- R5 — The executor is shut down in `close()`. Frames may still be in flight; `ImageProxy.close()`
  after provider unbind is safe, and the analyzer holds no Activity reference.

---

## 8. Resource ownership

| Resource | Owner | Acquired | Released |
|---|---|---|---|
| `ExecutorService` | `ScannerSession` | construction | `close()` |
| `BarcodeScanner` (MLKit) | `BarcodeDetector` | construction | `close()`, called by session |
| CameraX use-case bindings | `ScannerSession` | `bind()` | `close()` + the bound `LifecycleOwner` |
| Torch `Observer` | `ScannerSession` | `bind()` | `close()` |
| `ImageProxy` (per frame) | `BarcodeImageAnalyzer` | `analyze()` | every path of `analyze()` (I5) |

`ScannerSession` is owned by `ScannerActivity` and closed in `onDestroy`. When ViewModels arrive,
ownership moves to `ScannerViewModel.onCleared()` — **a change in the UI layer, not in the session.**

---

## 9. Error handling

| Failure | Handling |
|---|---|
| `ProcessCameraProvider.getInstance` future fails | `state = Failed(cause)`; UI may show an error affordance |
| `bindToLifecycle` throws | caught, `state = Failed(cause)` (replaces the swallow at `:171-173`, D9) |
| MLKit `process` fails | logged in `BarcodeDetector`; frame released via `onDone`; scanning continues |
| Frame has no `mediaImage` | frame closed immediately, skipped (D4) |
| No flash unit | `TorchState.Unavailable`; `toggleTorch` is a no-op (D3) |
| `bind` after `close` | guarded by `closed`; no-op with a warning |

No new user-facing error UI is added in this step — `SessionState.Failed` is exposed and logged, and
wiring it to a visible message is deferred to the Compose screens.

---

## 10. UI integration

### 10.1 `ScannerActivity` — before / after

| Concern | Before | After |
|---|---|---|
| Camera setup | `initCameraController`, `startCamera`, `onCameraProviderPrepared`, `getPreviewConfig`, `getImagerAnalyzer` (~90 lines) | `session.bind(this, binding.barcodeFinderPv.surfaceProvider)` |
| Torch | `cameraController.torchState.observe`, `toggleFlashState`, `setFlashState` | collect `session.torch`; `session.toggleTorch()` |
| Scan result | `onBarcodeDetected` lambda + `cameraProvider.unbind` | collect `session.barcodes` |
| Resources | `FlashState.color()` / `.icon()` | private `when (TorchState)` in the Activity |
| Permission | `PermissionWrapper` | **unchanged** |
| Toolbar / insets / splash | as-is | **unchanged** |

Net: `ScannerActivity` drops from 205 lines to roughly 130, all of it view wiring and the retained
permission flow.

### 10.2 Collection pattern

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch { session.torch.collect { binding.torchBtn.render(it) } }
        launch { session.barcodes.collect { openDisplay(it) } }
    }
}
```

`render` is a private `FloatingActionButton.(TorchState) -> Unit` holding the `R.color`/`R.drawable`
mapping — the resource knowledge that §5.2 kept out of the core.

### 10.3 Scan re-arming

`onResume` calls `session.armScanning()`, restoring "scan again after returning from Display"
(previously an implicit consequence of unbind/rebind).

### 10.4 Other Activities

`DisplayActivity` and `AboutActivity` are untouched. `ui/BaseActivity.kt` stays — `PermissionWrapper`'s
extension function is declared on it, and removing it depends on both the permission and navigation
changes, which are out of scope.

---

## 11. Migration seam analysis

What the Compose step must change in each core class — the design is only successful if this column
is empty.

| Class | Compose-time change |
|---|---|
| `ScandyBarcode` | none (until navigation drops `Serializable`) |
| `TorchState` | none |
| `SessionState` | none |
| `BarcodeDetector` | none |
| `BarcodeImageAnalyzer` | none |
| `ScannerSession` | none |

This holds because of three deliberate choices:

1. `bind()` accepts `Preview.SurfaceProvider`, a CameraX type. `PreviewView` provides one today;
   `CameraXViewfinder` or an `AndroidView`-wrapped `PreviewView` provides one later.
2. `LifecycleOwner` is satisfied by both a `ComponentActivity` and `LocalLifecycleOwner.current`.
3. State is `StateFlow`/`SharedFlow`, consumed by `repeatOnLifecycle` today and
   `collectAsStateWithLifecycle()` later.

What *does* change at Compose time is confined to the UI layer: who owns the session (Activity →
ViewModel), how the resource mapping is expressed, and the wholesale replacement of the permission
layer with `accompanist-permissions`.

---

## 12. Test plan

### 12.1 Unit tests

| Target | Cases |
|---|---|
| `TorchState.fromCameraX` | `ON`, `OFF`, unknown int, `null` |

**This is thin, and worth stating plainly.** With the permission layer out of scope and `mockk`
declined, `TorchState.fromCameraX` is the only new pure function available to unit-test. The
testability argument for this refactor is therefore mostly *deferred* value: `ScannerSession` becomes
mockable behind a ViewModel later, and `PermissionPolicy`-style testing arrives with Accompanist.
The immediate justification for this step is correctness and the migration seam, not test coverage.

Place new tests in `app/src/test/kotlin` (existing tests are in `app/src/test/java` while main source
is `app/src/main/kotlin`) and confirm AGP picks the directory up.

### 12.2 Instrumented / manual

`BarcodeImageAnalyzer` and `ScannerSession` depend on CameraX and MLKit types that are impractical to
fake without a mocking library and an emulator with a virtual camera. They are verified on-device per
§13. Introducing interfaces over CameraX purely for testability would be over-engineering at this size.

---

## 13. Verification

On a physical device, against the unchanged XML UI:

1. Scan a URL barcode → Display opens with the browse action. Scan a text barcode → text shown, copy
   works.
2. **Back out of Display, scan again** — exercises `armScanning()`/I3. Failure here means a stuck latch.
3. Toggle the torch repeatedly; the FAB tint and icon track it. *Clearest signal D1/D2 are gone.*
4. Tap the torch before the camera binds, or on a device with no flash — silent no-op, no crash (D3).
5. Permission flow regression check: fresh install → deny → re-request → confirm behaviour is
   **identical to today**, including the known D8 no-op. Nothing here should change.
6. Rotate on the scanner screen (exercises both `layout/` and `layout-land/` variants).
7. Background/foreground mid-scan; confirm the preview resumes.
8. Leave the scanner open ~2 minutes — memory flat rather than climbing (D5).
9. Profile the main thread while scanning — no per-frame work on it (D6).
10. `./gradlew assembleRelease` and scan on the release build. R8 is on via `optimization { enable = true }`
    with an empty `proguard-rules.pro`.

---

## 14. Defect traceability

| Defect | Fixed by | Verified by |
|---|---|---|
| D1 two camera stacks | §5.6 / I6 | §13.3 |
| D2 torch on detached stack | §5.6.5 | §13.3 |
| D3 torch `throw` | §5.2 `Unavailable` + §5.6.5 | §13.4 |
| D4 analyzer stall | §5.5 / I5 | §13.1, §13.2 |
| D5 per-frame MLKit client | §5.4 / I1 | §13.8 |
| D6 main-thread analysis | §5.4 executor + §5.6.4 | §13.9 |
| D7 duplicate `startActivity` | §5.5 / I3 | §13.1 |
| D8 silent permission no-op | *not addressed* — carry to Accompanist migration | §13.5 confirms unchanged |
| D9 swallowed bind failure | §5.3 `SessionState.Failed` | code review |

D2 follows logically from D1 but depends on `LifecycleCameraController` internals not verified by
reading; §13.3 settles it either way.

---

## 15. Risks

**R1 — `LifecycleCameraController` removal.** It is currently first to bind and may mask a timing
difference in preview startup. §13.6/§13.7 cover this.

**R2 — Coroutines and lifecycle arrive transitively.** `kotlinx-coroutines-android:1.9.0` and
`lifecycle-runtime-ktx:2.9.0` resolve today only via `room-runtime` and `navigation-fragment-ktx` —
the unused dependencies being kept. **Declare both explicitly** in `gradle/libs.versions.toml` so the
core does not rest on that. Nothing is removed:

```toml
kotlinx-coroutines-android = "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0"
androidx-lifecycle-runtime = "androidx.lifecycle:lifecycle-runtime-ktx:2.9.0"
```

**R3 — MLKit model download.** `BarcodeScanning` may fetch its model on first use; keep the failure
logging so a cold-start failure is distinguishable from a regression introduced here.

**R4 — `namespace 'scandy'`** differs from the applicationId, so generated symbols import as
`scandy.R`. Preserve this; do not "fix" it here.

**R5 — Accompanist maintenance status.** Google has been progressively winding Accompanist down and
folding modules into androidx. Worth confirming `accompanist-permissions` is still the maintained
choice before the Compose phase commits to it. No impact on this step.

---

## 16. Implementation sequence

Ordered so the build stays green at every commit.

| # | Commit | Contents |
|---|---|---|
| 1 | deps | Declare coroutines + lifecycle-runtime explicitly (R2). No code change. |
| 2 | domain | `TorchState`, `SessionState`; rename `Barcode.kt` → `ScandyBarcode.kt`. Unit test `fromCameraX`. |
| 3 | detector | `BarcodeDetector`; rewrite `BarcodeAnalyzer` → `BarcodeImageAnalyzer`. Not yet wired. |
| 4 | session | `ScannerSession`. Not yet wired. |
| 5 | rewire | `ScannerActivity` onto the session. Delete `FlashState`. **Full §13 pass here.** |

Commits 2–4 are additive and independently reviewable; commit 5 is the switchover and the one to
review closely. Note that commits 3 and 4 leave temporarily-unused classes in the tree — acceptable
for reviewability, and resolved by commit 5.

---

## 17. Out of scope

No Compose dependencies, composables, Material 3 theme, ViewModels, navigation changes or Activity
consolidation. `ScandyBarcode` keeps `Serializable`; `BaseActivity`, all four XML layouts and View
Binding all stay.

**The permission layer is untouched** — `PermissionWrapper`, `PermissionHost` (including its dead
`Fragment` subclass) and `FragmentExtensions.kt` are all left exactly as they are, to be replaced by
`accompanist-permissions` during the Compose phase.

Unused dependencies (Room, KSP, navigation-fragment/ui, `dataBinding true`) stay, as decided. The dead
`WindowExtensions.setNoLimitsToLayout` and `Context.openUrl` (§2.3) also stay — removing them is
cleanup rather than extraction and would widen the diff without serving the goal.
