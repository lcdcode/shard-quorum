package com.lcdcode.shardquorum

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lcdcode.shardquorum.ui.HomeScreen
import com.lcdcode.shardquorum.ui.create.CreateSecretScreen
import com.lcdcode.shardquorum.ui.recover.RecoverScreen
import com.lcdcode.shardquorum.ui.theme.ShardQuorumTheme

private enum class Screen { HOME, CREATE, RECOVER }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Key material will be rendered on screen; block screenshots, the
        // recents thumbnail, and non-secure displays for the whole activity.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        setContent {
            ShardQuorumTheme {
                var screen by remember { mutableStateOf(Screen.HOME) }
                when (screen) {
                    Screen.HOME -> HomeScreen(
                        onCreate = { screen = Screen.CREATE },
                        onRecover = { screen = Screen.RECOVER },
                    )
                    Screen.CREATE -> CreateSecretScreen(onExit = { screen = Screen.HOME })
                    Screen.RECOVER -> RecoverScreen(onExit = { screen = Screen.HOME })
                }
            }
        }
    }
}
