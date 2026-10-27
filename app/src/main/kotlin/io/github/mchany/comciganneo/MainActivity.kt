package io.github.mchany.comciganneo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.mchany.comciganneo.data.AppRepository
import io.github.mchany.comciganneo.ui.NeoApp

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<NeoViewModel> {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NeoViewModel(AppRepository(applicationContext, BuildConfig.NEIS_API_KEY)) as T
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NeoApp(viewModel) }
    }
}
