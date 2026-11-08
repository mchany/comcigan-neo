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
            /**
             * 역할: 요청된 ViewModel 유형에 맞는 인스턴스를 생성한다.
             * 입력: modelClass: Class<T>.
             * 출력: T 값.
             */
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NeoViewModel((application as NeoApplication).repository) as T
        }
    }
    /**
     * 역할: 위젯 목록 팩토리 초기화 시 별도 작업을 하지 않는다.
     * 입력: savedInstanceState: Bundle?.
     * 출력: 없음(Unit).
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val notificationEventId = intent.getStringExtra("notification_event")
        if (savedInstanceState == null || (notificationEventId != null && notificationEventId != savedInstanceState.getString("last_notification_event"))) {
            readNotificationIntent(intent)
        }
        setContent { NeoApp(viewModel, notificationTarget, notificationEvent) }
    }
    /**
     * 역할: 새 알림 인텐트를 현재 화면에 전달하고 상태를 갱신한다.
     * 입력: intent: Intent.
     * 출력: 없음(Unit).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readNotificationIntent(intent)
    }
    /**
     * 역할: 화면에서 처리한 알림 이동 상태를 저장한다.
     * 입력: outState: Bundle.
     * 출력: 없음(Unit).
     */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("last_notification_event", intent.getStringExtra("notification_event"))
        super.onSaveInstanceState(outState)
    }
    /**
     * 역할: 화면이 다시 활성화될 때 알림 이동 상태를 확인한다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    override fun onResume() {
        super.onResume()
        (application as NeoApplication).refreshNotificationSchedules()
    }
    /**
     * 역할: 인텐트에서 알림 종류와 이벤트 식별자를 읽는다.
     * 입력: intent: Intent?.
     * 출력: 없음(Unit).
     */
    private fun readNotificationIntent(intent: Intent?) {
        notificationTarget = intent?.getStringExtra("notification_kind")?.takeIf { it == "timetable" || it == "meals" }
        notificationEvent++
    }
}
