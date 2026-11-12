/* Derived from hegelty/pycomcigan (MIT), Copyright (c) 2023 hegelty.
 * See THIRD_PARTY_NOTICES.md and LICENSES/pycomcigan-MIT.txt. */
package io.github.mchany.comciganneo.comcigan

import kotlinx.serialization.json.*

/** Reads known data expressions only. Never evaluates remote JavaScript. */
internal object ComciganServicePage {
    private val marker = Regex("""/\*\s*hour-(?:obfuscated-v2|ajax-obfuscated-v1)\s*\*/""")
    private val script = Regex(
        """/\*\s*hour-(?:obfuscated-v2|ajax-obfuscated-v1)\s*\*/\s*\(function\(a,k\)\{.*?\}\)\((\[\[.*?\]\]),\s*(\d+)\s*\);""",
        RegexOption.DOT_MATCHES_ALL,
    )

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
        fun capture(name: String, pattern: String, text: String = source): String =
            Regex(pattern).find(text)?.groupValues?.get(1)
                ?: throw StructureChanged("컴시간 페이지의 $name 형식이 변경됐어요.")
        fun ajax(name: String): String = Regex(
            """function\s+$name\s*\([^)]*\)\s*\{(.*?)\}\s*\)\s*;\s*\}""", RegexOption.DOT_MATCHES_ALL,
        ).find(source)?.groupValues?.get(1) ?: throw StructureChanged("컴시간 페이지의 요청 함수 형식이 변경됐어요.")
        val encodedSearch = source.contains("function QAjaxSchool")
        var token: HourRequestToken? = null
        val searchRoute = if (encodedSearch) {
            val school = ajax("QAjaxSchool")
            val endpoint = capture("검색 경로", """url\s*:\s*['"]\.(/\d+\?)['"]""", school)
            val prefix = capture("검색 요청 코드",
                """btoa\(\s*['"](\d+l)['"]\s*\+\s*encodeURIComponent\(\s*sc\s*\)\s*\+\s*['"]_enc5['"]\s*\)""", school)
            val timetable = ajax("QAjaxTimetable")
            if (capture("시간표 경로", """url\s*:\s*['"]\.(/\d+\?)['"]""", timetable) != endpoint ||
                !Regex("""['"]_enc5['"]""").containsMatchIn(timetable)) {
                throw StructureChanged("시간표 요청 형식이 변경됐어요.")
            }
            token = requestToken(source, school, timetable)
            endpoint + prefix
        } else capture("검색 경로", """\.(/\d+\?\d+l)""")
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
            capture("요청 코드", """sc_data\(\s*'(\d+)_"""),
            "자료" + capture("교사", """성명\s*=\s*(?:Q성명\(\s*)?자료\.자료(\d+)"""),
            "자료" + capture("과목", """자료\.자료(\d+)\[sb\]"""),
            "자료" + capture("갱신 시각", """=\s*(?:QnmsbChange\(\s*)?H시간표\.자료(\d+)"""),
            "자료" + capture("현재 시간표", """일일자료\s*=\s*Q자료\(\s*자료\.자료(\d+)"""),
            "자료" + capture("원본 시간표", """원자료\s*=\s*Q자료\(\s*자료\.자료(\d+)"""),
            responseKey, divisor, offset, if (encodedSearch) "enc5" else "enc2", encodedSearch, token,
            EncodedTextFields.parse(source),
        )
    }
}
