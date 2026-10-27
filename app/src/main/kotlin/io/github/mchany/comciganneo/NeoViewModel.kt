package io.github.mchany.comciganneo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.mchany.comciganneo.data.*
import io.github.mchany.comciganneo.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate

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
    val meal: MealState = MealState(),
) {
    val mealSchool: NeisSchoolRef? get() = preferences.mealSchools[preferences.selection?.school?.code.toString()]
}

class NeoViewModel(private val repository: AppRepository) : ViewModel() {
    private val mutable = MutableStateFlow(NeoState())
    val state: StateFlow<NeoState> = mutable.asStateFlow()
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var candidateJob: Job? = null
    private var mealJob: Job? = null
    private var mealSearchJob: Job? = null
    private var lastToday = schoolToday()
    init {
        viewModelScope.launch {
            repository.preferences.collect { preferences ->
                val previous = mutable.value.preferences.selection
                val previousMealSchool = mutable.value.mealSchool
                val previousKey = mutable.value.preferences.mealKeyConfigured
                val first = mutable.value.initializing
                mutable.update { it.copy(initializing = false, preferences = preferences) }
                if (preferences.selection != null && (first || previous != preferences.selection)) load()
                if (first || previous?.school?.code != preferences.selection?.school?.code ||
                    previousMealSchool != mutable.value.mealSchool || previousKey != preferences.mealKeyConfigured) {
                    mealJob?.cancel(); mealSearchJob?.cancel()
                    mutable.update { it.copy(meal = MealState()) }
                    loadMeals()
                }
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
        val today = schoolToday()
        if (today != lastToday) {
            val wasToday = mutable.value.meal.date == lastToday.toString()
            lastToday = today
            if (wasToday) mutable.update { it.copy(meal = it.meal.copy(date = today.toString())) }
        }
        val meal = mutable.value.meal
        if (!meal.loading && mutable.value.mealSchool != null && mutable.value.preferences.mealKeyConfigured &&
            (meal.snapshot?.isFresh(LocalDate.parse(meal.date)) != true || meal.todaySnapshot?.isFresh(today) != true)) loadMeals()
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
        mealJob?.cancelAndJoin()
        repository.clearCache()
        mutable.update { it.copy(snapshot = null, loading = false, error = "저장된 시간표와 급식을 삭제했어요. 새로고침하면 다시 가져옵니다.",
            meal = it.meal.copy(snapshot = null, todaySnapshot = null, loading = false, error = "저장된 급식을 삭제했어요.")) }
    }

    fun mealDate(date: LocalDate) {
        if (date.toString() == mutable.value.meal.date) return
        mutable.update { it.copy(meal = it.meal.copy(date = date.toString())) }
        loadMeals()
    }
    fun refreshMeals() = loadMeals(force = true)
    private fun loadMeals(force: Boolean = false) {
        mealJob?.cancel()
        val school = mutable.value.mealSchool ?: return
        val date = LocalDate.parse(mutable.value.meal.date)
        val today = schoolToday()
        mealJob = viewModelScope.launch {
            mutable.update { it.copy(meal = it.meal.copy(loading = true, error = null, todayError = null,
                snapshot = it.meal.snapshot?.takeIf { s -> s.school == school && s.weekStart == weekStart(date).toString() },
                todaySnapshot = it.meal.todaySnapshot?.takeIf { s -> s.school == school && s.weekStart == weekStart(today).toString() })) }
            try {
                val cache = repository.cachedMeals(school, date)
                val todayCache = if (weekStart(date) == weekStart(today)) cache else repository.cachedMeals(school, today)
                mutable.update { it.copy(meal = it.meal.copy(snapshot = cache, todaySnapshot = todayCache)) }
                try {
                    val snapshot = repository.refreshMeals(school, date, force)
                    ensureActive()
                    mutable.update { it.copy(meal = it.meal.copy(snapshot = snapshot,
                        todaySnapshot = if (weekStart(date) == weekStart(today)) snapshot else it.meal.todaySnapshot)) }
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) {
                    mutable.update { it.copy(meal = it.meal.copy(error = mealUserMessage(e),
                        todayError = if (weekStart(date) == weekStart(today)) mealUserMessage(e) else null)) }
                }
                if (weekStart(date) != weekStart(today)) {
                    try {
                        val todaySnapshot = repository.refreshMeals(school, today, force)
                        ensureActive()
                        mutable.update { it.copy(meal = it.meal.copy(todaySnapshot = todaySnapshot)) }
                    } catch (e: CancellationException) { throw e }
                      catch (e: Exception) { mutable.update { it.copy(meal = it.meal.copy(todayError = mealUserMessage(e))) } }
                }
                mutable.update { it.copy(meal = it.meal.copy(loading = false)) }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { mutable.update { it.copy(meal = it.meal.copy(loading = false, error = mealUserMessage(e), todayError = mealUserMessage(e))) } }
        }
    }
    fun openMealConnection() {
        mutable.update { it.copy(meal = it.meal.copy(connecting = true, connectionError = null)) }
        if (mutable.value.preferences.mealKeyConfigured) searchMealSchools(mutable.value.preferences.selection?.school?.name.orEmpty())
    }
    fun closeMealConnection() {
        if (mutable.value.meal.saving) return
        mealSearchJob?.cancel()
        mutable.update { it.copy(meal = it.meal.copy(connecting = false, searching = false)) }
    }
    fun searchMealSchools(query: String) {
        mealSearchJob?.cancel()
        if (query.trim().length < 2) {
            mutable.update { it.copy(meal = it.meal.copy(connectionError = "학교 이름을 두 글자 이상 입력해 주세요.")) }
            return
        }
        mealSearchJob = viewModelScope.launch {
            mutable.update { it.copy(meal = it.meal.copy(searching = true, searched = false, schools = emptyList(), connectionError = null)) }
            try {
                val schools = repository.searchMealSchools(query)
                ensureActive()
                val region = mutable.value.preferences.selection?.school?.region.orEmpty()
                mutable.update { it.copy(meal = it.meal.copy(searching = false, searched = true,
                    schools = schools.sortedByDescending { s -> s.region.contains(region) && region.isNotBlank() })) }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { mutable.update { it.copy(meal = it.meal.copy(searching = false, connectionError = mealUserMessage(e))) } }
        }
    }
    fun connectMealSchool(school: NeisSchoolRef?) {
        val code = mutable.value.preferences.selection?.school?.code ?: return
        if (mutable.value.meal.saving) return
        viewModelScope.launch {
            mutable.update { it.copy(meal = it.meal.copy(saving = true)) }
            try {
                repository.setMealSchool(code, school)
                mutable.update { it.copy(meal = it.meal.copy(connecting = false, saving = false)) }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { mutable.update { it.copy(meal = it.meal.copy(saving = false, connectionError = "급식 학교를 저장하지 못했어요. 다시 시도해 주세요.")) } }
        }
    }
    fun clearMealCache() {
        viewModelScope.launch {
            mealJob?.cancelAndJoin()
            try {
                repository.clearMealCache()
                mutable.update { it.copy(meal = it.meal.copy(snapshot = null, todaySnapshot = null, loading = false, error = "저장된 급식을 삭제했어요.")) }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { mutable.update { it.copy(meal = it.meal.copy(error = "저장된 급식을 삭제하지 못했어요.")) } }
        }
    }
}
