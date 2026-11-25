package io.github.mchany.comciganneo.comcigan

import com.sun.net.httpserver.HttpServer
import io.github.mchany.comciganneo.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.LocalDate
import java.util.Base64
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.*

class RequestTokenTransportTest {
    private data class Seen(
        val path: String, val token: String?, val query: String? = null, val method: String = "GET",
        val contentType: String? = null, val body: String = "",
    )
    private data class Reply(val status: Int,val body: String)
    private class Server(date: LocalDate? = null, private val respond: (Seen,Int) -> Reply) : Closeable {
        val seen = Collections.synchronizedList(mutableListOf<Seen>())
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0).apply {
            createContext("/") { exchange ->
                val request = Seen(
                    exchange.requestURI.path, exchange.requestHeaders.getFirst("X-Hour-Token"), exchange.requestURI.rawQuery,
                    exchange.requestMethod, exchange.requestHeaders.getFirst("Content-Type"),
                    exchange.requestBody.use { it.readBytes().decodeToString() },
                )
                seen.add(request)
                val reply = respond(request,seen.size)
                val bytes = reply.body.toByteArray(if (request.path == "/st") charset("MS949") else Charsets.UTF_8)
                exchange.sendResponseHeaders(reply.status,bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            start()
        }
        private val http = OkHttpClient.Builder().callTimeout(5,TimeUnit.SECONDS).build()
        val client = ComciganClient("http://127.0.0.1:${server.address.port}",http) { date ?: schoolToday() }
        override fun close() {
            server.stop(0)
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }
    @Test fun `transmits token on search and both weeks but never on page requests`() = runBlocking {
        val selection = ClassSelection(School(29318,"테스트고","대전"),1,1)
        val date = LocalDate.parse("2026-11-21")
        var searching = true
        Server(date) { request,_ ->
            if (request.path == "/st") Reply(200,TokenFixtures.page())
            else if (request.token != TokenFixtures.TOKEN) Reply(403,"Forbidden")
            else Reply(200, if (searching) TokenFixtures.encrypt(TokenFixtures.SCHOOLS)
                else publishedBody(if (selector(request) == 1) "2026-11-16" else "2026-11-23", null))
        }.use { server ->
            assertEquals(29318,server.client.searchSchools("학교").single().code)
            searching = false
            assertEquals("2026-11-16", server.client.fetch(selection, 0).weekStart)
            assertEquals("2026-11-23", server.client.fetch(selection, 1).weekStart)
            assertEquals(listOf(null,TokenFixtures.TOKEN,TokenFixtures.TOKEN,TokenFixtures.TOKEN,TokenFixtures.TOKEN,TokenFixtures.TOKEN),server.seen.map { it.token })
        }
    }

    @Test fun `refreshes cached token once on rejection without putting it on the page request`() = runBlocking {
        var pages=0
        var searches=0
        Server { request,_ ->
            if (request.path == "/st") { pages++; Reply(200,TokenFixtures.page(if(pages==1) TokenFixtures.TOKEN else TokenFixtures.OTHER_TOKEN)) }
            else { searches++; if(searches==2) Reply(403,"Forbidden") else Reply(200,TokenFixtures.encrypt(TokenFixtures.SCHOOLS)) }
        }.use { server ->
            server.client.searchSchools("학교")
            assertEquals(29318,server.client.searchSchools("학교").single().code)
            assertEquals(2,pages)
            assertEquals(3,searches)
            assertEquals(listOf(null,TokenFixtures.TOKEN,TokenFixtures.TOKEN,null,TokenFixtures.OTHER_TOKEN),server.seen.map { it.token })
        }
    }

    @Test fun `persistent 403 stops after one refresh and invalidates rejected credentials`() = runBlocking {
        Server { request,_ -> if(request.path=="/st") Reply(200,TokenFixtures.page()) else Reply(403,"Forbidden") }.use { server ->
            assertFailsWith<ComciganRequestRejected> { server.client.searchSchools("학교") }
            assertEquals(4,server.seen.size)
            assertEquals(2,server.seen.count { it.path=="/st" })
        }
    }

    @Test fun `429 is surfaced without retrying or requesting another token`() = runBlocking {
        Server { request,_ -> if(request.path=="/st") Reply(200,TokenFixtures.page()) else Reply(429,"Too Many Requests") }.use { server ->
            assertFailsWith<ComciganRateLimited> { server.client.searchSchools("학교") }
            assertEquals(2,server.seen.size)
        }
    }

    @Test fun `500 and page request rejection do not enter a token refresh loop`() = runBlocking {
        Server { request,_ -> if(request.path=="/st") Reply(200,TokenFixtures.page()) else Reply(500,"Error") }.use { server ->
            assertEquals(500, assertFailsWith<ComciganHttpException> { server.client.searchSchools("학교") }.statusCode)
            assertEquals(2,server.seen.size)
        }
        Server { _,_ -> Reply(403,"Forbidden") }.use { server ->
            assertFailsWith<ComciganRequestRejected> { server.client.searchSchools("학교") }
            assertEquals(1,server.seen.size)
            assertNull(server.seen.single().token)
        }
    }

    @Test fun `older pages without a token header still work`() = runBlocking {
        Server { request,_ -> if(request.path=="/st") Reply(200,TokenFixtures.page(null)) else Reply(200,TokenFixtures.encrypt(TokenFixtures.SCHOOLS)) }.use { server ->
            assertEquals(29318,server.client.searchSchools("학교").single().code)
            assertTrue(server.seen.all { it.token==null })
        }
    }

    @Test fun `numeric POST preserves exact bodies and reads published selector seven`() = runBlocking {
        val date = LocalDate.parse("2026-11-09")
        val search = "MzIxbCVFRCU5NSU5OSVFQSVCNSU5MCFfMjYwMA%3D%3D"
        val first = "OTg3NjVfMTIzNF8wXzFfMjYwMA=="
        val second = "OTg3NjVfMTIzNF8wXzdfMjYwMA=="
        Server(date) { request, _ ->
            val json = when (request.body) {
                search -> """{"학교검색":[[12694,"ipPGmw==","ipPGmw==",0,"eldKBQ==",0]]}"""
                first -> numericTimetable("eVVLB2ZUSBx7XA==")
                second -> numericTimetable("eVVLB2ZUSBx6Uw==")
                else -> null
            }
            when {
                request.path == "/st" -> Reply(200, postPage())
                json != null -> Reply(200, TokenFixtures.encrypt(json, "2600"))
                else -> Reply(400, "Unexpected body")
            }
        }.use { server ->
            val school = server.client.searchSchools("학교!").single()
            assertEquals(School(1234, "지역", "지역"), school)
            val snapshot = server.client.fetch(ClassSelection(school, 1, 1), 1)
            assertEquals("2026-11-16", snapshot.weekStart)
            assertEquals("수학", snapshot.slots.single().current?.subject)
            assertEquals(
                listOf(
                    Seen("/st", null),
                    Seen("/765432", TokenFixtures.TOKEN, method = "POST", contentType = "text/plain; charset=UTF-8", body = search),
                    Seen("/765432", TokenFixtures.TOKEN, method = "POST", contentType = "text/plain; charset=UTF-8", body = first),
                    Seen("/765432", TokenFixtures.TOKEN, method = "POST", contentType = "text/plain; charset=UTF-8", body = second),
                ),
                server.seen,
            )
        }
    }

    @Test fun `ambiguous or unsupported POST declarations fail closed`() {
        val page = postPage()
        for (invalid in listOf(
            page.replace("Qa='POST'", "Qa='GET'"),
            page.replace("Qa='POST'", "Qa='POST' + other"),
            page + "\nQa='GET';",
            page.replace("var 안내중 = false,Qa='POST';", "function unrelated(){var Qa='POST';}"),
            page.replace("findSchool7(sc, success)", "findSchool7(sc, Qa)"),
            page.replace("Qa='POST'", "Other='POST'") + "\n// Qa='POST';",
            page.replace("Qa='POST'", "Other='POST'") + "\nvar text=\"Qa='POST';\";",
            page.replaceFirst("method:Qa", "method:Other"),
            page.replaceFirst("method:Qa,", "method:Qa,method:'GET',"),
            page.replace("r+'_2600'", "r+'_2601'"),
            page.replaceFirst("url:'./765432'", "url:'./123456'"),
            page.replace("charset=UTF-8", "charset=MS949"),
            page.replace("btoa(s8+'_'+r+'_2600')", "encodeURIComponent(btoa(s8+'_'+r+'_2600'))"),
            page.replaceFirst("dataType:", "data:btoa('other'),dataType:"),
            page.replaceFirst("headers:{'X-Hour-Token'", "headers:{'Different-Header'"),
            page.replace("url:'./765432',method:Qa,contentType:'text/plain; charset=UTF-8'," +
                "data:btoa(s8+'_'+r+'_2600')",
                "url:'./765432?'+btoa(s8+'_'+r+'_2600')"),
        )) {
            val error = assertFailsWith<StructureChanged> { ComciganParser.serviceCodes(invalid) }
            assertEquals(StructureSource.PROTOCOL, error.source)
        }
    }

    @Test fun `unrelated numeric AJAX does not affect lookup recognition`() {
        val unrelated = "\nfunction ping(){return ${'$'}.ajax({url:'./123',data:{kind:'status'}});}"
        for ((page, method) in listOf(
            TokenFixtures.page() to ComciganRequestMethod.GET,
            postPage() to ComciganRequestMethod.POST,
        )) {
            val codes = ComciganParser.serviceCodes(page + unrelated)
            assertEquals("/765432", codes.endpoint)
            assertEquals(method, codes.requestMethod)
        }
    }

    private fun postPage() = TokenFixtures.page()
        .replace("function QAjaxSchool(sc, success) {return", "function findSchool7(sc, success) {" +
            "var s7='321l'+encodeURIComponent(sc);return")
        .replace("url:'./765432?'+encodeURIComponent(btoa('321l'+encodeURIComponent(sc)+'_enc5'))",
            "url:'./765432',method:Qa,contentType:'text/plain; charset=UTF-8',data:encodeURIComponent(btoa(s7+'_2600'))")
        .replace("url:'./765432?'+btoa(s7+'_'+da1+'_'+r+'_enc5')",
            "url:'./765432',method:Qa,contentType:'text/plain; charset=UTF-8',data:btoa(s8+'_'+r+'_2600')")
        .replace("var s7=sc+sc2;return", "var s7=sc+sc2,s8=s7+'_'+da1;return")
        .replace("QAjaxTimetable", "requestTable8")
        .replace("sc_data", "loadTable1")
        .replace("da1=H시간표.자료13;", "da1=decodeText9(H시간표.자료13);") + """

        var 안내중 = false,Qa='POST';
        function loadTable1(sc,sc2,r,nw) {
            var da1='0';
            requestTable8(sc,sc2,da1,r,function(data) {});
        }
        function decodeText9(value) {
            if(value==undefined || value==='') return '';
            var key=String.fromCharCode(75,101,121,49);
            var raw=atob(value);
            var bytes=new Uint8Array(raw.length);
            for(var i=0;i<bytes.length;i++) {
                bytes[i]=raw.charCodeAt(i)^key.charCodeAt(i%key.length);
            }
            return new TextDecoder('euc-kr',{fatal:true}).decode(bytes);
        }
        var start = new Date(decodeText9(자료.자료901));
        var time = 자료.자료902[교시-1];
    """.trimIndent()

    private fun numericTimetable(start: String) = """{
        "자료901":"$start","자료902":["09:00"],"자료13":"eVVLB2ZUSBx6VFkAeV9KBXFQTw==",
        "학급수":[1,1],"분리":1000,"일자자료":$published,"오늘r":1,
        "자료11":["","+tbC1mE="],"자료12":[0,"95O+4Q=="],
        "자료14":[[],[[],[1,[1,1001]]]],"자료15":[[],[[],[1,[1,1001]]]]
    }"""

    private val selection = ClassSelection(School(29318,"테스트고","대전"),1,1)
    private val published = """[[7,"26-11-16 ~ 26-11-21"],[1,"26-11-09 ~ 26-11-14"]]"""
    private fun publishedBody(start: String, catalog: String? = published) = TokenFixtures.encrypt("""{
        "시작일":"$start","학급수":[1,1],"분리":1000,
        ${catalog?.let { """"일자자료":$it,"오늘r":1,""" }.orEmpty()}
        "자료11":["","교사"],"자료12":["","국어"],
        "자료14":[[],[[],[1,[1,1001]]]],"자료15":[[],[[],[1,[1,1001]]]]
    }""")
    private fun selector(request: Seen): Int {
        val raw = String(Base64.getDecoder().decode(URLDecoder.decode(request.query!!,"UTF-8")),Charsets.UTF_8)
        return Regex("""_(\d+)_enc5$""").find(raw)!!.groupValues[1].toInt()
    }

    @Test fun `Sunday and Monday select actual published dates and nonconsecutive request ids`() = runBlocking {
        for (date in listOf("2026-11-08","2026-11-09")) {
            Server(LocalDate.parse(date)) { request,_ ->
                if (request.path == "/st") Reply(200,TokenFixtures.page())
                else Reply(200,publishedBody(if (selector(request)==7) "2026-11-16" else "2026-11-09"))
            }.use { server ->
                val first = server.client.fetch(selection,0)
                val second = server.client.fetch(selection,1)
                assertEquals("2026-11-09",first.weekStart)
                assertEquals("2026-11-16",second.weekStart)
                assertEquals(listOf("2026-11-09","2026-11-16"),second.publishedWeekStarts(LocalDate.parse(date)))
                assertEquals(listOf(1,1,7),server.seen.filter { it.path!="/st" }.map(::selector))
            }
        }
    }

    @Test fun `wrong period response is retried once and never reported as unpublished`() = runBlocking {
        Server(LocalDate.parse("2026-11-08")) { request,_ ->
            if (request.path=="/st") Reply(200,TokenFixtures.page()) else Reply(200,publishedBody("2026-11-09"))
        }.use { server ->
            assertFailsWith<TimetablePeriodChanged> { server.client.fetch(selection,1) }
            assertEquals(listOf(1,7,1,7),server.seen.filter { it.path!="/st" }.map(::selector))
            assertEquals(2,server.seen.count { it.path=="/st" })
        }
    }

    @Test fun `a genuinely missing second period does not request a fabricated id`() = runBlocking {
        Server(LocalDate.parse("2026-11-08")) { request,_ ->
            if (request.path=="/st") Reply(200,TokenFixtures.page())
            else Reply(200,publishedBody("2026-11-09","""[[1,"26-11-09 ~ 26-11-14"]]"""))
        }.use { server ->
            assertEquals(UnpublishedEvidence.CATALOG_ABSENT, assertFailsWith<TimetableUnavailable> { server.client.fetch(selection,1) }.evidence)
            assertEquals(listOf(1),server.seen.filter { it.path!="/st" }.map(::selector))
        }
    }
    @Test fun `catalog-free Saturday rollout passes client validation and preserves both published dates`() = runBlocking {
        val date = LocalDate.parse("2026-11-21")
        Server(date) { request, _ ->
            if (request.path == "/st") Reply(200, TokenFixtures.page())
            else Reply(200, publishedBody(if (selector(request) == 1) "2026-11-23" else "2026-11-30", null))
        }.use { server ->
            val first = server.client.fetch(selection, 0).copy(fetchedAt = 1)
            val second = server.client.fetch(selection, 1).copy(fetchedAt = 2)
            assertEquals("2026-11-23", first.weekStart)
            assertEquals("2026-11-30", second.weekStart)
            assertEquals(listOf(1, 2, 1, 2), server.seen.filter { it.path != "/st" }.map(::selector))
            requirePublishedPeriod(first, 0, date)
            requirePublishedPeriod(second, 1, date)
            assertEquals(first.weekStart, cachedTimetableSnapshot(listOf(first, second), selection, 0, date)?.weekStart)
            assertEquals(second.weekStart, cachedTimetableSnapshot(listOf(first, second), selection, 1, date)?.weekStart)
        }
    }

    @Test fun `catalog-free periods must advance to a later Monday and retry at most once`() = runBlocking {
        for (second in listOf("2026-11-23", "2026-11-16", "2026-11-24")) {
            Server(LocalDate.parse("2026-11-21")) { request, _ ->
                if (request.path == "/st") Reply(200, TokenFixtures.page())
                else Reply(200, publishedBody(if (selector(request) == 1) "2026-11-23" else second, null))
            }.use { server ->
                assertFailsWith<TimetablePeriodChanged> { server.client.fetch(selection, 1) }
                assertEquals(listOf(1, 2, 1, 2), server.seen.filter { it.path != "/st" }.map(::selector))
            }
        }
    }

    @Test fun `a second response without a catalog uses the initial catalog mapping`() = runBlocking {
        val date = LocalDate.parse("2026-11-08")
        Server(date) { request, _ ->
            when {
                request.path == "/st" -> Reply(200, TokenFixtures.page())
                selector(request) == 1 -> Reply(200, publishedBody("2026-11-09"))
                else -> Reply(200, publishedBody("2026-11-16", null))
            }
        }.use { server ->
            val second = server.client.fetch(selection, 1)
            assertEquals("2026-11-16", second.weekStart)
            assertEquals(listOf(1, 7), server.seen.filter { it.path != "/st" }.map(::selector))
            requirePublishedPeriod(second, 1, date)
        }
    }
    @Test fun `refreshing the first period retains a confirmed second period in cache`() = runBlocking {
        val date = LocalDate.parse("2026-11-21")
        Server(date) { request, _ ->
            if (request.path == "/st") Reply(200, TokenFixtures.page())
            else Reply(200, publishedBody(if (selector(request) == 1) "2026-11-23" else "2026-11-30", null))
        }.use { server ->
            val second = server.client.fetch(selection, 1).copy(fetchedAt = 1)
            val newerFirst = server.client.fetch(selection, 0).copy(fetchedAt = 2)
            val cached = cachedTimetableSnapshot(listOf(second, newerFirst), selection, 1, date)
            assertEquals("2026-11-30", cached?.weekStart)
        }
    }

    @Test fun `an optional second period cannot block the first period or retry a 429`() = runBlocking {
        val date = LocalDate.parse("2026-11-21")
        Server(date) { request, _ ->
            when {
                request.path == "/st" -> Reply(200, TokenFixtures.page())
                selector(request) == 1 -> Reply(200, publishedBody("2026-11-23", null))
                else -> Reply(429, "Too Many Requests")
            }
        }.use { server ->
            val first = server.client.fetch(selection, 0)
            assertEquals("2026-11-23", first.weekStart)
            assertEquals(listOf("2026-11-23"), first.publishedWeekStarts(date))
            assertFailsWith<ComciganRateLimited> { server.client.fetch(selection, 1) }
            assertEquals(listOf(1, 2, 1, 2), server.seen.filter { it.path != "/st" }.map(::selector))
            assertEquals(1, server.seen.count { it.path == "/st" })
        }
    }
}
