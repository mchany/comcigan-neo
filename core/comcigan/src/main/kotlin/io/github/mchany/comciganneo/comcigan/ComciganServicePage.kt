/* Derived from hegelty/pycomcigan (MIT), Copyright (c) 2023 hegelty.
 * See THIRD_PARTY_NOTICES.md and LICENSES/pycomcigan-MIT.txt. */
package io.github.mchany.comciganneo.comcigan

import kotlinx.serialization.json.*

internal const val SCRIPT_IDENTIFIER = """[\p{L}_$][\p{L}\p{N}_$]*"""
internal const val SERVICE_FIELD = """(?:자료[0-9]+|[A-Za-z_$][A-Za-z0-9_$]*)"""

/** Reads known data expressions only. Never evaluates remote JavaScript. */
internal object ComciganServicePage {
    private val marker = Regex("""/\*\s*hour-(?:obfuscated-v2|ajax-obfuscated-v1)\s*\*/""")
    private val script = Regex(
        """/\*\s*hour-(?:obfuscated-v2|ajax-obfuscated-v1)\s*\*/\s*\(function\(a,k\)\{.*?\}\)\((\[\[.*?\]\]),\s*(\d+)\s*\);""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val functionDeclaration = Regex("""\bfunction\s+($SCRIPT_IDENTIFIER)\s*\([^)]*\)\s*\{""")
    private val ajaxOptions = Regex(
        """(?:\$|jQuery)\s*\.\s*ajax\s*\(\s*\{(.*?)\}\s*\)\s*;\s*\}""", RegexOption.DOT_MATCHES_ALL,
    )
    private const val endpoint = """\burl\s*:\s*(?<urlquote>['"])\.(?<endpoint>/[0-9]+)\?\k<urlquote>\s*\+\s*"""
    private const val suffix = """(?<suffixquote>['"])_(?<format>$SERVICE_RESPONSE_VERSION)\k<suffixquote>"""
    private val searchRequest = Regex(
        endpoint + """encodeURIComponent\(\s*btoa\(\s*(?<prefixquote>['"])(?<prefix>[0-9]+l)\k<prefixquote>""" +
            """\s*\+\s*encodeURIComponent\(\s*sc\s*\)\s*\+\s*$suffix\s*\)\s*\)\s*(?=,|$)""",
    )
    private val timetableRequest = Regex(endpoint + """btoa\([^;{}]*?\+\s*$suffix\s*\)\s*(?=,|$)""")
    private val legacyPrefix = Regex("""\bsc_data\(\s*'([0-9]+)_""")

    private data class ScriptFunction(val name: String, val body: String)

    private data class AjaxRequest(val functionName: String, val options: String, val url: MatchResult) {
        val endpoint: String get() = url.groups["endpoint"]!!.value
        val format: String get() = url.groups["format"]!!.value
    }

    private fun functions(source: String): List<ScriptFunction> {
        val declarations = functionDeclaration.findAll(source).toList()
        return declarations.mapIndexed { index, declaration ->
            val nextStart = declarations.getOrNull(index + 1)?.range?.first ?: source.length
            ScriptFunction(declaration.groupValues[1], source.substring(declaration.range.last + 1, nextStart))
        }
    }

    private fun timetablePrefix(
        source: String, functions: List<ScriptFunction>, requestName: String?, numericVersion: Boolean,
    ): String {
        val prefixes = mutableSetOf<String>()
        if (requestName != null) {
            val call = Regex(
                """(?<![\p{L}\p{N}_$.])${Regex.escape(requestName)}\s*\(\s*sc\s*,\s*sc2\s*,\s*da1\s*,\s*r\s*,""",
            )
            for ((name, body) in functions) {
                if (!call.containsMatchIn(body)) continue
                val prefixCall = Regex(
                    """(?<![\p{L}\p{N}_$.])${Regex.escape(name)}\s*\(\s*(['"])([0-9]+)_\1\s*,""",
                )
                prefixCall.findAll(source).mapTo(prefixes) { it.groupValues[2] }
            }
        }
        if (prefixes.isEmpty() && !numericVersion) {
            return legacyPrefix.find(source)?.groupValues?.get(1)
                ?: throw StructureChanged("컴시간 페이지의 요청 코드 형식이 변경됐어요.")
        }
        return prefixes.singleOrNull()
            ?: throw StructureChanged("컴시간 페이지의 요청 코드 형식이 변경됐어요.")
    }

    private fun readablePage(page: String): String {
        var source = page
        repeat(4) {
            if (!marker.containsMatchIn(source)) return source
            if (!script.containsMatchIn(source)) throw StructureChanged("컴시간 페이지 스크립트 형식이 변경됐어요.")
            source = script.replace(source) { match ->
                try {
                    val chunks = Json.parseToJsonElement(match.groupValues[1]).jsonArray
                    val key = match.groupValues[2].toInt()
                    buildString {
                        for (chunk in chunks) {
                            val parts = chunk.jsonArray
                            val value = parts[1].jsonPrimitive.content
                            if (parts[0].jsonPrimitive.int == 1) append(value)
                            else {
                                var offset = 0
                                while (offset < value.length) {
                                    val end = (offset + 4).coerceAtMost(value.length)
                                    append((value.substring(offset, end).toInt(16) xor
                                        ((key + offset / 4 * 13) and 255)).toChar())
                                    offset += 4
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    throw StructureChanged("컴시간 페이지 스크립트를 읽을 수 없어요.", e)
                }
            }
            if (source.length > 16 * 1024 * 1024) throw StructureChanged("컴시간 페이지 스크립트가 너무 커요.")
        }
        if (marker.containsMatchIn(source)) throw StructureChanged("컴시간 페이지 스크립트 중첩 형식이 변경됐어요.")
        return source
    }

    private fun key(source: String): String {
        val literal = Regex("""var\s+key\s*=\s*CryptoJS\.enc\.Hex\.parse\(\s*['"]([0-9a-fA-F]{64})['"]\s*\)""")
            .find(source)?.groupValues?.get(1)
        if (literal != null) return literal
        val assembled = Regex(
            """var\s+key\s*=\s*CryptoJS\.enc\.Hex\.parse\(\s*\(function\(a\)\s*\{\s*return\s+([^;]+);\s*\}\)\(\s*(\[[^\]]+\])\s*\)\s*\)"""
        ).find(source) ?: throw StructureChanged("컴시간 페이지의 응답 복호화 키 형식이 변경됐어요.")
        val expression = assembled.groupValues[1].trim()
        val array = assembled.groupValues[2]
        val item = """['"][0-9a-fA-F]+['"]"""
        if (!Regex("""a\[\d+\](?:\s*\+\s*a\[\d+\]){0,15}""").matches(expression) ||
            !Regex("""\[\s*$item(?:\s*,\s*$item){0,15}\s*\]""").matches(array)) {
            throw StructureChanged("컴시간 응답 키 조합 형식이 변경됐어요.")
        }
        val fragments = Regex("""['"]([0-9a-fA-F]+)['"]""").findAll(array).map { it.groupValues[1] }.toList()
        val result = Regex("""a\[(\d+)\]""").findAll(expression).joinToString("") {
            val index = it.groupValues[1].toIntOrNull()
            fragments.getOrNull(index ?: -1) ?: throw StructureChanged("컴시간 응답 키 조각 위치를 읽을 수 없어요.")
        }
        if (!result.matches(Regex("[0-9a-fA-F]{64}"))) throw StructureChanged("컴시간 응답 키 길이가 변경됐어요.")
        return result
    }

    private fun requestToken(source: String, school: String, timetable: String): HourRequestToken? {
        val headerName = Regex("""['"]X-Hour-Token['"]\s*:""")
        val sections = listOf(school, timetable)
        if (sections.none { headerName.containsMatchIn(it) }) return null
        val reference = Regex(
            """headers\s*:\s*\{[^{}]*?['"]X-Hour-Token['"]\s*:\s*window\.__HourRequestToken\s*\|\|\s*(['"])\1\s*(?=[,\}])"""
        )
        if (sections.any { headerName.findAll(it).count() != 1 || !reference.containsMatchIn(it) }) {
            throw StructureChanged("컴시간 페이지의 요청 토큰 헤더 형식이 변경됐어요.")
        }
        val literals = Regex("""\bwindow\.__HourRequestToken\s*=\s*(['"])([0-9a-fA-F]+)\1\s*;""")
            .findAll(source).map { it.groupValues[2] }.distinct().toList()
        val value = literals.singleOrNull()
            ?: throw StructureChanged("컴시간 페이지의 요청 토큰을 읽을 수 없어요.")
        return HourRequestToken.parse(value)
    }

    fun codes(page: String): ServiceCodes {
        val source = readablePage(page)
        fun capture(name: String, pattern: String): String =
            Regex(pattern).find(source)?.groupValues?.get(1)
                ?: throw StructureChanged("컴시간 페이지의 $name 형식이 변경됐어요.")
        val functions = functions(source)
        val calls = functions.flatMap { (name, body) ->
            ajaxOptions.findAll(body).map { name to it.groupValues[1] }.toList()
        }
        fun requests(pattern: Regex) = calls.flatMap { (name, options) ->
            pattern.findAll(options).map { AjaxRequest(name, options, it) }.toList()
        }
        val searchRequests = requests(searchRequest)
        val timetableRequests = requests(timetableRequest)
        val encodedSearch = searchRequests.isNotEmpty() || timetableRequests.isNotEmpty() ||
            functions.any { it.name == "QAjaxSchool" || it.name == "QAjaxTimetable" }
        val school = searchRequests.singleOrNull()
        val timetable = timetableRequests.singleOrNull()
        if (encodedSearch && (school == null || timetable == null || school.functionName == timetable.functionName ||
                school.endpoint != timetable.endpoint || school.format != timetable.format)) {
            throw StructureChanged("컴시간 페이지의 검색 또는 시간표 요청 형식이 변경됐어요.")
        }
        val format = school?.format ?: "enc2"
        val numericVersion = format.all { it in '0'..'9' }
        val searchRoute = school?.let {
            it.endpoint + "?" + it.url.groups["prefix"]!!.value
        } ?: capture("검색 경로", """\.(/\d+\?\d+l)""")
        val token = if (school != null && timetable != null) requestToken(source, school.options, timetable.options) else null
        val encrypted = source.contains("CryptoJS.AES.decrypt")
        if (encodedSearch && !encrypted) throw StructureChanged("암호화 응답 처리 형식이 변경됐어요.")
        val responseKey = if (encrypted) key(source) else null
        val schoolIndex = if (encrypted) Regex(
            """Math\.floor\(\s*학교명단\[i\]\[0\]\s*/\s*(\d+)\s*\)\s*\+\s*(\d+)"""
        ).find(source) ?: throw StructureChanged("학교 코드 위치 형식이 변경됐어요.") else null
        val divisor = schoolIndex?.groupValues?.get(1)?.toIntOrNull()
        val offset = if (schoolIndex == null) 2 else schoolIndex.groupValues[2].toIntOrNull()
            ?: throw StructureChanged("학교 코드 위치 형식이 변경됐어요.")
        if (encrypted && (divisor == null || divisor <= 0 || offset !in 0..64)) {
            throw StructureChanged("학교 코드 위치 형식이 변경됐어요.")
        }
        return ServiceCodes(
            searchRoute,
            timetablePrefix(source, functions, timetable?.functionName, numericVersion),
            capture("교사", """(?<![\p{L}\p{N}_$])성명\s*=\s*(?:$SCRIPT_IDENTIFIER\s*\(\s*)?자료\.($SERVICE_FIELD)\s*\[\s*th\s*\]"""),
            capture("과목", """자료\.($SERVICE_FIELD)\s*\[\s*sb\s*\]"""),
            capture("갱신 시각", """\bda1\s*=\s*(?:$SCRIPT_IDENTIFIER\s*\(\s*)?H시간표\.($SERVICE_FIELD)(?![\p{L}\p{N}_$])"""),
            capture("현재 시간표", """(?<![\p{L}\p{N}_$])일일자료\s*=\s*Q자료\(\s*자료\.($SERVICE_FIELD)\s*\["""),
            capture("원본 시간표", """(?<![\p{L}\p{N}_$])원자료\s*=\s*Q자료\(\s*자료\.($SERVICE_FIELD)\s*\["""),
            responseKey, divisor, offset, format, encodedSearch, token,
            EncodedTextFields.parse(source, required = numericVersion),
        )
    }
}
