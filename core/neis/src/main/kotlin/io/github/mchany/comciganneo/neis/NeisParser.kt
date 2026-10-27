// API field mapping adapted from kimcore/neis.kt (MIT), commit 555daa7d0c24fb98b45e3c2f18bfe3ffa3c11135.
// Copyright (c) 2022 kimcore. See LICENSES/neis.kt-MIT.txt.
package io.github.mchany.comciganneo.neis

import io.github.mchany.comciganneo.model.*
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class NeisApiException(val code: String) : Exception("NEIS request failed ($code)")
class NeisStructureChanged : Exception("Unexpected NEIS response")
class NeisKeyRequired : Exception("NEIS API key required")
class NeisIncomplete : Exception("Incomplete NEIS response")

data class NeisPage(val total: Int, val rows: List<JsonObject>)

object NeisParser {
    private val json = Json { ignoreUnknownKeys = true }
    fun page(body: String, endpoint: String): NeisPage = parse {
        val root = json.parseToJsonElement(body).jsonObject
        root["RESULT"]?.jsonObject?.let { result ->
            val code = result.required("CODE")
            if (code == "INFO-200") return@parse NeisPage(0, emptyList())
            if (code != "INFO-000") throw NeisApiException(code)
            throw NeisStructureChanged()
        }
        val blocks = root[endpoint]?.jsonArray ?: throw NeisStructureChanged()
        val head = blocks.firstNotNullOfOrNull { it.jsonObject["head"]?.jsonArray } ?: throw NeisStructureChanged()
        val code = head.firstNotNullOfOrNull { it.jsonObject["RESULT"]?.jsonObject }?.required("CODE")
            ?: throw NeisStructureChanged()
        if (code == "INFO-200") return@parse NeisPage(0, emptyList())
        if (code != "INFO-000") throw NeisApiException(code)
        val total = head.firstNotNullOfOrNull { it.jsonObject["list_total_count"]?.jsonPrimitive?.intOrNull }
            ?.takeIf { it >= 0 } ?: throw NeisStructureChanged()
        val rows = blocks.firstNotNullOfOrNull { it.jsonObject["row"]?.jsonArray }?.map { it.jsonObject }
            ?: if (total == 0) emptyList() else throw NeisStructureChanged()
        if (rows.size > total) throw NeisStructureChanged()
        NeisPage(total, rows)
    }

    fun school(row: JsonObject): NeisSchoolRef = parse {
        NeisSchoolRef(row.required("ATPT_OFCDC_SC_CODE"), row.required("SD_SCHUL_CODE"), row.required("SCHUL_NM"),
            row.text("ATPT_OFCDC_SC_NM"), row.text("ORG_RDNMA"), row.text("LCTN_SC_NM"))
    }

    fun meal(row: JsonObject): MealEntry = parse {
        val date = LocalDate.parse(row.required("MLSV_YMD"), DateTimeFormatter.BASIC_ISO_DATE)
        val code = row.required("MMEAL_SC_CODE")
        val raw = row.required("DDISH_NM")
        MealEntry(date.toString(), code, row.text("MMEAL_SC_NM").ifBlank { MealType.fromCode(code).label },
            raw, plainText(raw).lines().filter { it.isNotBlank() }, row.text("CAL_INFO").takeIf { it.isNotBlank() },
            row.text("NTR_INFO"), plainText(row.text("NTR_INFO")), row.text("ORPLC_INFO"), plainText(row.text("ORPLC_INFO")),
            row.text("LOAD_DTM").takeIf { it.isNotBlank() })
    }

    fun plainText(value: String): String = value.replace(Regex("<br\\s*/?\\s*>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&amp;", "&").lines().joinToString("\n") { it.trim() }.trim()

    internal fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.required(key: String) = text(key).takeIf { it.isNotBlank() } ?: throw NeisStructureChanged()
    private inline fun <T> parse(block: () -> T): T = try { block() }
        catch (e: NeisApiException) { throw e }
        catch (e: NeisStructureChanged) { throw e }
        catch (_: Exception) { throw NeisStructureChanged() }
}
