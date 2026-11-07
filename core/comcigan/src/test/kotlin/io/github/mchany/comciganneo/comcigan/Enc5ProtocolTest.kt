package io.github.mchany.comciganneo.comcigan

import io.github.mchany.comciganneo.model.ClassSelection
import io.github.mchany.comciganneo.model.School
import kotlinx.serialization.json.*
import java.net.URLDecoder
import java.util.Base64
import kotlin.test.*

class Enc5ProtocolTest {
    private val key = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    private val ajax = """
        function QAjaxSchool(sc, success) {
            return ${'$'}.ajax({url:'./765432?'+encodeURIComponent(btoa('321l'+encodeURIComponent(sc)+'_enc5')),
                dataType:'text',success:success});}
        function QAjaxTimetable(sc,sc2,da1,r,success) {
            var s7=sc+sc2;return ${'$'}.ajax({url:'./765432?'+btoa(s7+'_'+da1+'_'+r+'_enc5'),
                dataType:'text',success:success});}
    """.trimIndent()
    private val crypto = """
        var key=CryptoJS.enc.Hex.parse((function(a){return a[2]+a[1]+a[3]+a[0];})([
          '18191a1b1c1d1e1f','08090a0b0c0d0e0f','0001020304050607','1011121314151617']));
        CryptoJS.AES.decrypt({ciphertext:binary[2]},key);
    """.trimIndent()
    private val fields = """
        sc_data('98765_',sc,1,'0');
        성명=Q성명(자료.자료11[th]); 과목명=Q과목명(자료.자료12[sb]);
        da1=H시간표.자료13;
        일일자료=Q자료(자료.자료14[g][c][d][p]); 원자료=Q자료(자료.자료15[g][c][d][p]);
        var r=Math.floor(학교명단[i][0]/6347)+2;
    """.trimIndent()
    private fun wrap(source: String, marker: String, key: Int): String {
        val encoded = source.mapIndexed { index, char -> "%04X".format(char.code xor ((key + index * 13) and 255)) }.joinToString("")
        val payload = buildJsonArray {
            add(buildJsonArray { add(1); add("/* literal chunk */") })
            add(buildJsonArray { add(0); add(encoded) })
        }
        return "/* $marker */(function(a,k){var s='';})($payload,$key);"
    }
    private fun page(): String = wrap(fields +
        wrap(ajax,"hour-ajax-obfuscated-v1",34) +
        wrap(crypto,"hour-ajax-obfuscated-v1",173), "hour-obfuscated-v2",83)
    private val codes get() = ComciganParser.serviceCodes(page())

    // Fixed AES/HMAC fixtures generated independently with .NET, using a synthetic key and data.
    private val schools = "enc5:AAECAwQFBgcICQoLDA0ODw==:WI1mDFh1S2qdu4VPaq69z1PpTehtf5IEw+vnTv3KZn1CN8rjWQFarf/h1fqJy4yxkuHClFdfCvyoK/WD8G3lPILmgL6Cu/7VEBGZPp4C0GPUl84elcR4x0RTs2nkfsAOO3N78O3IPjAAoCixhMAocBy4YtiV705Uy0se8dzUdK0=:Q3Woxe5UKn81dOpFk49zBJDlJJTuT+sz5NwA+M6TiHs="
    private val timetable = "enc5:AAECAwQFBgcICQoLDA0ODw==:z9cFOxrBULPD1mqVSnvFpDdC9GK5B8+ZtNUQa3MO1shqXe9Dmh0VdkK+L8QwK92PQipkBy7Q3nHeyhv/72m56IUH/X470rh+zNkLivG54ytwlJcR852z0CwMBCVaB/fLswa7/fvFjaeueVEi0KZQ/c1Cq5j7j8m0krEbhGZzxUyAALx/R5UTEzsWtn6eyBBbdX6IhsrvP/sq7JtABzq10pld01wqC7pwioLGSa1uJh4tcGXVUuftJr9sE6EoUunPkU1D5AjvUETpQns+ePuAG1a+JMadfiGvmMuGcDpfUjI=:h3WDXuXk+IqNlKaPAXb8VVDvcTKYxc6TonkQsCSCIkQ="

