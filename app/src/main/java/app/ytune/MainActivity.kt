package app.ytune

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("YTune") }
    }

    companion object {
        const val ACTION_OPEN_PLAYER = "app.ytune.OPEN_PLAYER"
        const val ACTION_OPEN_DOWNLOADS = "app.ytune.OPEN_DOWNLOADS"
    }
}
