# Component Design — Framework-Agnostic Camera Core (Scandy)

*Derived from `compose-migration.md`, scoped to component design only. Permissions and UI wiring are
out of scope here.*

*Status: **locked**. Naming, threading, resource ownership and the public API below are decided.
§11 records the questions that produced this revision and the answers that closed them.*

---

## 1. Intent

Scandy's camera logic — barcode domain model, detection, camera session lifecycle — currently lives
inside `ScannerActivity`. There is no layer beneath it: no ViewModel, no repository, no domain
service. This document designs the core that logic moves into.

The core is UI-toolkit-independent by construction. **The success criterion is that when Compose work
begins, the classes defined here do not need to change** (§8 tests that claim class by class).

### Goals

- **G1** — Single, authoritative camera binding path.
- **G2** — Core classes have zero references to `android.view.*`, `androidx.appcompat.*`, View Binding
  or `R`. Dependencies on `android.content.Context`, `androidx.lifecycle.LifecycleOwner` and
  `androidx.camera.*` are permitted, as all are satisfied identically by Compose.
- **G3** — Deterministic resource ownership: every acquired resource has exactly one owner and one
  release path.
- **G4** — State exposed as `StateFlow`, so the later move to ViewModels requires no change to the
  core's public API.
- **G5** — Per-frame work off the main thread.
- **G6** — **The API reads as the user's journey, not as CameraX mechanics.** A reader who has never
  seen CameraX should be able to follow the call site.

---

## 2. Expected architecture

```
┌──────────────────────────────────────────────────────────────┐
│ UI LAYER  (View today, Compose later — replaceable)          │
│   ScannerActivity · DisplayActivity · AboutActivity          │
│   · maps FlashStatus → R.color / R.drawable                  │
│   · collects status flows via repeatOnLifecycle              │
│   · PermissionWrapper  [unchanged, replaced later]           │
└───────────────┬──────────────────────────────────────────────┘
                │ Preview.SurfaceProvider, LifecycleOwner
                │ suspend scan() : ScandyBarcode
                │ StateFlow<ScannerStatus>, StateFlow<FlashStatus>
┌───────────────▼──────────────────────────────────────────────┐
│ CORE  (framework-agnostic — survives the migration)          │
│   Scanner ──uses──> BarcodeDetector                          │
│      └──private──> ImageAnalysis.barcodes(): Flow<…>         │
└───────────────┬──────────────────────────────────────────────┘
                │
┌───────────────▼──────────────────────────────────────────────┐
│ DOMAIN                                                        │
│   ScandyBarcode · ScannerStatus · FlashStatus                 │
└───────────────────────────────────────────────────────────────┘
```

**Dependency rule:** UI → Core → Domain. No arrow points upward. The core never imports from `ui/`.

### 2.1 Package layout

```
dev.thedukerchip.scandy/
  camera/
    ScandyBarcode.kt      [EXISTS]   domain model + MLKit mapper
    ScannerStatus.kt      [EXISTS]   domain type
    FlashStatus.kt        [EXISTS]   domain enum
    BarcodeDetector.kt    [EXISTS — reworked to suspend, §3.4]
    Scanner.kt            [NEW]      the whole camera journey (§3.5)
    BarcodeAnalyzer.kt    [DELETED]  folded into Scanner.kt as a private Flow builder
  extensions/
    ConcurrentExtensions.kt [DELETED] no ListenableFuture bridging left
  ui/
    scanner/FlashState.kt   [DELETED] replaced by FlashStatus
```

Net file change within the core: 1 new, 1 reworked, 3 deleted.

### 2.2 Dependencies this design requires

The project currently declares **no coroutines dependency at all**, and no explicit `lifecycle`
artifact. The flow-based API is not optional extra polish — these are its floor.

| Artifact | Needed for |
|---|---|
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | `Dispatchers.Main`, `StateFlow`, everything below |
| `org.jetbrains.kotlinx:kotlinx-coroutines-play-services` | `Task.await()` in `BarcodeDetector` |
| `androidx.lifecycle:lifecycle-runtime-ktx` | `lifecycleScope`, `repeatOnLifecycle` |
| `androidx.lifecycle:lifecycle-livedata-ktx` | `LiveData.asFlow()` for torch state |

