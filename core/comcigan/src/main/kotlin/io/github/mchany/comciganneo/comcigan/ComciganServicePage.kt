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
    private val functionDeclaration = Regex("""\bfunction\s+($SCRIPT_IDENTIFIER)\s*\(([^)]*)\)\s*\{""")
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
    private const val postUrl = """\A\s*url\s*:\s*(?<urlquote>['"])\.(?<endpoint>/[0-9]+)\k<urlquote>"""
    private val postRoute = Regex(postUrl + """\s*(?=,|$)""")
    private val postSchoolParameters = Regex("""\s*sc\s*,\s*$SCRIPT_IDENTIFIER\s*""")
    private val postTimetableParameters = Regex("""\s*sc\s*,\s*sc2\s*,\s*da1\s*,\s*r\s*,\s*$SCRIPT_IDENTIFIER\s*""")
    private const val postData = postUrl + """\s*,\s*method\s*:\s*(?<method>$SCRIPT_IDENTIFIER)\s*,""" +
        """\s*contentType\s*:\s*(?<contentQuote>['"])text/plain;\s*charset=UTF-8\k<contentQuote>\s*,\s*data\s*:\s*"""
    private val postSearch = Regex(
        postData + """encodeURIComponent\(\s*btoa\(\s*(?<input>$SCRIPT_IDENTIFIER)\s*\+\s*$suffix\s*\)\s*\)\s*(?=,|$)""",
    )
    private val postSearchInput = Regex(
        """\bvar\s+(?<input>$SCRIPT_IDENTIFIER)\s*=\s*(?<prefixQuote>['"])(?<prefix>[0-9]+l)\k<prefixQuote>\s*\+\s*""" +
            """encodeURIComponent\(\s*sc\s*\)\s*;\s*return\s*\z""",
    )
    private val postTimetable = Regex(
        postData + """btoa\(\s*(?<input>$SCRIPT_IDENTIFIER)\s*\+\s*""" +
            """(?:(?<dateSep>['"])_\k<dateSep>\s*\+\s*(?<date>da1)\s*\+\s*)?""" +
            """(?<requestSep>['"])_\k<requestSep>\s*\+\s*r\s*\+\s*$suffix\s*\)\s*(?=,|$)""",
    )
    private val postTimetableInput = Regex(
        """\bvar\s+(?<school>$SCRIPT_IDENTIFIER)\s*=\s*sc\s*\+\s*sc2\s*""" +
            """(?:,\s*(?<dated>$SCRIPT_IDENTIFIER)\s*=\s*\k<school>\s*\+\s*(?<sep>['"])_\k<sep>\s*\+\s*da1\s*)?;\s*return\s*\z""",
    )
    private val postOverride = Regex("""(?:\A|,)\s*(?:['"])?(?:url|method|type|contentType|data)(?:['"])?\s*:""")
    private val scriptElement = Regex("""<script\b[^>]*>(.*?)</script\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val legacyPrefix = Regex("""\bsc_data\(\s*'([0-9]+)_""")
    private const val identifierPath = """$SCRIPT_IDENTIFIER(?:\s*\.\s*$SCRIPT_IDENTIFIER)*"""
    private val schoolListCall = Regex(
        """(?<![\p{L}\p{N}_$.])($SCRIPT_IDENTIFIER)\s*\(\s*$identifierPath\s*\.\s*학교검색\s*(?=[,)])""",
    )
    private const val formulaEnd =
        """(?=\s*(?:[;,)}]|</script>|\z)|[ \t]*[\r\n](?!\s*(?:[-+*/%&|^=<>?:.(\[`]|(?:in|instanceof)\b)))"""
    private val schoolCodeFormula = Regex(
        """(?<![\p{L}\p{N}_$.])(?:(?:var|let|const)\s+(?<variable>$SCRIPT_IDENTIFIER)\s*=\s*""" +
            """(?<value>0|[1-9][0-9]*)\s*;\s*(?:var|let|const)\s+$SCRIPT_IDENTIFIER\s*=\s*)?""" +
            """Math\s*\.\s*floor\s*\(\s*(?<rows>$identifierPath)\s*\[\s*i\s*]\s*\[\s*0\s*]\s*/\s*""" +
            """(?<divisor>[0-9]+|$SCRIPT_IDENTIFIER)\s*\)\s*\+\s*(?<offset>[0-9]+)$formulaEnd""",
    )
    private const val scriptLiteralOrComment =
        """//[^\r\n]*|/\*[\s\S]*?\*/|"(?:[^"\\\r\n]++|\\[\s\S])*+"|'(?:[^'\\\r\n]++|\\[\s\S])*+'|""" +
            """`(?:[^`\\]++|\\[\s\S])*+`"""
    private val ignoredScriptText = Regex(scriptLiteralOrComment)
    private val rendererBodyToken = Regex("$scriptLiteralOrComment|[{}]")
    private const val schoolCodeIndexChanged = "학교 코드 위치 형식이 변경됐어요."
    private const val updatedFieldChanged = "컴시간 페이지의 갱신 시각 형식이 변경됐어요."
    private const val timetableFieldsChanged = "컴시간 페이지의 현재 또는 원본 시간표 형식이 변경됐어요."
    private const val unqualified = """(?<![\p{L}\p{N}_$.])"""
    private val legacyUpdatedField = Regex(
        """\bda1\s*=\s*(?:$SCRIPT_IDENTIFIER\s*\(\s*)?H시간표\.($SERVICE_FIELD)(?![\p{L}\p{N}_$])""",
    )
    private val cachedUpdatedField = Regex(
        """${unqualified}da1\s*=\s*$SCRIPT_IDENTIFIER\s*\(\s*$SCRIPT_IDENTIFIER\s*\.\s*($SERVICE_FIELD)\s*\)""" +
            """\s*\|\|\s*(['"])0\2\s*(?=[;}]|\z)""",
    )
    private val defaultTimestamp = Regex("""\s*(['"])0\1\s*(?=[;,}]|\z)""")
    private val legacyCurrentField = Regex("""${unqualified}일일자료\s*=\s*Q자료\(\s*자료\.($SERVICE_FIELD)\s*\[""")
    private val legacyOriginalField = Regex("""${unqualified}원자료\s*=\s*Q자료\(\s*자료\.($SERVICE_FIELD)\s*\[""")
    private val slotRoles = Regex(
        """${unqualified}baSplit\s*\(\s*($SCRIPT_IDENTIFIER)\s*,\s*($SCRIPT_IDENTIFIER)\s*,\s*자료\s*\.\s*변경알림\s*\)""",
    )
    private val localAssignment = Regex("""$unqualified($SCRIPT_IDENTIFIER)\s*=(?!=)""")
    private val slotRead = Regex(
        """\s*($SCRIPT_IDENTIFIER)\s*\(\s*자료\s*\.\s*($SERVICE_FIELD)\s*""" +
            """\[\s*학년\s*]\s*\[\s*반\s*]\s*\[\s*요일\s*]\s*\[\s*교시\s*]\s*\)\s*(?=[;,}]|\z)""",
    )

    private data class ScriptFunction(val name: String, val body: String, val parameters: String) {
        val firstParameter: String get() = parameters.substringBefore(',').trim()
        fun hasParameter(name: String): Boolean = parameters.split(',').any { it.trim() == name }
    }

    private data class AjaxCall(val function: ScriptFunction, val options: String, val before: String)

    private data class AjaxRequest(
        val call: AjaxCall, val expression: MatchResult,
        val method: ComciganRequestMethod = ComciganRequestMethod.GET,
        val postSearchPrefix: String? = null,
    ) {
        val endpoint: String get() = expression.groups["endpoint"]!!.value
        val format: String get() = expression.groups["format"]!!.value
    }

    private fun postSearch(call: AjaxCall): AjaxRequest? {
        if (!postSchoolParameters.matches(call.function.parameters)) return null
        val request = postSearch.find(call.options) ?: return null
        val input = postSearchInput.find(call.before) ?: return null
        if (request.groups["input"]!!.value != input.groups["input"]!!.value || overridesPostOption(call, request)) return null
        return AjaxRequest(call, request, ComciganRequestMethod.POST, input.groups["prefix"]!!.value)
    }

    private fun postTimetable(call: AjaxCall): AjaxRequest? {
        if (!postTimetableParameters.matches(call.function.parameters)) return null
        val request = postTimetable.find(call.options) ?: return null
        val input = postTimetableInput.find(call.before) ?: return null
        val dated = input.groups["dated"]?.value
        val expected = when {
            request.groups["date"] == null -> dated
            dated == null -> input.groups["school"]!!.value
            else -> null
        }
        if (request.groups["input"]!!.value != expected || overridesPostOption(call, request)) return null
        return AjaxRequest(call, request, ComciganRequestMethod.POST)
    }

    private fun overridesPostOption(call: AjaxCall, request: MatchResult): Boolean =
        postOverride.containsMatchIn(call.options.substring(request.range.last + 1))

    private fun matchingPostMethod(source: String, school: AjaxRequest, timetable: AjaxRequest): Boolean {
        val reference = school.expression.groups["method"]!!.value
        if (reference != timetable.expression.groups["method"]!!.value ||
            school.call.function.hasParameter(reference) || timetable.call.function.hasParameter(reference)) return false
        val name = Regex.escape(reference)
        val tokens = Regex("""$scriptLiteralOrComment|(?<assignment>(?<![\p{L}\p{N}_$.])$name\s*=(?!=))|[{}]""")
        // The live page declares the method alongside a boolean in the same var statement.
        val declaration = Regex(
            """\b(?:var|let|const)\s+(?:$SCRIPT_IDENTIFIER\s*=\s*(?:true|false)\s*,\s*)*""" +
                """(?<binding>$name)\s*=\s*(?<quote>['"])POST\k<quote>\s*(?=[;,])""",
        )
        var bound = false
        for (script in scripts(source)) {
            val positions = declaration.findAll(script).map { it.groups["binding"]!!.range.first }.toSet()
            var depth = 0
            for (token in tokens.findAll(script)) {
                when (token.value) {
                    "{" -> depth++
                    "}" -> depth--
                    else -> {
                        val assignment = token.groups["assignment"] ?: continue
                        if (bound || depth != 0 || assignment.range.first !in positions) return false
                        bound = true
                    }
                }
            }
        }
        return bound
    }

    private fun scripts(source: String): List<String> =
        scriptElement.findAll(source).map { it.groupValues[1] }.toList().ifEmpty { listOf(source) }

    private fun functions(scripts: List<String>): List<ScriptFunction> = scripts.flatMap { script ->
        val declarations = codeMatches(functionDeclaration, script).toList()
        declarations.mapIndexed { index, declaration ->
            val nextStart = declarations.getOrNull(index + 1)?.range?.first ?: script.length
            ScriptFunction(declaration.groupValues[1], script.substring(declaration.range.last + 1, nextStart), declaration.groupValues[2])
        }
    }

    private fun rendererBody(section: String): String? {
        var depth = 1
        for (token in rendererBodyToken.findAll(section)) {
            when (token.value) {
                "{" -> depth++
                "}" -> if (--depth == 0) return section.substring(0, token.range.first)
            }
        }
        return null
    }

    private fun codeMatches(pattern: Regex, source: String): Sequence<MatchResult> = sequence {
        val ignored = ignoredScriptText.findAll(source).iterator()
        var next = if (ignored.hasNext()) ignored.next().range else null
        for (match in pattern.findAll(source)) {
            val start = match.range.first
            while (next != null && next.last < start) next = if (ignored.hasNext()) ignored.next().range else null
            if (next == null || start !in next) yield(match)
        }
    }

    private fun fieldCandidates(pattern: Regex, scripts: List<String>): Sequence<String> =
        scripts.asSequence().flatMap { codeMatches(pattern, it) }.map { it.groupValues[1] }

    private fun timetableCall(requestName: String): Regex =
        Regex("""$unqualified${Regex.escape(requestName)}\s*\(\s*sc\s*,\s*sc2\s*,\s*da1\s*,\s*r\s*,""")

    private fun cachedTimestampFields(beforeRequest: String): Sequence<String> {
        val fields = codeMatches(cachedUpdatedField, beforeRequest).associateBy { it.range.first }
        if (fields.isEmpty()) return emptySequence()
        val writes = codeMatches(localAssignment, beforeRequest).filter { it.groupValues[1] == "da1" }
        val firstField = fields.keys.first()
        if (writes.any { write ->
                val start = write.range.first
                start !in fields && (start >= firstField || defaultTimestamp.matchAt(beforeRequest, write.range.last + 1) == null)
            }) {
            throw StructureChanged(updatedFieldChanged)
        }
        return fields.values.asSequence().map { it.groupValues[1] }
    }

    private fun updatedField(scripts: List<String>, functions: List<ScriptFunction>, requestName: String?): String {
        val cached = if (requestName == null) emptySequence() else {
            val lookup = timetableCall(requestName)
            functions.asSequence().mapNotNull { rendererBody(it.body) }.flatMap { body ->
                val request = codeMatches(lookup, body).firstOrNull() ?: return@flatMap emptySequence()
                cachedTimestampFields(body.substring(0, request.range.first))
            }
        }
        return (fieldCandidates(legacyUpdatedField, scripts) + cached).distinct().take(2).toList().singleOrNull()
            ?: throw StructureChanged(updatedFieldChanged)
    }

    private fun renderedSlotField(body: String, assignments: List<MatchResult>, name: String): Pair<String, String>? =
        assignments.filter { it.groupValues[1] == name }.map { assignment ->
            val read = slotRead.matchAt(body, assignment.range.last + 1) ?: return null
            read.groupValues[1] to read.groupValues[2]
        }.distinct().singleOrNull()

    private fun timetableFields(scripts: List<String>, functions: List<ScriptFunction>): Pair<String, String> {
        val oldCurrent = fieldCandidates(legacyCurrentField, scripts).distinct().take(2).toList()
        val oldOriginal = fieldCandidates(legacyOriginalField, scripts).distinct().take(2).toList()
        if (oldCurrent.size > 1 || oldOriginal.size > 1) throw StructureChanged(timetableFieldsChanged)
        val legacy = if (oldCurrent.size == 1 && oldOriginal.size == 1) oldCurrent.single() to oldOriginal.single() else null

        val candidates = mutableSetOf<Pair<String, String>>()
        legacy?.let(candidates::add)
        for (function in functions.filter { it.firstParameter == "자료" }) {
            val body = rendererBody(function.body) ?: continue
            for (call in codeMatches(slotRoles, body)) {
                val (currentName, originalName) = call.destructured
                if (legacy != null && currentName == "일일자료" && originalName == "원자료") continue
                val assignments = codeMatches(localAssignment, body).takeWhile { it.range.first < call.range.first }.toList()
                // baSplit uses its first argument as the displayed lesson and its second as the original.
                val current = renderedSlotField(body, assignments, currentName) ?: throw StructureChanged(timetableFieldsChanged)
                val original = renderedSlotField(body, assignments, originalName) ?: throw StructureChanged(timetableFieldsChanged)
                if (current.first != original.first || current.second == original.second) throw StructureChanged(timetableFieldsChanged)
                candidates += current.second to original.second
            }
        }
        val fields = candidates.singleOrNull() ?: throw StructureChanged(timetableFieldsChanged)
        if (oldCurrent.any { it != fields.first } || oldOriginal.any { it != fields.second }) throw StructureChanged(timetableFieldsChanged)
        return fields
    }

    private fun schoolCodeCandidates(
        source: String, matchesRows: (String) -> Boolean,
    ): Sequence<Pair<Int, Int>> = codeMatches(schoolCodeFormula, source)
        .filter { matchesRows(it.groups["rows"]!!.value) }
        .map { match ->
            val operand = match.groups["divisor"]!!.value
            val divisor = if (operand == match.groups["variable"]?.value) {
                // Require a separate declaration statement before the calculation.
                var preceding = match.range.first - 1
                while (preceding >= 0 && source[preceding].isWhitespace()) preceding--
                if (preceding >= 0 && source[preceding] !in ";{}") throw StructureChanged(schoolCodeIndexChanged)
                match.groups["value"]!!.value.toIntOrNull()
            } else {
                operand.toIntOrNull()
            }
            val offset = match.groups["offset"]!!.value.toIntOrNull()
            if (divisor == null || divisor <= 0 || offset == null || offset !in 0..64) {
                throw StructureChanged(schoolCodeIndexChanged)
            }
            divisor to offset
        }

    private fun schoolCodeIndex(scripts: List<String>, functions: List<ScriptFunction>): Pair<Int, Int> {
        val direct = scripts.asSequence().flatMap { script ->
            schoolCodeCandidates(script) { rows ->
                rows == "학교명단" || rows.substringAfterLast('.', "").trim() == "학교검색"
            }
        }
        // A generic parameter is a school list when its caller passes the search result array.
        val renderers = scripts.asSequence().flatMap { codeMatches(schoolListCall, it) }.map { it.groupValues[1] }.toSet()
        val aliases = functions.asSequence().filter { it.name in renderers }.flatMap { function ->
            val body = rendererBody(function.body) ?: return@flatMap emptySequence()
            schoolCodeCandidates(body) { it == function.firstParameter }
        }
        return (direct + aliases).distinct().take(2).toList().singleOrNull() ?: throw StructureChanged(schoolCodeIndexChanged)
    }

    private fun timetablePrefix(
        source: String, functions: List<ScriptFunction>, requestName: String?, numericVersion: Boolean,
    ): String {
        val prefixes = mutableSetOf<String>()
        if (requestName != null) {
            val call = timetableCall(requestName)
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
        val scripts = scripts(source)
        val functions = functions(scripts)
        val calls = functions.flatMap { function ->
            ajaxOptions.findAll(function.body).map {
                AjaxCall(function, it.groupValues[1], function.body.substring(0, it.range.first))
            }.toList()
        }
        fun getRequests(pattern: Regex) = calls.flatMap { call ->
            pattern.findAll(call.options).map { AjaxRequest(call, it) }.toList()
        }
        val postCalls = calls.filter { call ->
            postRoute.containsMatchIn(call.options) &&
                (postSearchInput.containsMatchIn(call.before) || postTimetableInput.containsMatchIn(call.before))
        }
        val searchRequests = getRequests(searchRequest) + postCalls.mapNotNull(::postSearch)
        val timetableRequests = getRequests(timetableRequest) + postCalls.mapNotNull(::postTimetable)
        val encodedSearch = searchRequests.isNotEmpty() || timetableRequests.isNotEmpty() || postCalls.isNotEmpty() ||
            functions.any { it.name == "QAjaxSchool" || it.name == "QAjaxTimetable" }
        val school = searchRequests.singleOrNull()
        val timetable = timetableRequests.singleOrNull()
        if (encodedSearch && (school == null || timetable == null || school.call.function.name == timetable.call.function.name ||
                school.endpoint != timetable.endpoint || school.format != timetable.format || school.method != timetable.method)) {
            throw StructureChanged("컴시간 페이지의 검색 또는 시간표 요청 형식이 변경됐어요.")
        }
        val method = school?.method ?: ComciganRequestMethod.GET
        if (postCalls.isNotEmpty() && (postCalls.size != 2 || method != ComciganRequestMethod.POST ||
                school == null || timetable == null || !matchingPostMethod(source, school, timetable))) {
            throw StructureChanged("컴시간 페이지의 조회 메서드 형식이 변경됐어요.")
        }
        val format = school?.format ?: "enc2"
        val numericVersion = format.all { it in '0'..'9' }
        val (apiEndpoint, schoolSearchPrefix) = if (school == null) {
            val route = capture("검색 경로", """\.(/\d+\?\d+l)""")
            route.substringBefore('?') to route.substringAfter('?')
        } else {
            school.endpoint to (school.postSearchPrefix ?: school.expression.groups["prefix"]!!.value)
        }
        val token = if (school != null && timetable != null) requestToken(source, school.call.options, timetable.call.options) else null
        val encrypted = source.contains("CryptoJS.AES.decrypt")
        if (encodedSearch && !encrypted) throw StructureChanged("암호화 응답 처리 형식이 변경됐어요.")
        val responseKey = if (encrypted) key(source) else null
        val (divisor, offset) = if (encrypted) schoolCodeIndex(scripts, functions) else null to 2
        val requestName = timetable?.call?.function?.name
        val updatedField = updatedField(scripts, functions, requestName)
        val (currentField, originalField) = timetableFields(scripts, functions)
        return ServiceCodes(
            apiEndpoint, schoolSearchPrefix,
            timetablePrefix(source, functions, requestName, numericVersion),
            capture("교사", """(?<![\p{L}\p{N}_$])성명\s*=\s*(?:$SCRIPT_IDENTIFIER\s*\(\s*)?자료\.($SERVICE_FIELD)\s*\[\s*th\s*\]"""),
            capture("과목", """자료\.($SERVICE_FIELD)\s*\[\s*sb\s*\]"""),
            updatedField,
            currentField, originalField,
            responseKey, divisor, offset, format, encodedSearch, token,
            EncodedTextFields.parse(source, required = numericVersion), method,
        )
    }
}
