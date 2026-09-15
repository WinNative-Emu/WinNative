import com.winlator.cmod.runtime.input.controls.FakeInputWriter;
import com.winlator.cmod.runtime.input.controls.GamepadState;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

// Runs the production Java writer and JNI fence on ART against the guest hook.
public class FakeInputWriterTest {
  static { System.loadLibrary("winlator"); }
  private static native long openReader(String library, String directory, String rings);
  private static native int[] readState(long reader);
  private static native void closeReader(long reader);

  private static void check(boolean value) {
    if (!value) throw new AssertionError();
  }

  public static void main(String[] args) throws Exception {
    File directory = new File(args[1], "input");
    FakeInputWriter writer = new FakeInputWriter(directory.getPath(), 0);
    check(writer.open());
    GamepadState state = new GamepadState();
    writer.writeGamepadState(state);
    long reader = openReader(args[0], directory.getPath(), FakeInputWriter.getRingEnv(directory));
    check(readState(reader)[0] == 0);

    // A press and release that both happen before Wine polls must not vanish.
    state.setPressed(GamepadState.BUTTON_A, true);
    writer.writeGamepadState(state);
    state.setPressed(GamepadState.BUTTON_A, false);
    writer.writeGamepadState(state);
    int[] result = readState(reader);
    check(result[2] == 0 && result[3] == 1);

    state.thumbLX = state.thumbLY = 0.75f;
    state.triggerR = 1f;
    writer.writeGamepadState(state);
    check(readState(reader)[0] > 0);
    writer.close();
    writer = new FakeInputWriter(directory.getPath(), 0);
    check(writer.open());
    state.clear();
    writer.reset();
    result = readState(reader);
    check(result[0] == 0 && result[1] == 0 && result[4] == 0);

    AtomicBoolean done = new AtomicBoolean();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread guest = new Thread(() -> {
      try {
        while (!done.get()) readState(reader);
      } catch (Throwable error) {
        failure.set(error);
      }
    });
    guest.start();
    for (int i = 0; i < 100000; ++i) {
      state.thumbLX = state.thumbLY = (i % 1000) / 1000f;
      writer.writeGamepadState(state);
    }
    state.clear();
    writer.writeGamepadState(state);
    done.set(true);
    guest.join();
    if (failure.get() != null) throw new AssertionError(failure.get());
    result = readState(reader);
    check(result[0] == 0 && result[1] == 0 && result[2] == 0 && result[4] == 0);
    closeReader(reader);
    writer.destroy();
    FakeInputWriter.releaseAllRingSlots();
    System.out.println("PASS Java writer: quick tap, reattach, 100000 concurrent frames, neutral release");
  }
}
