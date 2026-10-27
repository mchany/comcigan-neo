package io.github.mchany.comciganneo.neis

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class NeisTransportTest {
    @Test fun readsUtf8AndDoesNotExposeRequestKeysOnHttpFailure() = runBlocking<Unit> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ok") { exchange ->
            val bytes = "급식 응답".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/failure") { exchange ->
            exchange.sendResponseHeaders(503, -1); exchange.close()
        }
        server.start()
        try {
            val transport = OkHttpNeisTransport()
            val base = "http://127.0.0.1:${server.address.port}"
            assertEquals("급식 응답", transport.get("$base/ok".toHttpUrl()))
            val error = assertFailsWith<IOException> { transport.get("$base/failure?KEY=private-test-key".toHttpUrl()) }
            assertFalse(error.stackTraceToString().contains("private-test-key"))
        } finally { server.stop(0) }
    }

    @Test fun cancellationInterruptsResponseBodyDownload() = runBlocking<Unit> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        server.createContext("/slow") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write(1)
                exchange.responseBody.flush()
                started.complete(Unit)
                release.await(5, TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        server.start()
        try {
            val request = async { OkHttpNeisTransport().get("http://127.0.0.1:${server.address.port}/slow".toHttpUrl()) }
            withTimeout(5_000) { started.await() }
            withTimeout(2_000) { request.cancelAndJoin() }
            assertTrue(request.isCancelled)
        } finally { release.countDown(); server.stop(0) }
    }
}
