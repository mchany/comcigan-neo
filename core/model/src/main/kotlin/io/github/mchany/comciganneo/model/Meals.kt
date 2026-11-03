package io.github.mchany.comciganneo.model

import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
data class NeisSchoolRef(
    val officeCode: String,
    val schoolCode: String,
    val name: String,
    val officeName: String,
    val address: String,
    val region: String = "",
)

@Serializable
enum class MealType(val code: String, val label: String) {
    BREAKFAST("1", "조식"), LUNCH("2", "중식"), DINNER("3", "석식"), UNKNOWN("", "기타");
    /**
     * 역할: NEIS 급식 종류 코드를 해당 열거형 값으로 변환한다.
     * 입력: code: String.
     * 출력: MealType 값.
     */
    companion object { fun fromCode(code: String) = entries.firstOrNull { it.code == code } ?: UNKNOWN }
}

@Serializable
data class MealEntry(
    val date: String,
    val typeCode: String,
    val typeName: String,
    val rawMenu: String,
    val menu: List<String>,
    val calories: String? = null,
    val rawNutrition: String = "",
    val nutrition: String = "",
    val rawOrigin: String = "",
    val origin: String = "",
    val loadedDate: String? = null,
) { val type: MealType get() = MealType.fromCode(typeCode) }

@Serializable
data class MealSnapshot(
    val school: NeisSchoolRef,
    val weekStart: String,
    val fetchedAt: Long,
    val meals: List<MealEntry>,
) {
    /**
     * 역할: 지정한 날짜에 해당하는 급식 항목 목록을 반환한다.
     * 입력: date: LocalDate.
     * 출력: List<MealEntry> 값.
     */
    fun forDate(date: LocalDate) = meals.filter { it.date == date.toString() }
    /**
     * 역할: 조회 시각과 날짜별 자료 유무를 기준으로 급식 캐시가 최신인지 확인한다.
     * 입력: date: LocalDate, now: Long.
     * 출력: Boolean 값.
     */
    fun isFresh(date: LocalDate, now: Long = System.currentTimeMillis()): Boolean {
        val ttl = if (forDate(date).isEmpty()) 3_600_000L else 21_600_000L
        return now >= fetchedAt && now - fetchedAt < ttl
    }
}

val MealAllergens = listOf("난류", "우유", "메밀", "땅콩", "대두", "밀", "고등어", "게", "새우", "돼지고기",
    "복숭아", "토마토", "아황산류", "호두", "닭고기", "쇠고기", "오징어", "조개류(굴·전복·홍합 포함)", "잣")