**Not** needed: `kotlinx-coroutines-guava`. The only `ListenableFuture` that mattered was
`ProcessCameraProvider.getInstance()`, and `camera-lifecycle` 1.4.0+ ships
`ProcessCameraProvider.awaitInstance(context)` as a `suspend fun`. Scandy is on CameraX 1.6.1.

---

## 3. Components

### 3.1 `ScandyBarcode` — domain model

**No change.** Already lives in `camera/ScandyBarcode.kt` with the mapper `Barcode.toScandyBarcode()`.

`: Serializable` is retained. It carries the `Intent` payload, and removing it requires a navigation
change that is not part of this design.

### 3.2 `ScannerStatus` — what the camera is doing

```kotlin
sealed class ScannerStatus {
    data object Off : ScannerStatus()                       // not scanning
    data object Starting : ScannerStatus()                  // opening the camera
    data object Scanning : ScannerStatus()                  // live, watching for a code
    data class Unavailable(val reason: Throwable) : ScannerStatus()
}
```

Names describe what a person would see, not the binding protocol (`Binding`/`Ready`). Makes the
binding outcome observable rather than logged and swallowed (D9).

### 3.3 `FlashStatus` — domain enum

```kotlin
enum class FlashStatus { On, Off, Unavailable }
```

Replaces `ui/scanner/FlashState.kt`, a sealed class that mixed state with `@ColorRes`/`@DrawableRes`
accessors.

`Unavailable` collapses the two "cannot toggle" cases — no camera bound, or no flash unit — into one
total, exhaustive type. This is what eliminates D3: there is no `else` branch left to throw from.
`FlashState.fromTorchState` took a non-null `Int` and folded everything that was not `ON` into `OFF`;
the mapper below accepts the `Int?` that CameraX actually produces when no camera is bound.

```kotlin
internal fun Int?.toFlashStatus(): FlashStatus = when (this) {
    TorchState.ON  -> FlashStatus.On
    TorchState.OFF -> FlashStatus.Off
    else           -> FlashStatus.Unavailable
}
```

Resource mapping is **not** in the core. It stays in the UI layer as a private `when`, and later
becomes a composable's `when`.

### 3.4 `BarcodeDetector` — MLKit client wrapper

**Responsibility:** own exactly one MLKit `BarcodeScanner`, and answer one question — *is there a
barcode in this image?* Fixes D5.

```kotlin
class BarcodeDetector : Closeable {

    private val client: BarcodeScanner = BarcodeScanning.getClient()

    /** The first barcode in [image] that Scandy understands, or `null`. */
    suspend fun read(image: InputImage): ScandyBarcode? =
        client.process(image).await()
            .asSequence()
            .map(Barcode::toScandyBarcode)
            .firstOrNull { it !is ScandyBarcode.Unknown }

    override fun close() = client.close()
}
```

**Design notes**

- No `CoroutineScope` constructor parameter, no `launch`, no callback. `read` is a plain suspend
  function; the caller decides where it runs. This is what lets the analyzer bridge stay serial
  without any dispatcher configuration (§3.5.2).
- No `Executor` parameter either. The three-listener dance
  (`addOnSuccessListener`/`addOnFailureListener`/`addOnCompleteListener`) with an injected callback
  executor collapses into one `await()`. The old invariant "the release hook fires exactly once" stops
  being an assertion and becomes a language guarantee.
- `await()` **throws** on MLKit failure. Callers treat that as "no barcode in this frame" (§3.5.2).
- The `Unknown` filter runs **after** mapping and **across all** barcodes in the frame. Taking
  `list.firstOrNull()` and then mapping is wrong: a frame whose first barcode is a WiFi payload would
  yield `Unknown` and hide a readable code behind it.

**Invariants**
- I1: exactly one `BarcodeScanner` per instance, closed exactly once.
- I2: `read` never returns `ScandyBarcode.Unknown`.

### 3.5 `Scanner` — the camera journey

The central class, and the only new file. Owns the **single** camera binding path, replacing the two
stacks (`LifecycleCameraController` and `ProcessCameraProvider`) that `ScannerActivity` binds today.
Fixes D1, D2, D6, D9.

