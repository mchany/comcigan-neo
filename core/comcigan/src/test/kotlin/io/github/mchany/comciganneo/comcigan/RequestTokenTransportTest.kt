package io.github.mchany.comciganneo.comcigan

import com.sun.net.httpserver.HttpServer
import io.github.mchany.comciganneo.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.*

class RequestTokenTransportTest {
    private data class Seen(val path: String,val token: String?)
    private data class Reply(val status: Int,val body: String)
    private class Server(private val respond: (Seen,Int) -> Reply) : Closeable {
        val seen = Collections.synchronizedList(mutableListOf<Seen>())
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0).apply {
            createContext("/") { exchange ->
                val request = Seen(exchange.requestURI.path,exchange.requestHeaders.getFirst("X-Hour-Token"))
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
        val client = ComciganClient("http://127.0.0.1:${server.address.port}",http)
        override fun close() {
            server.stop(0)
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }
    private fun timetable(week: Int): String = TokenFixtures.encrypt("""{
        "시작일":"${weekStart(offset=week)}","학급수":[1,1],"분리":1000,
        "자료11":["","교사"],"자료12":["","국어"],
        "자료14":[[],[[],[1,[1,1001]]]],"자료15":[[],[[],[1,[1,1001]]]]
    }""")

    @Test fun `transmits token on search and both weeks but never on page requests`() = runBlocking {
        val selection = ClassSelection(School(29318,"테스트고","대전"),1,1)
        var week = 0
        Server { request,_ ->
            if (request.path == "/st") Reply(200,TokenFixtures.page())
            else if (request.token != TokenFixtures.TOKEN) Reply(403,"Forbidden")
            else Reply(200,if (week == 0) TokenFixtures.encrypt(TokenFixtures.SCHOOLS) else timetable(week-1))
        }.use { server ->
            assertEquals(29318,server.client.searchSchools("학교").single().code)
            week=1
            assertEquals(weekStart().toString(),server.client.fetch(selection,0).weekStart)
            week=2
            assertEquals(weekStart(offset=1).toString(),server.client.fetch(selection,1).weekStart)
            assertEquals(listOf(null,TokenFixtures.TOKEN,TokenFixtures.TOKEN,TokenFixtures.TOKEN),server.seen.map { it.token })
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
            assertFailsWith<IOException> { server.client.searchSchools("학교") }
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
}
