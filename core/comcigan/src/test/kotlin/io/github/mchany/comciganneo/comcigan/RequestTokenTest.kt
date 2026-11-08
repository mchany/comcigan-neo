package io.github.mchany.comciganneo.comcigan

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import kotlin.test.*

internal object TokenFixtures {
    const val TOKEN = "AB12AB12AB12AB12AB12AB12AB12AB12AB12AB12AB12AB12AB12AB12AB12AB12"
    const val OTHER_TOKEN = "CD34CD34CD34CD34CD34CD34CD34CD34CD34CD34CD34CD34CD34CD34CD34CD34"
    private val key = ByteArray(32) { it.toByte() }
    private val header = "headers:{'X-Hour-Token':window.__HourRequestToken || ''},"
    fun page(token: String? = TOKEN, schoolHeader: Boolean = token != null, timetableHeader: Boolean = token != null): String = """
        ${token?.let { "window.__HourRequestToken='$it';" }.orEmpty()}
        function QAjaxSchool(sc, success) {return ${'$'}.ajax({
            url:'./765432?'+encodeURIComponent(btoa('321l'+encodeURIComponent(sc)+'_enc5')),
            ${if (schoolHeader) header else ""}dataType:'text',success:success});}
        function QAjaxTimetable(sc,sc2,da1,r,success) {var s7=sc+sc2;return ${'$'}.ajax({
            url:'./765432?'+btoa(s7+'_'+da1+'_'+r+'_enc5'),
            ${if (timetableHeader) header else ""}dataType:'text',success:success});}
        sc_data('98765_',sc,1,'0');
        성명=Q성명(자료.자료11[th]); 과목명=Q과목명(자료.자료12[sb]);
        da1=H시간표.자료13;
        일일자료=Q자료(자료.자료14[g][c][d][p]); 원자료=Q자료(자료.자료15[g][c][d][p]);
        var r=Math.floor(학교명단[i][0]/6347)+2;
        var key=CryptoJS.enc.Hex.parse('000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f');
        CryptoJS.AES.decrypt({ciphertext:binary[2]},key);
    """.trimIndent()
    fun encrypt(json: String): String {
        // Transport fixtures only; crypto correctness is covered by independent fixed .NET vectors.
        val iv = ByteArray(16) { it.toByte() }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),IvParameterSpec(iv))
        }
        val b64 = Base64.getEncoder()
        val body = "enc5:" + b64.encodeToString(iv) + ":" + b64.encodeToString(cipher.doFinal(json.toByteArray(Charsets.UTF_8)))
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(key),"HmacSHA256"))
        }
        return body + ":" + b64.encodeToString(mac.doFinal(body.toByteArray(Charsets.UTF_8)))
    }
    const val SCHOOLS = """{"학교검색":[[12694,"대전","테스트고",99999,29318,88888],[0,"알림","없으면 추가 검색하세요",0]]}"""
}

class RequestTokenTest {
    @Test fun `extracts page token without exposing it in codes copy or token strings`() {
        val codes = ComciganParser.serviceCodes(TokenFixtures.page())
        assertEquals(TokenFixtures.TOKEN,codes.requestToken?.headerValue())
        for (text in listOf(codes.toString(),codes.copy().toString(),codes.requestToken.toString())) {
            assertFalse(text.contains(TokenFixtures.TOKEN))
        }
        assertNull(ComciganParser.serviceCodes(TokenFixtures.page(null)).requestToken)
    }

    @Test fun `rejects missing nonliteral invalid or ambiguous token without leaking values`() {
        val original = TokenFixtures.page()
        val declaration = "window.__HourRequestToken='${TokenFixtures.TOKEN}';"
        val invalid = listOf(
            original.replace(declaration,""),
            original.replace(declaration,"window.__HourRequestToken=42;"),
            original.replace(TokenFixtures.TOKEN,"not-hex"),
            original.replace(declaration,"window.__HourRequestToken='AB' + evil();"),
            original + "window.__HourRequestToken='${TokenFixtures.OTHER_TOKEN}';",
            original.replace(TokenFixtures.TOKEN,"AB".repeat(2049)),
        )
        for (page in invalid) {
            val e = assertFailsWith<StructureChanged> { ComciganParser.serviceCodes(page) }
            assertFalse(e.toString().contains(TokenFixtures.TOKEN))
        }
    }

    @Test fun `requires matching token header references in both request functions`() {
        for ((school,timetable) in listOf(true to false,false to true)) {
            val page = TokenFixtures.page(schoolHeader=school,timetableHeader=timetable) +
                "function unrelated(){return {'X-Hour-Token':window.__HourRequestToken || ''};}"
            assertFailsWith<StructureChanged> { ComciganParser.serviceCodes(page) }
        }
        val bad = TokenFixtures.page().replace("window.__HourRequestToken || ''","window.__HourRequestToken || '' + evil()")
        assertFailsWith<StructureChanged> { ComciganParser.serviceCodes(bad) }
        val duplicate = TokenFixtures.page().replace("headers:{", "headers:{'X-Hour-Token':'BAD',")
        assertFailsWith<StructureChanged> { ComciganParser.serviceCodes(duplicate) }
    }

    @Test fun `filters encrypted footer before computing the code column`() {
        val codes = ComciganParser.serviceCodes(TokenFixtures.page())
        val rows = ComciganParser.schools(TokenFixtures.encrypt(TokenFixtures.SCHOOLS),codes)
        assertEquals(listOf(29318),rows.map { it.code })
        assertEquals("테스트고",rows.single().name)
        assertTrue(ComciganParser.schools(TokenFixtures.encrypt("""{"학교검색":[[0,"알림","없으면 추가 검색하세요",0]]}"""),codes).isEmpty())
        assertTrue(ComciganParser.schools(TokenFixtures.encrypt("""{"학교검색":[]}"""),codes).isEmpty())
    }

    @Test fun `retains legacy footer compatibility and rejects malformed real rows`() {
        assertEquals(listOf(10),ComciganParser.schools("""{"학교검색":[[1,"서울","가고",10],[0,"알림","없으면 추가 검색하세요",0]]}""").map { it.code })
        val codes = ComciganParser.serviceCodes(TokenFixtures.page())
        assertFailsWith<StructureChanged> {
            ComciganParser.schools(TokenFixtures.encrypt("""{"학교검색":[[12694,"대전","잘못된 행",0]]}"""),codes)
        }
    }
}
