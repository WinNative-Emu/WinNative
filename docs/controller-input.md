# Controller input transport

## Path from a touch to the game

`InputControlsView` tracks the Android pointer that owns each `ControlElement`.
A stick move updates the profile's `GamepadState`; releasing that pointer sends
zero axes. Up/cancel, missing-pointer reconciliation, focus changes and profile
changes already have release handling. `WinHandler` assigns the virtual pad a
slot and passes its state to `FakeInputWriter`. Physical controllers reach the
same writer after their Android axes/buttons have been mapped.

The writer emits changed Linux input events and a `SYN_REPORT` into a shared,
4096-event file ring. It also publishes a full state snapshot. Discovery files
under the fake `/dev/input` directory identify the available slots; they are
not the input queue. The guest's preloaded `libfakeinput.so` maps the ring and
implements `open`, `read`, readiness polling and evdev ioctls. Wine converts
those events into HID reports and ultimately the game's controller state.

Input is stateful: a held axis remains held until a newer event changes it.
Continuing to run therefore does not require repeated button-down messages.
A missing release can affect one axis while other buttons still work; a
stalled reader can leave the entire controller frozen. The Linux protocol
also exposes current state through ioctls. See the
[kernel input protocol](https://docs.kernel.org/input/event-codes.html).

Wine's upstream evdev reader consumes one `input_event` per read and submits
reports on `SYN_REPORT`. Partial delivery is therefore a normal workload, not
just an artificial small-buffer test. See
[Wine's evdev reader](https://github.com/wine-mirror/wine/blob/master/dlls/winebus.sys/bus_udev.c).
This repository supplies the interposer; it does not contain the source of
every bundled Proton's winebus build.

## Gaps left by the previous fixes

The review starts at `c4a42ff2` and includes controller fixes `c769acb1`,
`024353de`, `d48fef36`, and the Direct Audio fix `f18dc4f9`.

| Fault | Why it matters | Correction |
| --- | --- | --- |
| `read()` kept a raw map-entry pointer after unlocking | Another reader or poll modified its cursor/keyframe, and close could free the entry and unmap its ring. | Keep shared ownership across waits; serialize cursor/keyframe access with close. Closed readers return `EBADF`. |
| Poll acknowledged resync while a previous keyframe was incomplete | The new request was consumed without capturing its state. An old held axis could remain the last delivered value. | Poll only reports readiness. Read acknowledges a resync only when it captures that snapshot, after finishing the previous frame. |
| Snapshot, ring bytes and write cursor were published separately | A snapshot could describe a different frame from the cursor. The producer could also overwrite ring bytes while the reader copied them. | One sequence protects the whole publication. Validate copies against that sequence before advancing the reader. |
| Overflow replayed retained history after the latest snapshot | Recovery briefly reasserted old inputs and could mix frames. | A recovery snapshot supersedes events through its own cursor. Only later deltas follow it. |
| A busy snapshot was replaced with invented neutral state | A legitimate held input could disappear until it changed again. | Retry a busy publication without consuming data or acknowledging recovery. |
| Every transition to idle requested snapshot recovery | This made recovery routine. Discarding covered deltas during such recovery would also discard short taps. | Keep ordinary press/release traffic as ordered deltas. Explicit full resends publish their recovery counter with the new state. |
| A new writer's `reset()` trusted its initially neutral local baseline | Reset immediately after reattach could omit releases for controls held by the old writer. | Honor the pending full resend during reset too. |
| Guest rumble used blocking connect/send under the controller lock | A full vibration listener backlog could park winebus and block the other input hooks. | Use nonblocking, close-on-exec sockets and suppress `SIGPIPE`. Rumble is best effort under backpressure. |
| Evdev bitmap ioctls copied fixed sizes and reported zero current state | A small caller buffer was overwritten; querying state could falsely report released inputs. | Bound bitmap copies to the requested length and return the published buttons/axes. |
| Masked `ppoll` bypassed fake readiness; poll errors were swallowed | A regular ring file looked readable while idle, or interruption became an endless retry. | Keep fake descriptors out of the real wait, retain the supplied mask during waits, and propagate errors. |
| Ring path parsing never marked a successful parse complete | Concurrent opens could rewrite strings after lookup returned a pointer. | Load once under the mutex and return an owned path string. |

Polling retains the original controller object for the duration of the wait,
so close/fd-number reuse cannot silently switch it to another controller.
Ordinary non-controller reads, writes and ioctls execute without holding the
controller mutex. Blocking input reads release that mutex while waiting.
The input hook no longer installs its own process-wide SIGINT handler.

The 64-byte ring layout and version remain unchanged. The Java writer and
guest library must be deployed together because publication ordering is now
stronger; the normal APK/guest-launch copy path does this. A genuinely removed
slot still changes generation and reports disconnection. This fix adds no
timer, input watchdog, UI work, translated string or audio-driver change.

## Reproducible validation

Run against an explicitly selected arm64 Android device:

```bash
ANDROID_HOME=/path/to/android-sdk tools/test-fakeinput.sh ADB_SERIAL
```

To check the exact native libraries extracted from an assembled APK:

```bash
tools/test-fakeinput.sh ADB_SERIAL /path/to/libfakeinput.so /path/to/libwaudio.so
```

The script uses NDK `27.3.13750724`, SDK/build-tools 35, JDK 17+, and `rg`.
It creates isolated temporary fixtures on the device and removes its working
directory afterward. It does not replace the installed app or touch game data.

The native tests cover partial-frame resync, a writer paused during publication,
overflow, ordered deltas, current-state queries, short ioctl buffers, concurrent
readers, close during read/poll, masked/mixed polling, signal interruption,
generation changes, rumble backpressure, and blocking unrelated writes.

The ART test compiles the actual `FakeInputWriter`, `GamepadState` and JNI fence.
It checks a quick tap before the guest polls, reset after reattach, and 100,000
concurrent frames. Each published test frame has matching X/Y values, allowing
the guest consumer to detect mixed frames; the final state must be neutral.

The audio test runs with `libfakeinput.so` in `LD_PRELOAD`, loads the production
`libwaudio.so` namespace bridge, opens/starts/stops/closes five AAudio streams,
and requires audio callbacks. It also issues an unrelated ioctl while the
AAudio Binder pool is active, checking the earlier Direct Audio deadlock path.
The Gradle unit suite separately covers the bundled Direct Audio ABI selection.

On the original code, the device probes reproduced lost resync, invented neutral
state, stale replay, wrong ioctl state, buffer overwrites and invalid concurrent
events. Blocking reader teardown and a full rumble backlog exceeded the test
deadline. Ordinary deltas and unrelated blocking-write passthrough passed.

These are demonstrated transport faults, not proof that every historical game
freeze had the same trigger. Touch input is the user-confirmed affected setup;
physical-controller impact follows from the shared transport but requires a
physical-controller gameplay test. A prolonged run in the originally affected
game remains the final check for any independent game/runtime problem.
