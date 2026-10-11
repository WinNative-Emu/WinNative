package com.winlator.cmod.runtime.input.controls

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SteamControllerPointerNativeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun trackpadScrollMovesGameList() {
        lateinit var list: LazyListState
        compose.setContent {
            list = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                items(100) { Text("Game $it", Modifier.height(80.dp)) }
            }
        }
        compose.waitUntil(5000) { compose.activity.window.decorView.hasWindowFocus() }
        compose.runOnIdle {
            val pointer = SteamControllerPointer(compose.activity)
            try { pointer.scroll(0f, -8f) } finally { pointer.clear() }
        }
        compose.waitUntil(5000) { list.firstVisibleItemIndex > 0 }
    }

    @Test fun trackpadPointerCanClickComposeButton() {
        var clicks = 0
        compose.setContent {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button(onClick = { clicks++ }) { Text("Target") }
            }
        }
        compose.waitUntil(5000) { compose.activity.window.decorView.hasWindowFocus() }
        compose.runOnIdle {
            val pointer = SteamControllerPointer(compose.activity)
            try {
                pointer.move(0, 0)
                pointer.button(false, true)
                pointer.button(false, false)
            } finally { pointer.clear() }
        }
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
