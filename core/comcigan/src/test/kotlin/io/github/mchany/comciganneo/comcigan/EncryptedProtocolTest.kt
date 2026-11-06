package io.github.mchany.comciganneo.comcigan

import io.github.mchany.comciganneo.model.ClassSelection
import io.github.mchany.comciganneo.model.School
import java.util.Base64
import kotlin.test.*

class EncryptedProtocolTest {
    private val key = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    private fun page(divisor: Int = 6347) = """
        url:'./1234567?9876l'+sc+'_enc2';
        sc_data('98765_',sc,1,'0');
        성명=Q성명(자료.자료11[th]);
        과목명=Q과목명(자료.자료12[sb]);
        da1=H시간표.자료13;
        일일자료=Q자료(자료.자료14[g][c][d][p]);
        원자료=Q자료(자료.자료15[g][c][d][p]);
        var r=Math.floor(학교명단[i][0]/$divisor)+2;
        var key=CryptoJS.enc.Hex.parse('$key');
        CryptoJS.AES.decrypt({ciphertext:binary[2]},key);
    """.trimIndent()
    private val codes get() = ComciganParser.serviceCodes(page())

    // Frozen vectors generated independently with .NET AES-CBC/PKCS7 and HMACSHA256.
    private val schools = "enc2:AAECAwQFBgcICQoLDA0ODw==:WI1mDFh1S2qdu4VPaq69z1PpTehtf5IEw+vnTv3KZn1CN8rjWQFarf/h1fqJy4yxkuHClFdfCvyoK/WD8G3lPILmgL6Cu/7VEBGZPp4C0GPUl84elcR4x0RTs2nkfsAOO3N78O3IPjAAoCixhMAocBy4YtiV705Uy0se8dzUdK0=:q0mkkNfFyZ5gj2ha4Ju7/n7I6wUwL5xxrMmXugafXwc="
    private val legacyEncoding = "enc1:000102030405060708090a0b0c0d0e0f:588d660c58754b6a9dbb854f6aaebdcf53e94de86d7f9204c3ebe74efdca667d4237cae359015aadffe1d5fa89cb8cb192e1c294575f0afca82bf583f06de53c82e680be82bbfed51011993e9e02d063d497ce1e95c478c74453b369e47ec00e3b737bf0edc83e3000a028b184c028701cb862d895ef4e54cb4b1ef1dcd474ad:d1d9520d8c1bd73e592eb5f9b8d110c479469b6d2281c776080594407c87a7cc"
    private val timetable = "enc2:AAECAwQFBgcICQoLDA0ODw==:z9cFOxrBULPD1mqVSnvFpDdC9GK5B8+ZtNUQa3MO1shqXe9Dmh0VdkK+L8QwK92PQipkBy7Q3nHeyhv/72m56IUH/X470rh+zNkLivG54ytwlJcR852z0CwMBCVaB/fLswa7/fvFjaeueVEi0KZQ/c1Cq5j7j8m0krEbhGZzxUzFohKzp9x0H5SSCadLswqcQF/2fJ3LoNTEtq/vzboUQvdKkGX8nXP6ZVV9Y4WKz/lBDRwO02aC93MnDlQFcU0akCykqPGtfztVGL+DUd2NKYqWHlelJaUrHAB5aLh8B3c=:5pvPWxoEtoyHq73SScdGxOO8lCkslt/j4TiEUFSCddI="

    @Test fun `extracts live protocol parameters and encodes both request types`() {
        assertEquals(key, codes.responseKeyHex)
        assertEquals(6347, codes.schoolCodeDivisor)
        assertEquals(9000, ComciganParser.serviceCodes(page(9000)).schoolCodeDivisor)
        assertEquals("/1234567?9876l%C7%D0%B1%B3_enc2", codes.searchPath("학교"))
        for (week in 0..1) {
            val token = String(Base64.getDecoder().decode(codes.timetablePath(29318, week).substringAfter('?')))
            assertEquals("98765_29318_0_${week + 1}_enc2", token)
        }
        assertFailsWith<StructureChanged> { ComciganParser.serviceCodes(page().replace(key, "bad")) }
        assertFailsWith<StructureChanged> { ComciganParser.serviceCodes(page(0)) }
    }

    @Test fun `authenticates decrypts and selects different code columns instead of decoys`() {
        for (body in listOf(schools, legacyEncoding)) {
            val result = ComciganParser.schools(body + "\u0000\r\n", codes)
            assertEquals(listOf(29318, 20449), result.map { it.code })
            assertEquals(listOf("테스트고", "동명이교"), result.map { it.name })
        }
    }

    @Test fun `decrypts timetable before parsing lessons`() {
        val selection = ClassSelection(School(29318, "테스트고", "대전"), 1, 1)
        val snapshot = ComciganParser.timetable(timetable, codes, selection, 42)
        assertEquals("2026-11-02", snapshot.weekStart)
        assertEquals("국어", snapshot.slots.single().current?.subject)
        assertEquals("교사", snapshot.slots.single().current?.teacher)
        assertEquals(42L, snapshot.fetchedAt)
        // An unencrypted server rejection must not be mistaken for an unpublished week.
        assertFailsWith<StructureChanged> { ComciganParser.timetable("{}", codes, selection, 42) }
    }

    @Test fun `rejects tampering wrong keys noncanonical encoding and truncated data`() {
        val parts = schools.split(':')
        val corruptions = listOf(
            schools.replace("WI1m", "XI1m"),
            schools.replace("AAECA", "BAECA"),
            schools.replace("q0mkk", "r0mkk"),
            "enc2:${parts[1].trimEnd('=')}:${parts[2]}:${parts[3]}",
            "enc2:${parts[1]}:AA==:${parts[3]}",
            schools.substringBeforeLast(':'),
        )
        for (body in corruptions) assertFailsWith<StructureChanged> { ComciganParser.schools(body, codes) }
        assertFailsWith<StructureChanged> { ComciganParser.schools(schools, codes.copy(responseKeyHex = "00".repeat(32))) }
        assertFailsWith<StructureChanged> { ComciganParser.schools(schools) }
        assertFailsWith<StructureChanged> { ComciganParser.schools("{}", codes) }
    }
}
