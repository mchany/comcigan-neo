package io.github.mchany.comciganneo.comcigan

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal fun HealthReport.toJson(): String = buildJsonObject {
    put("schemaVersion",schemaVersion)
    put("checkedAt",checkedAt)
    put("timezone",timezone)
    put("target",buildJsonObject { put("school",target);put("grade",1);put("classNumber",1) })
    put("overall",overall.name)
    put("steps",buildJsonArray {
        for (step in steps) add(buildJsonObject {
            put("id",step.id);put("status",step.status.name);put("errorType",step.errorType?.name?.let(::JsonPrimitive) ?: JsonNull)
            put("code",step.code);put("referenceDate",step.referenceDate);put("week",step.week?.let(::JsonPrimitive) ?: JsonNull)
            put("expectedStart",step.expectedStart?.let(::JsonPrimitive) ?: JsonNull)
            put("actualStart",step.actualStart?.let(::JsonPrimitive) ?: JsonNull)
            put("httpStatus",step.httpStatus?.let(::JsonPrimitive) ?: JsonNull)
            put("slotCount",step.slotCount?.let(::JsonPrimitive) ?: JsonNull)
            put("lessonCount",step.lessonCount?.let(::JsonPrimitive) ?: JsonNull)
            put("attempts",buildJsonArray {
                for (attempt in step.attempts) add(buildJsonObject {
                    put("number",attempt.number);put("status",attempt.status.name)
                    put("errorType",attempt.errorType?.name?.let(::JsonPrimitive) ?: JsonNull)
                    put("code",attempt.code);put("dateRefetches",attempt.dateRefetches)
                    put("httpStatus",attempt.httpStatus?.let(::JsonPrimitive) ?: JsonNull)
                })
            })
        })
    })
}.toString()

internal fun HealthReport.toMarkdown(): String = buildString {
    appendLine("## Parser Health")
    appendLine()
    appendLine("Checked at $checkedAt / $timezone")
    appendLine("Target: ${markdownText(target)} / 1학년 1반")
    appendLine("Overall: ${overall.name}")
    appendLine()
    appendLine("| Check | Result | Error type | Date / week | Detail | Attempts (date refetches) |")
    appendLine("|---|---|---|---|---|---|")
    for (step in steps) {
        val attempts = step.attempts.joinToString(" → ") {
            "${it.number}:${it.status}/${it.code}" + (it.httpStatus?.let { status -> "/HTTP$status" } ?: "") + "(${it.dateRefetches})"
        }
        val detail = listOfNotNull(step.code, step.httpStatus?.let { "HTTP $it" },
            step.expectedStart?.let { "expected=$it" },step.actualStart?.let { "actual=$it" },
            step.slotCount?.let { "slots=$it" },step.lessonCount?.let { "lessons=$it" }).joinToString(" ")
        appendLine("| ${step.id} | ${step.status} | ${step.errorType ?: "-"} | ${step.referenceDate} / ${step.week ?: "-"} | $detail | $attempts |")
    }
    appendLine()
    appendLine("Attempts count Health stage attempts, not individual HTTP requests. UNPUBLISHED is not proof of parsing recovery.")
}
private fun markdownText(text: String): String = text.take(200).replace("&","&amp;").replace("<","&lt;")
    .replace(">","&gt;").replace("|","&#124;").replace("@","&#64;").replace("`","&#96;")
    .replace("\r"," ").replace("\n"," ")

/** Write a complete sidecar in the same directory; failed writes never leave a partial result. */
internal fun writeHealthResult(path: Path, report: HealthReport) {
    val target = path.toAbsolutePath()
    val temporary = Files.createTempFile(target.parent,"parser-health-",".json")
    try {
        Files.writeString(temporary,report.toJson())
        Files.move(temporary,target,ATOMIC_MOVE,REPLACE_EXISTING)
    } finally { Files.deleteIfExists(temporary) }
}
