package io.github.mchany.comciganneo.comcigan

import kotlin.test.*
import kotlinx.serialization.json.*
import io.github.mchany.comciganneo.model.*

class EncodedFieldsTest {
    private val decoder = """
        function QnmsbChange(value) {
            if(value==undefined || value==='') return '';
            var key=String.fromCharCode(75,101,121,49);
            var raw=atob(value);
            var bytes=new Uint8Array(raw.length);
            for(var i=0;i<bytes.length;i++) {
                bytes[i]=raw.charCodeAt(i)^key.charCodeAt(i%key.length);
            }
            return new TextDecoder('euc-kr',{fatal:true}).decode(bytes);
        }
        var start=new Date(QnmsbChange(자료.자료901));
        var time=자료.자료902[교시-1];
    """.trimIndent()
    /**
     * 역할: 운영 키를 포함하지 않는 새 필드 인코딩 페이지를 만든다.
     * 입력: 없음.
     * 출력: 고정된 합성 서비스 페이지.
     */
    private fun page() = TokenFixtures.page().replace("da1=H시간표.자료13;","da1=QnmsbChange(H시간표.자료13);") + decoder

    /**
     * 역할: 새 래퍼가 있는 서비스 페이지와 인코딩된 학교 코드의 호환을 검증한다.
     * 입력: 합성 서비스 페이지와 독립적인 고정 CP949 문자열 벡터.
     * 출력: 학교명·지역명·학교 코드가 복원되면 통과한다.
     */
    @Test fun encodedSchoolAndFooter() {
        val codes = ComciganParser.serviceCodes(page())
        val json = """{"학교검색":[[12694,"ipPGmw==","ipPGmw==","ignored-decoy","eldK",0],[0,"알림","없으면 추가 검색하세요","ew=="]]}"""
        val result = ComciganParser.schools(TokenFixtures.encrypt(json),codes).single()
        assertEquals("지역",result.name)
        assertEquals("지역",result.region)
        assertEquals(123,result.code)
    }

    /**
     * 역할: 독립 생성 벡터로 CP949 확장 문자와 잘못된 인코딩 거부를 검증한다.
     * 입력: Node.js·iconv 기반 pycomcigan PR 18의 합성 문자열 벡터.
     * 출력: 정상 문자열은 정확히 복원되고 비정상 값은 형식 오류다.
     */
    @Test fun fixedVectorsAndStrictDecoding() {
        val fields = ComciganParser.serviceCodes(page()).textFields!!
        for ((encoded,plain) in listOf("34s=" to "뷁","+tbC1mE=" to "교사*","95O+4Q==" to "수학","eVVLB2ZUSBx7XA==" to "2026-11-09")) {
            assertEquals(plain,fields.decode(JsonPrimitive(encoded)))
        }
        for (bad in listOf(JsonPrimitive("!"),JsonPrimitive("AB=="),JsonPrimitive("yg=="),JsonPrimitive("95O+4Q==\n"),JsonPrimitive(0),buildJsonObject {})) {
            val error = assertFailsWith<StructureChanged> { fields.decode(bad) }
            assertNull(error.cause)
        }
        assertFalse(ComciganParser.serviceCodes(page()).toString().contains("75,101,121,49"))
    }

    /**
     * 역할: 변환 함수 또는 필드 참조가 알려진 문법에서 벗어나면 거부한다.
     * 입력: 연산·인코딩·키·필드 참조를 변조한 합성 페이지.
     * 출력: 모든 변조가 형식 오류로 중단된다.
     */
    @Test fun rejectsUnknownGrammarAndAmbiguousFields() {
        val source = page()
        for ((old,new) in listOf("fatal:true" to "fatal:false","'euc-kr'" to "'utf-8'","^key.charCodeAt" to "+key.charCodeAt","75,101,121,49" to "256","75,101,121,49" to "evil()","자료901" to "missing")) {
            assertFailsWith<StructureChanged> { ComciganParser.serviceCodes(source.replace(old,new)) }
        }
        assertFailsWith<StructureChanged> { ComciganParser.serviceCodes(source+decoder) }
        assertFailsWith<StructureChanged> { ComciganParser.serviceCodes(source.replace("75,101,121,49",List(257){"1"}.joinToString(","))) }
        assertFailsWith<StructureChanged> { ComciganParser.serviceCodes(source+"new Date(QnmsbChange(자료.자료777));") }
        val changed = source.replace("자료901","자료777").replace("자료902","자료778").replace("75,101,121,49","51,52,53")
        assertNotNull(ComciganParser.serviceCodes(changed).textFields)
        val korean = source.replace("value","암호문").replace("var key=String","var 키=String")
            .replace("key.charCodeAt","키.charCodeAt").replace("key.length","키.length")
            .replace("raw","바이트문자열").replace("bytes","바이트")
        assertNotNull(ComciganParser.serviceCodes(korean).textFields)
    }

    /**
     * 역할: 인증된 시간표의 날짜·수업명·숫자 표식과 일과시간을 기존 모델로 복원한다.
     * 입력: 합성 AES 응답과 독립 고정 문자열 벡터.
     * 출력: 기존 시간표 모델에 날짜·수업·시작 시간이 채워지고 입력 JSON은 보존된다.
     */
    @Test fun restoresTimetableAndPreservesInput() {
        val codes = ComciganParser.serviceCodes(page())
        val data = """{"자료901":"eVVLB2ZUSBx7XA==","자료902":["09:00"],"자료13":"eVVLB2ZUSBx6VFkAeV9KBXFQTw==",
            "학급수":[1,1],"분리":1000,"자료11":["","+tbC1mE="],"자료12":[0,"95O+4Q=="],
            "자료14":[[],[[],[1,[1,1001]]]],"자료15":[[],[[],[1,[1,1001]]]]}"""
        val json = Json.parseToJsonElement(data).jsonObject
        val before = json.toString()
        val restored = codes.textFields!!.normalize(json,codes)
        assertEquals(before,json.toString())
        assertEquals("",restored.getValue("자료12").jsonArray[0].jsonPrimitive.content)
        val snapshot = ComciganParser.timetable(TokenFixtures.encrypt(data),codes,ClassSelection(School(123,"지역","지역"),1,1),1)
        assertEquals("2026-11-09",snapshot.weekStart)
        assertEquals("수학",snapshot.slots.single().current?.subject)
        assertEquals("교사*",snapshot.slots.single().current?.teacher)
        assertEquals("09:00",snapshot.slots.single().startTime)
        assertFailsWith<StructureChanged> { ComciganParser.timetable(TokenFixtures.encrypt("{}"),codes,snapshot.selection,1) }
        val encrypted = TokenFixtures.encrypt("{}")
        val head = encrypted.substringBeforeLast(':')
        val tag = encrypted.substringAfterLast(':')
        val tampered = head+":"+(if(tag[0]=='A') "B" else "A")+tag.drop(1)
        val error = assertFailsWith<StructureChanged> { ComciganParser.timetable(tampered,codes,snapshot.selection,1) }
        assertTrue(error.message!!.contains("무결성"))
    }
}
