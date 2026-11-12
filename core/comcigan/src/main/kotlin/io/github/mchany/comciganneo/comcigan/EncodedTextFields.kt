/* QnmsbChange grammar adapted from hegelty/pycomcigan (MIT).
 * See THIRD_PARTY_NOTICES.md and LICENSES/pycomcigan-MIT.txt. */
package io.github.mchany.comciganneo.comcigan

import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64

class EncodedTextFields private constructor(
    private val key: ByteArray, private val startField: String, private val timesField: String,
) {
    /**
     * 역할: 진단 문자열에서 필드 복원 키를 숨긴다.
     * 입력: 없음.
     * 출력: 민감한 값이 없는 고정 문자열.
     */
    override fun toString(): String = "EncodedTextFields([redacted])"

    /**
     * 역할: Base64와 반복 바이트 XOR을 해제하고 CP949를 엄격히 디코딩한다.
     * 입력: 인코딩된 JSON 문자열 또는 빈 값.
     * 출력: 복원 문자열이며 잘못된 타입·인코딩은 형식 오류로 거부한다.
     */
    internal fun decode(value: JsonElement): String {
        try {
            if (value == JsonNull) return ""
            val primitive = value.jsonPrimitive
            require(primitive.isString)
            val encoded = primitive.content
            if (encoded.isEmpty()) return ""
            val bytes = Base64.getDecoder().decode(encoded)
            require(Base64.getEncoder().encodeToString(bytes) == encoded)
            for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor key[i % key.size].toInt()).toByte()
            return Charset.forName("MS949").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            // Never attach decoder exceptions: they may contain source bytes or field values.
            throw StructureChanged("인코딩된 컴시간 문자열을 읽을 수 없어요.")
        }
    }

    /**
     * 역할: 인증된 시간표 JSON의 문자열과 이동한 자료 필드를 기존 스키마로 정규화한다.
     * 입력: HMAC 검증·AES 복호화 후의 JSON과 페이지에서 읽은 서비스 코드.
     * 출력: 원본을 변경하지 않은 정규화 JSON이며 필수 자료 누락은 형식 오류다.
     */
    internal fun normalize(data: JsonObject, codes: ServiceCodes): JsonObject = try {
        val result = data.toMutableMap()
        for (field in listOf(codes.teacherField, codes.subjectField)) {
            result[field] = JsonArray(data.getValue(field).jsonArray.mapIndexed { index, value ->
                if (field == codes.subjectField && index == 0 && value is JsonPrimitive) JsonPrimitive("")
                else JsonPrimitive(decode(value))
            })
        }
        for (field in listOf(codes.updatedField, startField)) result[field] = JsonPrimitive(decode(data.getValue(field)))
        for (field in listOf("지역명", "학교명")) data[field]?.let { result[field] = JsonPrimitive(decode(it)) }
        result["시작일"] = result.getValue(startField)
        val times = data.getValue(timesField).jsonArray
        require(times.all { it is JsonPrimitive && it.isString })
        result["일과시간"] = times
        JsonObject(result)
    } catch (e: StructureChanged) { throw e }
      catch (_: Exception) { throw StructureChanged("인코딩된 시간표 자료 필드 형식이 변경됐어요.") }

    companion object {
        private val decoder = Regex(
            """function\s+QnmsbChange\s*\(\s*(?<value>[\p{L}\p{N}_$]+)\s*\)\s*\{\s*if\s*\(\s*\k<value>\s*==\s*undefined\s*\|\|\s*\k<value>\s*===\s*(?:''|"")\s*\)\s*return\s*(?:''|"")\s*;\s*""" +
            """var\s+(?<key>[\p{L}\p{N}_$]+)\s*=\s*String\.fromCharCode\(\s*(?<numbers>[0-9]{1,3}(?:\s*,\s*[0-9]{1,3}){0,255})\s*\)\s*;\s*""" +
            """var\s+(?<raw>[\p{L}\p{N}_$]+)\s*=\s*atob\(\s*\k<value>\s*\)\s*;\s*""" +
            """var\s+(?<bytes>[\p{L}\p{N}_$]+)\s*=\s*new\s+Uint8Array\(\s*\k<raw>\.length\s*\)\s*;\s*""" +
            """for\s*\(\s*""" +
            """var\s+(?<index>[\p{L}\p{N}_$]+)\s*=\s*0\s*;\s*\k<index>\s*<\s*\k<bytes>\.length\s*;\s*\k<index>\+\+\s*\)\s*\{\s*\k<bytes>\[\s*\k<index>\s*\]\s*=\s*\k<raw>\.charCodeAt\(\s*\k<index>\s*\)\s*\^\s*\k<key>\.charCodeAt\(\s*\k<index>\s*%\s*\k<key>\.length\s*\)\s*;\s*\}\s*""" +
            """return\s+new\s+TextDecoder\(\s*['"]euc-kr['"]\s*,\s*\{\s*fatal\s*:\s*true\s*\}\s*\)\.decode\(\s*\k<bytes>\s*\)\s*;\s*\}""",
            RegexOption.DOT_MATCHES_ALL,
        )

        /**
         * 역할: 실행 없이 알려진 변환 함수 문법과 리터럴 키·필드 참조만 추출한다.
         * 입력: 중첩 문자열 난독화를 복원한 서비스 페이지.
         * 출력: 기존 페이지는 null, 새 페이지는 복원 정보이며 모호한 표현은 거부한다.
         */
        internal fun parse(source: String): EncodedTextFields? {
            if (!Regex("""\bQnmsbChange\s*\(""").containsMatchIn(source)) return null
            try {
                val matches = decoder.findAll(source).toList()
                require(matches.size == 1)
                require(Regex("""function\s+QnmsbChange\s*\(""").findAll(source).count() == 1)
                val values = matches.single().groups["numbers"]!!.value.split(',').map { it.trim().toInt() }
                require(values.size in 1..256 && values.all { it in 0..255 })
                val starts = Regex("""new\s+Date\(\s*QnmsbChange\(\s*자료\.(자료\d+)\s*\)\s*\)""")
                    .findAll(source).map { it.groupValues[1] }.toSet()
                val times = Regex("""자료\.(자료\d+)\[\s*교시\s*-\s*1\s*\]""")
                    .findAll(source).map { it.groupValues[1] }.toSet()
                require(starts.size == 1 && times.size == 1)
                return EncodedTextFields(values.map { it.toByte() }.toByteArray(), starts.single(), times.single())
            } catch (_: Exception) {
                throw StructureChanged("컴시간 문자열 변환 함수 또는 자료 필드 형식이 변경됐어요.")
            }
        }
    }
}
