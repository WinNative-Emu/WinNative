package com.winlator.cmod.runtime.input.controls

import android.app.Activity
import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class SteamControllerUiSessionTest {
    @Test fun signInSessionRetainsBackendOnPauseAndReleasesItOnStop() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val backend = mock(SteamControllerBackend::class.java)
        `when`(backend.start()).thenReturn(true)
        var creations = 0
        val session = SteamControllerUiSession(activity) { creations++; backend }
        SteamControllerPrefs.setEnabled(activity, true)
        try {
            session.resume()
            session.pause()
            verify(backend, never()).stop()
            session.resume()
            assertEquals(1, creations)
            session.stop()
            session.stop()
            verify(backend, times(1)).stop()
            session.resume()
            assertEquals(2, creations)
        } finally { session.stop(); activity.finish() }
    }

    @Test fun disabledControllerSupportDoesNotStartSignInBackend() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        SteamControllerPrefs.setEnabled(activity, false)
        val session = SteamControllerUiSession(activity) { error("Backend must stay disabled") }
        try { session.resume(); session.pause() } finally { session.stop(); activity.finish() }
    }
}
