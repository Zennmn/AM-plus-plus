package dev.amenhancer.module.ui

import dev.amenhancer.module.model.ModuleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedSettingsHostTest {
    @Test
    fun `settings activity policy accepts settings naming variants but rejects ordinary screens`() {
        assertTrue(
            EmbeddedSettingsTextPolicy.isSettingsClassName(
                "com.apple.android.music.settings.SettingsActivity",
            ),
        )
        assertTrue(
            EmbeddedSettingsTextPolicy.isSettingsClassName(
                "com.apple.android.music.account.AccountSettingsController",
            ),
        )
        assertFalse(
            EmbeddedSettingsTextPolicy.isSettingsClassName(
                "com.apple.android.music.common.activity.PlayerActivity",
            ),
        )
        assertFalse(
            EmbeddedSettingsTextPolicy.isSettingsClassName(
                "com.apple.android.music.MainActivity",
            ),
        )
    }

    @Test
    fun `settings title policy recognizes localized and English settings labels`() {
        assertTrue(EmbeddedSettingsTextPolicy.isSettingsTitle("设置"))
        assertTrue(EmbeddedSettingsTextPolicy.isSettingsTitle("Settings"))
        assertTrue(EmbeddedSettingsTextPolicy.isSettingsTitle("General settings"))
        assertFalse(EmbeddedSettingsTextPolicy.isSettingsTitle("正在播放"))
    }

    @Test
    fun `only PlayerActivity receives one injection decision per resume`() {
        val state = EmbeddedSettingsLifecycleState()

        assertEquals(
            EmbeddedSettingsLifecycleAction.Ignore,
            state.onActivityResumed("other", "com.apple.android.music.MainActivity"),
        )
        assertEquals(
            EmbeddedSettingsLifecycleAction.Inject,
            state.onActivityResumed(
                "player-1",
                EmbeddedSettingsHost.PLAYER_ACTIVITY_NAME,
            ),
        )
        assertEquals(
            EmbeddedSettingsLifecycleAction.AlreadyInjected,
            state.onActivityResumed(
                "player-1",
                EmbeddedSettingsHost.PLAYER_ACTIVITY_NAME,
            ),
        )
    }

    @Test
    fun `destroy releases the activity ownership for cleanup and future injection`() {
        val state = EmbeddedSettingsLifecycleState()

        state.onActivityResumed("player-1", EmbeddedSettingsHost.PLAYER_ACTIVITY_NAME)

        assertTrue(state.onActivityDestroyed("player-1"))
        assertFalse(state.onActivityDestroyed("player-1"))
        assertEquals(
            EmbeddedSettingsLifecycleAction.Inject,
            state.onActivityResumed("player-1", EmbeddedSettingsHost.PLAYER_ACTIVITY_NAME),
        )
    }

    @Test
    fun `settings activity receives one injection decision per resume`() {
        val state = EmbeddedSettingsLifecycleState()

        assertEquals(
            EmbeddedSettingsLifecycleAction.Inject,
            state.onActivityResumed(
                "settings-1",
                "com.apple.android.music.settings.SettingsActivity",
                EmbeddedHostActivityRole.Settings,
            ),
        )
        assertEquals(
            EmbeddedSettingsLifecycleAction.AlreadyInjected,
            state.onActivityResumed(
                "settings-1",
                "com.apple.android.music.settings.SettingsActivity",
                EmbeddedHostActivityRole.Settings,
            ),
        )
    }

    @Test
    fun `foreign activity destruction does not clear the active player`() {
        val state = EmbeddedSettingsLifecycleState()

        state.onActivityResumed("player-1", EmbeddedSettingsHost.PLAYER_ACTIVITY_NAME)

        assertFalse(state.onActivityDestroyed("other"))
        assertEquals(
            EmbeddedSettingsLifecycleAction.AlreadyInjected,
            state.onActivityResumed("player-1", EmbeddedSettingsHost.PLAYER_ACTIVITY_NAME),
        )
    }
}

class EmbeddedSafResultRouterTest {
    @Test
    fun `successful result is routed to the matching pending operation`() {
        val router = EmbeddedSafResultRouter()
        val requestCode = router.begin(EmbeddedSafOperation.Font)

        assertEquals(
            EmbeddedSafRoute.Selected(
                operation = EmbeddedSafOperation.Font,
                uri = "content://fonts/am.ttf",
            ),
            router.route(requestCode, EmbeddedSafResult.RESULT_OK, "content://fonts/am.ttf"),
        )
        assertNull(router.pending())
    }

    @Test
    fun `foreign request code is ignored without consuming the pending operation`() {
        val router = EmbeddedSafResultRouter()
        val ownRequestCode = router.begin(EmbeddedSafOperation.Font)

        assertEquals(
            EmbeddedSafRoute.Ignored,
            router.route(4401, EmbeddedSafResult.RESULT_OK, "content://host/result"),
        )
        assertEquals(ownRequestCode, router.pending()?.requestCode)
    }

    @Test
    fun `canceled own result is observed and clears the pending operation`() {
        val router = EmbeddedSafResultRouter()
        val requestCode = router.begin(EmbeddedSafOperation.Ttml)

        assertEquals(
            EmbeddedSafRoute.Canceled(EmbeddedSafOperation.Ttml),
            router.route(requestCode, EmbeddedSafResult.RESULT_CANCELED, null),
        )
        assertNull(router.pending())
    }

    @Test
    fun `embedded request codes do not overlap host legacy request codes`() {
        val hostCodes = setOf(4401, 4402, 4403, 4404)

        assertTrue(EmbeddedSafResultRouter.OWN_REQUEST_CODES.none(hostCodes::contains))
    }
}

class EmbeddedSettingsControllerContractTest {
    @Test
    fun `controller facade exposes and saves ordinary module settings`() {
        var stored = ModuleSettings()
        val controller = object : EmbeddedSettingsController {
            override fun currentSettings(): ModuleSettings = stored

            override fun saveOrdinarySettings(settings: ModuleSettings): Boolean {
                stored = settings
                return true
            }
        }
        val updated = stored.copy(
            dualPaneEnabled = false,
            lyricBlurRadiusOffsetPx = 4,
            customLyricsEnabled = true,
        )

        assertEquals(ModuleSettings(), controller.currentSettings())
        assertTrue(controller.saveOrdinarySettings(updated))
        assertEquals(updated, controller.currentSettings())
    }
}
