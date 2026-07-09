package com.lcdcode.shardquorum

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lcdcode.shardquorum.ui.AboutScreen
import com.lcdcode.shardquorum.ui.HomeScreen
import com.lcdcode.shardquorum.ui.OnboardingScreen
import com.lcdcode.shardquorum.ui.create.CreateSecretScreen
import com.lcdcode.shardquorum.ui.recover.RecoverScreen
import com.lcdcode.shardquorum.ui.theme.ShardQuorumTheme

private enum class Screen { HOME, CREATE, RECOVER, ABOUT, ONBOARDING }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Key material will be rendered on screen; block screenshots, the
        // recents thumbnail, and non-secure displays for the whole activity.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val onboardingDone = prefs.getBoolean(KEY_ONBOARDING_DONE, false)

        setContent {
            ShardQuorumTheme {
                var screen by remember { mutableStateOf(
                    if (onboardingDone) Screen.HOME else Screen.ONBOARDING
                ) }
                when (screen) {
                    Screen.ONBOARDING -> OnboardingScreen(
                        onDone = {
                            prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
                            screen = Screen.HOME
                        },
                    )
                    Screen.HOME -> HomeScreen(
                        onCreate = { screen = Screen.CREATE },
                        onRecover = { screen = Screen.RECOVER },
                        onAbout = { screen = Screen.ABOUT },
                    )
                    Screen.CREATE -> CreateSecretScreen(onExit = { screen = Screen.HOME })
                    Screen.RECOVER -> RecoverScreen(onExit = { screen = Screen.HOME })
                    Screen.ABOUT -> AboutScreen(onExit = { screen = Screen.HOME })
                }
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "shardquorum_prefs"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
    }
}