```kotlin
class Scanner(private val context: Context) {

    private val _status = MutableStateFlow<ScannerStatus>(ScannerStatus.Off)
    val status: StateFlow<ScannerStatus> = _status.asStateFlow()

    private val _flash = MutableStateFlow(FlashStatus.Unavailable)
    val flash: StateFlow<FlashStatus> = _flash.asStateFlow()

    private var camera: Camera? = null

    /**
     * Shows the camera in [viewfinder] and suspends until a barcode is found.
     *
     * Returns exactly one barcode. Cancelling the caller stops the camera.
     *
     * @throws Exception if the camera cannot be opened; [status] carries the same cause.
     */
    @MainThread
    suspend fun scan(viewfinder: Preview.SurfaceProvider, owner: LifecycleOwner): ScandyBarcode

    @MainThread
    fun toggleFlash()
}
```

There is **no** `close()`, no `arm()`, no `bind()`, and no `Closeable`. Everything the scanner
acquires is acquired inside `scan()` and released before it returns (§6).

#### 3.5.1 `scan()` — the whole flow in one function

```kotlin
@MainThread
suspend fun scan(viewfinder: Preview.SurfaceProvider, owner: LifecycleOwner): ScandyBarcode {
    _status.value = ScannerStatus.Starting

    val provider = try {
        ProcessCameraProvider.awaitInstance(context)
    } catch (cause: Exception) {
        _status.value = ScannerStatus.Unavailable(cause)
        throw cause
    }

    val preview = Preview.Builder().build().apply { surfaceProvider = viewfinder }
    val frames = ImageAnalysis.Builder()
        .setResolutionSelector(hdResolution)
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()

    val camera = try {
        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, frames)
    } catch (cause: Exception) {
        _status.value = ScannerStatus.Unavailable(cause)
        throw cause
    }
    this.camera = camera

    return try {
        BarcodeDetector().use { detector ->
            coroutineScope {
                val watchingFlash = launch { trackFlash(camera) }
                try {
                    _status.value = ScannerStatus.Scanning
                    frames.barcodes(detector).first()
                } finally {
                    watchingFlash.cancel()
                }
            }
        }
    } finally {
        provider.unbind(preview, frames)
        this.camera = null
        _flash.value = FlashStatus.Unavailable
        _status.value = ScannerStatus.Off
    }
}
```

Read top to bottom: *open the camera, show it, watch the flash, wait for a barcode, then put
everything back.* Every acquisition has its release in the same function, in the reverse order.

**Notably absent: `unbindAll()`.** See §11.4.

#### 3.5.2 The frame bridge — a private `Flow` builder, not a class

`BarcodeAnalyzer` disappears as a type. What is left is small enough to be a private extension in
`Scanner.kt`:

```kotlin
@OptIn(ExperimentalGetImage::class)
private fun ImageAnalysis.barcodes(detector: BarcodeDetector): Flow<ScandyBarcode> = callbackFlow {
    setAnalyzer(Dispatchers.IO.asExecutor()) { frame ->
        frame.use {
            val media = it.image ?: return@use
            val image = InputImage.fromMediaImage(media, it.imageInfo.rotationDegrees)
            val barcode = runBlocking { runCatching { detector.read(image) }.getOrNull() }
            if (barcode != null) trySend(barcode)
        }
    }
    awaitClose { clearAnalyzer() }
}
```

**Why this shape, and what it deletes**

- **`analyze()` blocks, deliberately.** CameraX invokes the analyzer *serially*: the next frame is
  never delivered until the current `ImageProxy` is closed, and `STRATEGY_KEEP_ONLY_LATEST` drops and
  closes everything that arrives meanwhile. Serialisation is a **framework guarantee**, so no
  `limitedParallelism`, no dedicated `ExecutorService`, no `Mutex`, and nothing for the caller to
  configure correctly. Blocking is safe because `Dispatchers.IO` exists to be blocked.
- **`frame.use { }` replaces invariant I5.** Every path — null media image, MLKit throw, cancellation
  — closes the proxy exactly once, enforced by the language. This is the D4 fix.
- **`@Volatile handled` and `arm()` are gone.** "Emit at most one detection, then stop" was being
  encoded as a mutable field read and written from two threads. It is not state; it is a program's
  position in time. `first()` collects one value and cancels upstream, `awaitClose` calls
  `clearAnalyzer()`, and the camera stops feeding. This is the D7 fix.
- **`ImageProxy` is never queued.** The flow carries `ScandyBarcode` — a plain value. Buffering
  `ImageProxy` would hold leases on a fixed-size hardware buffer pool; a slow or conflating consumer
  drains the pool and CameraX stops producing frames permanently. See §11.2.
