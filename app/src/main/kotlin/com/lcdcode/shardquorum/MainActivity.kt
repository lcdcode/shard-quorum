package com.lcdcode.shardquorum

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.lcdcode.shardquorum.ui.HomeScreen
import com.lcdcode.shardquorum.ui.theme.ShardQuorumTheme

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
                HomeScreen()
            }
        }
    }
}
