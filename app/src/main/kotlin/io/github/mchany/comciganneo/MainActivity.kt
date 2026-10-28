package io.github.mchany.comciganneo

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.mchany.comciganneo.ui.NeoApp

class MainActivity : ComponentActivity() {
    private var notificationTarget by mutableStateOf<String?>(null)
    private var notificationEvent by mutableIntStateOf(0)
    private val viewModel by viewModels<NeoViewModel> {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NeoViewModel((application as NeoApplication).repository) as T
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val notificationEventId = intent.getStringExtra("notification_event")
        if (savedInstanceState == null || (notificationEventId != null && notificationEventId != savedInstanceState.getString("last_notification_event"))) {
            readNotificationIntent(intent)
        }
        setContent { NeoApp(viewModel, notificationTarget, notificationEvent) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readNotificationIntent(intent)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("last_notification_event", intent.getStringExtra("notification_event"))
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        (application as NeoApplication).refreshNotificationSchedules()
    }
    private fun readNotificationIntent(intent: Intent?) {
        notificationTarget = intent?.getStringExtra("notification_kind")?.takeIf { it == "timetable" || it == "meals" }
        notificationEvent++
    }
}