- **MLKit failures are swallowed per frame** (`runCatching`) rather than terminating the scan. A
  single unreadable frame is not a session failure; the next frame arrives in ~33 ms.

**Known limitation (accepted):** cancellation does not interrupt an in-flight `runBlocking`. If the
user leaves the screen mid-detection, that MLKit call finishes (tens of milliseconds) and its result
is discarded by the already-closed channel. Bounded and harmless, but it is real.

#### 3.5.3 Flash handling

```kotlin
private suspend fun trackFlash(camera: Camera) {
    if (!camera.cameraInfo.hasFlashUnit()) {
        _flash.value = FlashStatus.Unavailable
        return
    }
    camera.cameraInfo.torchState.asFlow().collect { _flash.value = it.toFlashStatus() }
}

@MainThread
fun toggleFlash() {
    when (_flash.value) {
        FlashStatus.On -> camera?.cameraControl?.enableTorch(false)
        FlashStatus.Off -> camera?.cameraControl?.enableTorch(true)
        FlashStatus.Unavailable -> Unit          // no else — nothing to throw from
    }
}
```

`CameraInfo.getTorchState()` returns `LiveData<Int>` whose values are `androidx.camera.core.TorchState`
`@IntDef` constants — that is the CameraX API, not a design choice. `asFlow()` removes the
`Observer<Int>` field, removes `observeForever`, and removes the corresponding line from the cleanup
path: the collecting coroutine is cancelled with the scope.

Observing is load-bearing rather than optional bookkeeping — the torch is turned off by the camera on
unbind and on some device-level events, so a locally tracked `Boolean` would desync.

`enableTorch()` returns a `ListenableFuture<Void>` whose result is ignored; no bridging needed.

#### 3.5.4 Use-case construction

Carried over from `ScannerActivity` unchanged, so imaging behaviour is identical:

- `Preview.Builder().build()` with `surfaceProvider` assigned from the caller's argument.
- `ImageAnalysis.Builder()` with `ResolutionSelector` targeting `Size(1280, 720)` and
  `FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER`, and `STRATEGY_KEEP_ONLY_LATEST`.
- `CameraSelector.DEFAULT_BACK_CAMERA`.
- `setAnalyzer(Dispatchers.IO.asExecutor(), …)` — **never** `ContextCompat.getMainExecutor`. D6.

Use cases are constructed **per `scan()` call** and unbound in the same call's `finally`. They are
never fields, so they can never be double-bound.

#### 3.5.5 Invariants

- I3: at most one CameraX binding is live per `Scanner`. **This is the D1/D2 fix and the central
  invariant of the class.**
- I4: `scan()` returns at most one barcode per call, and never `ScandyBarcode.Unknown`.
- I5: every `ImageProxy` is closed exactly once.
- I6: nothing acquired by `scan()` outlives it — on return, on throw, or on cancellation.
- I7: no method touches `android.view.*`, View Binding, or `R`.

### 3.6 Deletions

| File | Reason |
|---|---|
| `camera/BarcodeAnalyzer.kt` | folded into the private `ImageAnalysis.barcodes()` builder (§3.5.2) |
| `extensions/ConcurrentExtensions.kt` | `awaitInstance()` removes the only `ListenableFuture` bridge |
| `ui/scanner/FlashState.kt` | replaced by `FlashStatus` |

---

## 4. Call site

The whole UI contract, in the form it will keep after the Compose migration:

```kotlin
private fun startScanning() {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            runCatching {
                scanner.scan(
                    viewfinder = binding.barcodeFinderPv.surfaceProvider,
                    owner = this@ScannerActivity,
                )
            }.onSuccess(::showCode)
            // failures are already published on scanner.status
        }
    }
}
```

Reads as: *while this screen is visible, scan into the viewfinder, then show the code.*

**Re-arming is now a lifecycle consequence, not an API call.** `showCode` starts `DisplayActivity`
→ this screen leaves `STARTED` → the block is cancelled → `scan()`'s `finally` releases the camera.
Coming back re-enters `STARTED` → the block re-runs → `scan()` again. The failure mode the old design
warned about — forgetting `arm()` and permanently killing the scanner — is unrepresentable.

Exactly one scan happens per screen visit: after `scan()` returns, the block completes and does not
re-run until the next `STARTED` transition.

---

## 5. Threading model

