package io.github.mchany.comciganneo.neis

import io.github.mchany.comciganneo.model.*
import kotlinx.coroutines.runBlocking

/** Explicit read-only probe. A keyless probe can validate one day, never a truncated week. */
fun main(args: Array<String>) = runBlocking {
    val key = System.getenv("NEIS_API_KEY").orEmpty()
    val client = NeisClient(key, allowSample = key.isBlank())
    val schools = client.searchSchools(args.firstOrNull() ?: "중일고등학교")
    val school = schools.single()
    val from = if (key.isBlank()) schoolToday() else weekStart()
    val to = if (key.isBlank()) from else from.plusDays(6)
    val meals = client.meals(school, from, to)
    println("${school.name}: $from..$to, ${meals.size} meals; " + if (key.isBlank()) "sample only" else "authenticated")
}
