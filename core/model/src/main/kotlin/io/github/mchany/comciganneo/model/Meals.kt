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
    fun forDate(date: LocalDate) = meals.filter { it.date == date.toString() }
    fun isFresh(date: LocalDate, now: Long = System.currentTimeMillis()): Boolean {
        val ttl = if (forDate(date).isEmpty()) 3_600_000L else 21_600_000L
        return now >= fetchedAt && now - fetchedAt < ttl
    }
}

val MealAllergens = listOf("난류", "우유", "메밀", "땅콩", "대두", "밀", "고등어", "게", "새우", "돼지고기",
    "복숭아", "토마토", "아황산류", "호두", "닭고기", "쇠고기", "오징어", "조개류(굴·전복·홍합 포함)", "잣")