| Thread | Work |
|---|---|
| **Main** | `scan()` call and return; `awaitInstance`; `bindToLifecycle` / `unbind`; `torchState` observation via `asFlow`; `toggleFlash`; all flow collection in the UI. |
| **`Dispatchers.IO`** (one frame at a time, guaranteed by CameraX) | `analyze()`; `InputImage` construction; the blocking MLKit call; `ImageProxy.close()`; `trySend`. |
| **MLKit internal** | Detection itself; not owned here. |

**Rules**

- R1 — `scan()` and `toggleFlash()` are `@MainThread`. `bindToLifecycle`, `unbind` and `LiveData`
  observation all require it. `lifecycleScope` is `Dispatchers.Main.immediate`, so the call site
  satisfies this without dispatching.
- R2 — Frame processing is serial because **CameraX invokes the analyzer serially**, not because of
  any executor or dispatcher configuration. Nothing in the API can be misconfigured to break this.
- R3 — `Dispatchers.IO` is chosen inside the core and never accepted from a caller. It is used because
  `analyze()` blocks; `Dispatchers.Default` would be wrong to block.
- R4 — `MutableStateFlow` is thread-safe; both `_status` and `_flash` are written from main only.
- R5 — There is no executor lifecycle to manage. Cancelling the caller cancels everything.

---

## 6. Resource ownership

| Resource | Owner | Acquired | Released |
|---|---|---|---|
| `BarcodeScanner` (MLKit) | `BarcodeDetector` | `scan()` entry | `use { }` at `scan()` exit |
| CameraX use-case bindings | `Scanner` | `scan()` | `finally` in `scan()`, plus the bound `LifecycleOwner` |
| Torch collection | `scan()`'s `coroutineScope` | `launch` | cancelled in `finally` |
| `ImageProxy` (per frame) | the analyzer lambda | `analyze()` | `frame.use { }` (I5) |

**Every row is bounded by `scan()`.** `Scanner` holds no resource between calls, which is why it has
no `close()` and why the UI needs no `onDestroy` teardown. One `Scanner` instance can be reused for
the lifetime of the screen — or the ViewModel — without leaking anything.

When ViewModels arrive, ownership of the `Scanner` *instance* moves to the ViewModel. Because there is
nothing to release, `onCleared()` needs no body — **a change in the UI layer, and not even that.**

---

## 7. Error handling

