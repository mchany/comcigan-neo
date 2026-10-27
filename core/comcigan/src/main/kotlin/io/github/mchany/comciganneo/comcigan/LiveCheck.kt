package io.github.mchany.comciganneo.comcigan

import io.github.mchany.comciganneo.model.ClassSelection
import kotlinx.coroutines.runBlocking

/** Opt-in network smoke check. Not part of offline unit tests. */
fun main(args: Array<String>) = runBlocking {
    val query = args.firstOrNull() ?: "경기북과학고"
    val client = ComciganClient()
    val schools = client.searchSchools(query)
    check(schools.isNotEmpty()) { "No matching school" }
    val school = schools.first()
    println("학교 검색 성공: ${school.name} (${school.region})")
    for (week in 0..1) {
        try {
            val snapshot = client.fetch(ClassSelection(school, 1, 1), week)
            val count = snapshot.slots.count { it.current != null }
            check(count > 0) { "No lessons parsed" }
            println("주 시작일 ${snapshot.weekStart}: 수업 ${count}개, 변경 ${snapshot.slots.count { it.changed }}개")
        } catch (e: TimetableUnavailable) {
            if (week == 0) throw e
            println("다음 주 미게시")
        }
    }
}
