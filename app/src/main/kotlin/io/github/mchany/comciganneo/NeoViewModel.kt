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
    val notificationSetupSaving: Boolean = false,
) {
    val mealSchool: NeisSchoolRef? get() = preferences.mealSchool
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
    /**
     * 역할: 시간표 학교 및 학급 설정 화면을 연다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    fun openSetup() { mutable.update { it.copy(setup = true, candidate = null, setupError = null, classCounts = emptyList()) } }
    /**
     * 역할: 시간표 학교 설정 화면을 닫고 진행 중인 검색을 취소한다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    fun closeSetup() {
        searchJob?.cancel(); candidateJob?.cancel()
        mutable.update { it.copy(setup = false, searching = false, candidate = null, setupError = null) }
    }
    /**
     * 역할: 학교 검색어로 결과를 가져와 화면 상태를 갱신한다.
     * 입력: query: String.
     * 출력: 없음(Unit).
     */
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
    /**
     * 역할: 선택 학교의 시간표 미리보기를 조회하고 학급 수를 갱신한다.
     * 입력: school: School.
     * 출력: 없음(Unit).
     */
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
    /**
     * 역할: 선택한 학교와 학년, 반을 저장한다.
     * 입력: grade: Int, classNumber: Int.
     * 출력: 없음(Unit).
     */
    fun select(grade: Int, classNumber: Int) {
        val school = mutable.value.candidate ?: return
        setting {
            repository.select(ClassSelection(school, grade, classNumber))
            mutable.update { it.copy(setup = false) }
        }
    }
    /**
     * 역할: 표시할 주간 시간표를 바꾸고 자료를 불러온다.
     * 입력: week: Int.
     * 출력: 없음(Unit).
     */
    fun week(week: Int) {
        if (week == mutable.value.week) return
        mutable.update { it.copy(week = week) }; load()
    }
    /**
     * 역할: 시간표를 강제로 다시 조회한다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    fun refresh() { if (!mutable.value.loading) load(force = true) }
    /**
     * 역할: 앱이 다시 보일 때 오늘 날짜와 시간표 및 급식 캐시의 최신 상태를 확인한다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    fun onForeground() {
        val today = schoolToday()
        if (today != lastToday) {
            val wasToday = mutable.value.meal.date == lastToday.toString()
            lastToday = today
            if (wasToday) mutable.update { it.copy(meal = it.meal.copy(date = today.toString())) }
        }
        val meal = mutable.value.meal
        if (!meal.loading && mutable.value.mealSchool != null && mutable.value.preferences.mealKeyConfigured &&
            meal.snapshot?.isFresh(LocalDate.parse(meal.date)) != true) loadMeals()
        val snapshot = mutable.value.snapshot
        if (!mutable.value.loading && mutable.value.preferences.selection != null &&
            (snapshot == null || snapshot.weekStart != weekStart(offset = mutable.value.week).toString() ||
                System.currentTimeMillis() - snapshot.fetchedAt > 300_000)) load()
    }
    /**
     * 역할: 위젯 렌더링에 필요한 저장 설정과 시간표 및 급식 자료를 읽는다.
     * 입력: force: Boolean.
     * 출력: 없음(Unit).
     */
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
    /**
     * 역할: 화면 설정 변경을 실행하고 오류 상태를 갱신한다.
     * 입력: block: suspend () -> Unit.
     * 출력: 없음(Unit).
     */
    private fun setting(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = "설정을 저장하지 못했어요. 다시 시도해 주세요.") } }
        }
    }
    /**
     * 역할: 화면 테마 모드를 저장한다.
     * 입력: mode: ThemeMode.
     * 출력: 없음(Unit).
     */
    fun theme(mode: ThemeMode) = setting { repository.theme(mode) }
    /**
     * 역할: 시스템 동적 색상 사용 여부를 저장한다.
     * 입력: enabled: Boolean.
     * 출력: 없음(Unit).
     */
    fun dynamic(enabled: Boolean) = setting { repository.dynamic(enabled) }
    /**
     * 역할: 교사 이름 표시 여부를 저장한다.
     * 입력: enabled: Boolean.
     * 출력: 없음(Unit).
     */
    fun teachers(enabled: Boolean) = setting { repository.teachers(enabled) }
    /**
     * 역할: 위젯 색상 팔레트 선택을 저장한다.
     * 입력: palette: WidgetPalette.
     * 출력: 없음(Unit).
     */
    fun widgetPalette(palette: WidgetPalette) = setting { repository.widgetPalette(palette) }
    /**
     * 역할: 해당 종류의 알림 사용 여부를 저장한다.
     * 입력: kind: NotificationKind, enabled: Boolean.
     * 출력: 없음(Unit).
     */
    fun notificationEnabled(kind: NotificationKind, enabled: Boolean) = setting { repository.updateNotification(kind, enabled = enabled) }
    /**
     * 역할: 해당 종류의 알림 시각을 저장한다.
     * 입력: kind: NotificationKind, minutes: Int.
     * 출력: 없음(Unit).
     */
    fun notificationTime(kind: NotificationKind, minutes: Int) = setting { repository.updateNotification(kind, minutes = minutes) }
    /**
     * 역할: 초기 급식 학교 설정 단계로 돌아간다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    fun backToMealSetup() = setting { repository.returnToMealSetup() }
    /**
     * 역할: 초기 알림 설정 상태를 완료로 저장한다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    fun finishNotificationSetup() {
        if (mutable.value.notificationSetupSaving) return
        viewModelScope.launch {
            mutable.update { it.copy(notificationSetupSaving = true, error = null) }
            try {
                repository.finishInitialSetup()
                mutable.update { it.copy(notificationSetupSaving = false) }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { mutable.update { it.copy(notificationSetupSaving = false, error = "알림 설정을 저장하지 못했어요. 다시 시도해 주세요.") } }
        }
    }
    /**
     * 역할: 시간표와 급식 캐시를 삭제한다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    fun clearCache() = setting {
        loadJob?.cancelAndJoin()
        mealJob?.cancelAndJoin()
        repository.clearCache()
        mutable.update { it.copy(snapshot = null, loading = false, error = "저장된 시간표와 급식을 삭제했어요. 새로고침하면 다시 가져옵니다.",
            meal = it.meal.copy(snapshot = null, loading = false, error = "저장된 급식을 삭제했어요.")) }
    }

    /**
     * 역할: 급식 화면에서 표시할 날짜를 바꾸고 새 자료를 불러온다.
     * 입력: date: LocalDate.
     * 출력: 없음(Unit).
     */
    fun mealDate(date: LocalDate) {
        if (date.toString() == mutable.value.meal.date) return
        mutable.update { it.copy(meal = it.meal.copy(date = date.toString())) }
        loadMeals()
    }
    /**
     * 역할: 급식 자료를 강제로 새로 조회한다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    fun refreshMeals() = loadMeals(force = true)
    /**
     * 역할: 현재 설정과 날짜에 필요한 급식 자료를 불러온다.
     * 입력: force: Boolean.
     * 출력: 없음(Unit).
     */
    private fun loadMeals(force: Boolean = false) {
        mealJob?.cancel()
        val school = mutable.value.mealSchool ?: return
        val date = LocalDate.parse(mutable.value.meal.date)
        mealJob = viewModelScope.launch {
            mutable.update { it.copy(meal = it.meal.copy(loading = true, error = null,
                snapshot = it.meal.snapshot?.takeIf { s -> s.school == school && s.weekStart == weekStart(date).toString() })) }
            try {
                val cache = repository.cachedMeals(school, date)
                mutable.update { it.copy(meal = it.meal.copy(snapshot = cache)) }
                val snapshot = repository.refreshMeals(school, date, force)
                ensureActive()
                mutable.update { it.copy(meal = it.meal.copy(snapshot = snapshot, loading = false)) }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { mutable.update { it.copy(meal = it.meal.copy(loading = false, error = mealUserMessage(e))) } }
        }
    }
    /**
     * 역할: 급식 학교 연결 화면을 연다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    fun openMealConnection() {
        mutable.update { it.copy(meal = it.meal.copy(connecting = true, connectionError = null)) }
        if (mutable.value.preferences.mealKeyConfigured) searchMealSchools(mutable.value.preferences.selection?.school?.name.orEmpty())
    }
    /**
     * 역할: 초기 급식 학교 연결 단계를 건너뛴다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    fun skipInitialMealSetup() {
        if (mutable.value.meal.saving) return
        viewModelScope.launch {
            mutable.update { it.copy(meal = it.meal.copy(saving = true, connectionError = null)) }
            try {
                repository.finishMealSetup()
                mealSearchJob?.cancel()
                mutable.update { it.copy(meal = it.meal.copy(saving = false, searching = false)) }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { mutable.update { it.copy(meal = it.meal.copy(saving = false, connectionError = "설정을 저장하지 못했어요. 다시 시도해 주세요.")) } }
        }
    }
    /**
     * 역할: 급식 학교 연결 화면과 검색 작업을 닫는다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    fun closeMealConnection() {
        if (mutable.value.meal.saving) return
        mealSearchJob?.cancel()
        mutable.update { it.copy(meal = it.meal.copy(connecting = false, searching = false)) }
    }
    /**
     * 역할: NEIS에서 급식 학교 검색 결과를 조회한다.
     * 입력: query: String.
     * 출력: 없음(Unit).
     */
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
    /**
     * 역할: 시간표 학교와 급식 학교의 연결 설정을 저장한다.
     * 입력: school: NeisSchoolRef?.
     * 출력: 없음(Unit).
     */
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
    /**
     * 역할: 급식 캐시 전체를 삭제하고 변경 이벤트를 알린다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    fun clearMealCache() {
        viewModelScope.launch {
            mealJob?.cancelAndJoin()
            try {
                repository.clearMealCache()
                mutable.update { it.copy(meal = it.meal.copy(snapshot = null, loading = false, error = "저장된 급식을 삭제했어요.")) }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { mutable.update { it.copy(meal = it.meal.copy(error = "저장된 급식을 삭제하지 못했어요.")) } }
        }
    }
}
