package io.github.mchany.comciganneo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.mchany.comciganneo.data.*
import io.github.mchany.comciganneo.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class NeoState(
    val initializing: Boolean = true,
    val preferences: AppPreferences = AppPreferences(),
    val week: Int = 0,
    val snapshot: TimetableSnapshot? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val setup: Boolean = false,
    val searchResults: List<School> = emptyList(),
    val searching: Boolean = false,
    val searched: Boolean = false,
    val candidate: School? = null,
    val classCounts: List<Int> = emptyList(),
    val setupError: String? = null,
)

class NeoViewModel(private val repository: AppRepository) : ViewModel() {
    private val mutable = MutableStateFlow(NeoState())
    val state: StateFlow<NeoState> = mutable.asStateFlow()
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var candidateJob: Job? = null
    init {
        viewModelScope.launch {
            repository.preferences.collect { preferences ->
                val previous = mutable.value.preferences.selection
                val first = mutable.value.initializing
                mutable.update { it.copy(initializing = false, preferences = preferences) }
                if (preferences.selection != null && (first || previous != preferences.selection)) load()
            }
        }
    }
    fun openSetup() { mutable.update { it.copy(setup = true, candidate = null, setupError = null, classCounts = emptyList()) } }
    fun closeSetup() {
        searchJob?.cancel(); candidateJob?.cancel()
        mutable.update { it.copy(setup = false, searching = false, candidate = null, setupError = null) }
    }
    fun search(query: String) {
        searchJob?.cancel(); candidateJob?.cancel()
        if (query.trim().length < 2) {
            mutable.update { it.copy(setupError = "학교 이름을 두 글자 이상 입력해 주세요.") }; return
        }
        searchJob = viewModelScope.launch {
            mutable.update { it.copy(searching = true, searched = false, candidate = null, setupError = null, searchResults = emptyList()) }
            try {
                val results = repository.search(query)
                mutable.update { it.copy(searching = false, searched = true, searchResults = results) }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { mutable.update { it.copy(searching = false, setupError = userMessage(e)) } }
        }
    }
    fun candidate(school: School) {
        candidateJob?.cancel()
        candidateJob = viewModelScope.launch {
            mutable.update { it.copy(searching = true, candidate = school, classCounts = emptyList(), setupError = null) }
            try {
                val preview = repository.preview(school)
                mutable.update { it.copy(searching = false, classCounts = preview.classCounts) }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { mutable.update { it.copy(searching = false, setupError = userMessage(e)) } }
        }
    }
    fun select(grade: Int, classNumber: Int) {
        val school = mutable.value.candidate ?: return
        setting {
            repository.select(ClassSelection(school, grade, classNumber))
            mutable.update { it.copy(setup = false) }
        }
    }
    fun week(week: Int) {
        if (week == mutable.value.week) return
        mutable.update { it.copy(week = week) }; load()
    }
    fun refresh() { if (!mutable.value.loading) load(force = true) }
    fun onForeground() {
        val snapshot = mutable.value.snapshot
        if (!mutable.value.loading && mutable.value.preferences.selection != null &&
            (snapshot == null || snapshot.weekStart != weekStart(offset = mutable.value.week).toString() ||
                System.currentTimeMillis() - snapshot.fetchedAt > 300_000)) load()
    }
    private fun load(force: Boolean = false) {
        loadJob?.cancel()
        val selection = mutable.value.preferences.selection ?: return
        val week = mutable.value.week
        loadJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true, error = null, snapshot = null) }
            try {
                val cache = repository.cached(selection, week)
                mutable.update { it.copy(snapshot = cache) }
                if (!force && cache != null && System.currentTimeMillis() - cache.fetchedAt < 300_000) {
                    mutable.update { it.copy(loading = false) }; return@launch
                }
                val snapshot = repository.refresh(selection, week)
                mutable.update { it.copy(snapshot = snapshot, loading = false) }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { mutable.update { it.copy(loading = false, error = userMessage(e)) } }
        }
    }
    private fun setting(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = "설정을 저장하지 못했어요. 다시 시도해 주세요.") } }
        }
    }
    fun theme(mode: ThemeMode) = setting { repository.theme(mode) }
    fun dynamic(enabled: Boolean) = setting { repository.dynamic(enabled) }
    fun teachers(enabled: Boolean) = setting { repository.teachers(enabled) }
    fun clearCache() = setting {
        loadJob?.cancelAndJoin()
        repository.clearCache()
        mutable.update { it.copy(snapshot = null, loading = false, error = "저장된 시간표를 삭제했어요. 새로고침하면 다시 가져옵니다.") }
    }
}