    @Test fun `unwraps both ajax blocks and reconstructs the dynamic key`() {
        assertEquals(key,codes.responseKeyHex)
        assertEquals("/765432?321l",codes.searchRoute)
        assertEquals("enc5",codes.responseFormat)
        assertTrue(codes.encodedSchoolSearch)
        assertEquals("자료14",codes.currentField)
        val changed = ComciganParser.serviceCodes(fields + ajax + crypto.replace("a[2]+a[1]+a[3]+a[0]","a[0]+a[3]+a[1]+a[2]"))
        assertEquals("18191a1b1c1d1e1f101112131415161708090a0b0c0d0e0f0001020304050607",changed.responseKeyHex)
    }

    @Test fun `school request uses UTF8 URI encoding inside Base64 and URI escapes the result`() {
        assertEquals("/765432?MzIxbCVFRCU5NSU5OSVFQSVCNSU5MF9lbmM1",codes.searchPath("학교"))
        val path = codes.searchPath("학교 (A+B)!~*'")
        val token = String(Base64.getDecoder().decode(URLDecoder.decode(path.substringAfter('?'),"UTF-8")),Charsets.UTF_8)
        assertEquals("321l%ED%95%99%EA%B5%90%20(A%2BB)!~*'_enc5",token)
        assertTrue(codes.searchPath("ab").endsWith("%3D"))
        for (week in 0..1) {
            val request = String(Base64.getDecoder().decode(codes.timetablePath(29318,week).substringAfter('?')))
            assertEquals("98765_29318_0_${week + 1}_enc5",request)
        }
    }

    @Test fun `authenticates enc5 and parses search plus timetable`() {
        assertEquals(listOf(29318,20449),ComciganParser.schools(schools,codes).map { it.code })
        val selection = ClassSelection(School(29318,"테스트고","대전"),1,1)
        val snapshot = ComciganParser.timetable(timetable,codes,selection,42)
        assertEquals("국어",snapshot.slots.single().current?.subject)
        assertEquals("2026-11-02",snapshot.weekStart)
    }

    @Test fun `does not rewrite authenticated prefix and rejects altered enc5 data`() {
        val changedPrefix = schools.replaceFirst("enc5:","enc2:")
        val error = assertFailsWith<StructureChanged> { ComciganParser.schools(changedPrefix,codes) }
        assertTrue(error.message.orEmpty().contains("무결성"))
        for (body in listOf(schools.replace("WI1m","XI1m"),schools.replaceFirst("enc5:","enc6:"),schools.substringBeforeLast(':'),"{}")) {
            assertFailsWith<StructureChanged> { ComciganParser.schools(body,codes) }
        }
    }

    @Test fun `fails closed on malformed key expressions and unexpected helper routes`() {
        for (bad in listOf(crypto.replace("a[2]","a[20]"),crypto.replace("a[2]","evil()"),crypto.replace("18191a1b1c1d1e1f","18"))) {
            assertFailsWith<StructureChanged> { ComciganParser.serviceCodes(fields + ajax + bad) }
        }
        assertFailsWith<StructureChanged> { ComciganParser.serviceCodes(fields + ajax.replace("btoa(s7", "btoa2(s7").replace("_enc5","_enc6") + crypto) }
        val mismatched = ajax.replace("url:'./765432?'+btoa(s7","url:'./999999?'+btoa(s7")
        assertFailsWith<StructureChanged> { ComciganParser.serviceCodes(fields + mismatched + crypto) }
    }

    @Test fun `rejects unsupported script shapes and excessive nesting`() {
        assertFailsWith<StructureChanged> { ComciganParser.serviceCodes("/* hour-ajax-obfuscated-v1 */broken") }
        var deep = "x"
        repeat(5) { deep = wrap(deep,"hour-obfuscated-v2",83) }
        val error = assertFailsWith<StructureChanged> { ComciganParser.serviceCodes(deep) }
        assertTrue(error.message.orEmpty().contains("중첩"))
    }
}