| Failure | Handling |
|---|---|
| `awaitInstance` throws | `status = Unavailable(cause)`; `scan()` rethrows; UI may show an error affordance |
| `bindToLifecycle` throws | `status = Unavailable(cause)`; `scan()` rethrows (replaces today's log-and-swallow, D9) |
| MLKit `read` throws | `runCatching` per frame; frame closed by `use`; scanning continues |
| Frame has no `mediaImage` | frame closed by `use`, skipped (D4) |
| Frame contains only unsupported types | `read` returns `null`; nothing emitted (I2) |
| No flash unit | `FlashStatus.Unavailable`; `toggleFlash` is a no-op (D3) |
| Caller cancelled mid-scan | `finally` unbinds; in-flight MLKit call completes and is discarded (§3.5.2) |

`scan()` throwing is deliberate rather than returning a nullable or suspending forever: the call site
handles it in one `runCatching`, and the cause is simultaneously on `status` for any UI that wants to
render it. No new user-facing error UI belongs to the core.

---

## 8. Migration seam

What the Compose step must change in each core class — the design is only successful if this column
is empty.

| Class | Compose-time change |
|---|---|
| `ScandyBarcode` | none (until navigation drops `Serializable`) |
| `ScannerStatus` | none |
| `FlashStatus` | none |
| `BarcodeDetector` | none |
| `Scanner` | none |

This holds because of three deliberate choices:

1. `scan()` accepts `Preview.SurfaceProvider`, a CameraX type. `PreviewView` provides one today;
   `CameraXViewfinder` or an `AndroidView`-wrapped `PreviewView` provides one later.
2. `LifecycleOwner` is satisfied by both a `ComponentActivity` and `LocalLifecycleOwner.current`.
3. State is `StateFlow`, consumed by `repeatOnLifecycle` today and `collectAsStateWithLifecycle()`
   later; `scan()` itself is a suspend function, callable from `LaunchedEffect` unchanged.

What *does* change at Compose time is confined to the UI layer: who owns the `Scanner` (Activity →
ViewModel), how the resource mapping is expressed, and the wholesale replacement of the permission
layer.

---

## 9. Remaining Java-API seams

Tracked explicitly because reducing them was a design goal (§11.5).

| Seam | Status |
|---|---|
| `ProcessCameraProvider.getInstance()` → `ListenableFuture` | eliminated — `awaitInstance()` |
| `scanner.process(image)` → `Task` | eliminated — `await()` |
| `cameraInfo.torchState` → `LiveData<Int>` | eliminated — `asFlow()` |
| `ListenableFuture.addListener` | eliminated — `ConcurrentExtensions.kt` deleted |
| `cameraControl.enableTorch()` → `ListenableFuture<Void>` | result ignored; no bridge needed |
| **`ImageAnalysis.setAnalyzer(Executor, Analyzer)`** | **irreducible** — CameraX ships no coroutine overload. Fed `Dispatchers.IO.asExecutor()`; one line. |

---

## 10. Defects addressed

Defects in the current implementation that this component design resolves. Permission-layer defects
are out of scope and not listed.

| ID | Location | Description | Fixed by |
|---|---|---|---|
| D1 | `ScannerActivity.kt:82-85`, `:164` | Two camera stacks bound to one `PreviewView`. `LifecycleCameraController` is bound in `onCreate`, then `ProcessCameraProvider.unbindAll()` tears it down. | §3.5 / I3 — `initCameraController()` deleted entirely |
| D2 | `ScannerActivity.kt:94`, `:97` | Flash toggle and `torchState` observer target `cameraController`, detached by D1. | §3.5.3 |
| D3 | `ScannerActivity.kt:118-124` | `throw RuntimeException("Invalid TorchState")` on the `else` branch; `torchState.value` is `null` when unbound. | §3.3 `Unavailable` + §3.5.3 |
| D4 | `BarcodeAnalyzer.kt:19`, `:32-35` | `imageProxy.close()` only inside MLKit listeners. A null-image frame is never released → CameraX stops delivering frames permanently. | §3.5.2 `frame.use { }` / I5 |
| D5 | `BarcodeAnalyzer.kt:22` | `BarcodeScanning.getClient()` called per frame; no instance ever closed. | §3.4 / I1 — one client per `scan()` |
| D6 | `ScannerActivity.kt:200` | `setAnalyzer` uses `ContextCompat.getMainExecutor` → per-frame work on the UI thread. | §3.5.4 `Dispatchers.IO.asExecutor()` |
| D7 | `BarcodeAnalyzer.kt:23-31` | Callback fires per barcode per frame; in-flight frames after unbind can re-trigger `startActivity`. | §3.5.2 `first()` / I4 |
| D8 | `ScannerActivity.kt:136-142`, `:159-175` | `startCamera()` runs from both the permission callback and `onResume`, each time constructing **new** use cases and stacking them on the process-wide provider. | §4 — one `scan()` per `STARTED`; §3.5.1 unbinds in `finally` |
| D9 | `ScannerActivity.kt:171-173` | `bindToLifecycle` failure is logged and swallowed; UI shows a dead viewfinder with no indication. | §3.2 `ScannerStatus.Unavailable` |

---

## 11. Decision log

Questions raised against the previous revision, and the answers that closed them.

### 11.1 Why coroutines, and which artifacts

Replacing the callbacks is worth it, but `kotlinx-coroutines-play-services` covers only MLKit's
`Task`. The provider future needs nothing (`awaitInstance()`), and `LiveData` needs
`lifecycle-livedata-ktx`. The previous revision's claim that converting the provider future required
`play-services` was wrong on two counts: it would have needed `coroutines-guava`, and neither is
necessary. Consequence: `ConcurrentExtensions.kt` does **not** survive — it is deleted. See §2.2, §9.

### 11.2 Why frames are not queued into a `StateFlow`/`Channel`

`ImageProxy` is not a value; it is a lease on a buffer from a fixed-size hardware pool. Queueing
proxies holds those buffers, and when the pool drains **CameraX stops producing frames entirely** —
the same permanent stall as D4, reached by a different route. `MutableStateFlow<ImageProxy>` is worse:
conflation silently overwrites a proxy that is then never closed.

Backpressure is already resolved at the source by `STRATEGY_KEEP_ONLY_LATEST`. Any queue in front of
it is a second, redundant, leak-prone buffer. A `Channel(CONFLATED, onUndeliveredElement = ImageProxy::close)`
would be *safe*, but it buys decoupling this app does not need in exchange for a new leak surface.
**The flow carries `ScandyBarcode`, never `ImageProxy`.**

### 11.3 Why serialisation is not configured by the caller

An earlier sketch had the client supply a `limitedParallelism(1)` dispatcher. Rejected: a guarantee
the core depends on must not be a caller's responsibility. The resolution is better than making it
private — CameraX **already** invokes `analyze()` serially and will not deliver the next frame until
the current proxy is closed. Letting `analyze()` block makes serialisation a framework property, and
`@Volatile`, `arm()`, the mutex, the dispatcher knob and the owned `ExecutorService` all disappear
together. See §3.5.2, R2.

Correspondingly dropped: `distinctUntilChanged`/`debounce` de-duplication. Scandy ends the scan on the
first detection, so duplicate readings across consecutive frames cannot occur.

### 11.4 Why `unbindAll()` is not needed

`ProcessCameraProvider` is a process-wide singleton, so its bindings outlive any single Activity
instance — but the current failure is **not** "use case already bound". `getPreviewConfig()` and
`getImagerAnalyzer()` construct *fresh* instances on every call, so nothing is rebound. There are two
distinct CameraX errors, and the current code hits the second:

1. **Same instance bound twice** → `IllegalArgumentException`. Not applicable here.
2. **Combination exceeds device capability** → `IllegalArgumentException` ("no supported surface
   combination"). Without `unbindAll()`, the `onResume` path *adds* a second Preview + ImageAnalysis
   on top of the first, plus the `LifecycleCameraController`'s own pair from `onCreate`. Three of each
   is past what any device negotiates.

So `unbindAll()` was masking accidental **duplicate use cases**, not duplicate bindings (D8). Once
`initCameraController()` is deleted and each `scan()` unbinds its own use cases in `finally`, both
failure modes are gone.

It is also actively hostile as a habit: being process-wide, it would tear down any future
`ImageCapture` or second session. If a defensive unbind is ever wanted, it must be
`provider.unbind(preview, frames)` — scoped to what this call owns, which is what §3.5.1 does.

### 11.5 Naming

The previous revision's vocabulary — `ScannerSession.bind()` → `armScanning()` → `SessionState.Ready`
— described CameraX's protocol rather than what a person is doing. Locked renames:

| Previous | Locked |
|---|---|
| `ScannerSession` | `Scanner` |
| `SessionState` (`Idle`/`Binding`/`Ready`/`Failed`) | `ScannerStatus` (`Off`/`Starting`/`Scanning`/`Unavailable`) |
| `TorchState` | `FlashStatus` |
| `bind()` + `armScanning()` | `scan()` |
| `barcodes: SharedFlow<ScandyBarcode>` | `scan()`'s return value |
| `BarcodeImageAnalyzer` (class) | private `ImageAnalysis.barcodes(): Flow<ScandyBarcode>` |
| `BarcodeDetector.detect(image, onResult, onDone)` | `BarcodeDetector.read(image): ScandyBarcode?` |

The last two rows carry the most weight. `MutableSharedFlow`, `extraBufferCapacity`, and the whole
event-versus-state argument existed only to serve a flow that a suspend return value replaces
outright; and with the analyzer gone as a type, three of its invariants collapse into `frame.use { }`.

Net effect: the core drops from six types to four, and `arm()`, `handled`, `unbindAll()`,
`ConcurrentExtensions.kt`, `ExecutorService`, `Observer<Int>` and the barcode `SharedFlow` all leave.

---

## 12. Delta against work in progress

`camera/BarcodeDetector.kt` currently on disk predates this lock and differs in three ways:

1. Takes a `CoroutineScope` and `launch`es internally → should be a plain `suspend fun read(...)`
   with no scope (§3.4).
2. Returns via an `onResult: (ScandyBarcode) -> Unit` callback → should return `ScandyBarcode?`.
3. Calls `.firstOrNull()` on the raw MLKit list *before* mapping → can yield `ScandyBarcode.Unknown`
   and hide a readable code behind an unsupported one. Map first, then filter `Unknown` (§3.4, I2).

`camera/BarcodeAnalyzer.kt` is unchanged from the original and is scheduled for deletion (§3.6).
`ui/scanner/FlashState.kt` is likewise still present and scheduled for deletion.