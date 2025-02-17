package org.http4k.server

import org.apache.hc.core5.http.ClassicHttpRequest
import org.apache.hc.core5.http.ClassicHttpResponse
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.EndpointDetails
import org.apache.hc.core5.http.Header
import org.apache.hc.core5.http.HttpEntityContainer
import org.apache.hc.core5.http.impl.bootstrap.HttpServer
import org.apache.hc.core5.http.impl.bootstrap.ServerBootstrap
import org.apache.hc.core5.http.io.HttpRequestHandler
import org.apache.hc.core5.http.io.SocketConfig
import org.apache.hc.core5.http.io.entity.EmptyInputStream.INSTANCE
import org.apache.hc.core5.http.io.entity.InputStreamEntity
import org.apache.hc.core5.http.protocol.HttpContext
import org.apache.hc.core5.http.protocol.HttpCoreContext
import org.apache.hc.core5.io.CloseMode.IMMEDIATE
import org.apache.hc.core5.util.TimeValue
import org.http4k.core.Headers
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.RequestSource
import org.http4k.core.Response
import org.http4k.core.safeLong
import org.http4k.core.then
import org.http4k.filter.ServerFilters
import org.http4k.lens.Header.CONTENT_TYPE
import org.http4k.server.ServerConfig.StopMode
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import org.apache.hc.core5.http.HttpRequest as ApacheRequest
import org.apache.hc.core5.http.HttpResponse as ApacheResponse

/**
 * Exposed to allow for insertion into a customised Apache WebServer instance
 */
class Http4kRequestHandler(handler: HttpHandler) : HttpRequestHandler {

    private val safeHandler = ServerFilters.CatchAll().then(handler)

    override fun handle(request: ClassicHttpRequest, response: ClassicHttpResponse, context: HttpContext) {
        safeHandler(request.asHttp4kRequest(context)).into(response)
    }

    private fun ApacheRequest.asHttp4kRequest(context: HttpContext): Request {
        val connection = context.getAttribute(HttpCoreContext.CONNECTION_ENDPOINT) as EndpointDetails
        return Request(Method.valueOf(method), uri.httpUri())
            .headers(headers.toHttp4kHeaders()).let {
                when (this) {
                    is HttpEntityContainer -> entity?.let { httpEntity ->
                        it.body(
                            httpEntity.content,
                            getFirstHeader("Content-Length")?.value.safeLong()
                        )
                    } ?: it
                    else -> it.body(INSTANCE, 0)
                }
            }
            .source((connection.remoteAddress as InetSocketAddress).let {
                RequestSource(
                    it.hostString,
                    it.port,
                    uri.scheme
                )
            })
    }

    private fun URI.httpUri(): String = path + if (query.isNullOrBlank()) "" else "?$query"

    private val headersThatApacheInterceptorSets = setOf("Transfer-Encoding", "Content-Length")

    private fun Response.into(response: ApacheResponse) {
        response.code = status.code
        response.reasonPhrase = status.description
        headers.filter { !headersThatApacheInterceptorSets.contains(it.first) }
            .forEach { (key, value) -> response.addHeader(key, value) }
        if (response is HttpEntityContainer) {
            val contentType = CONTENT_TYPE(this@into)?.let { ContentType.parse(it.toHeaderValue()) }
                ?: ContentType.WILDCARD
            response.entity = InputStreamEntity(body.stream, body.length ?: -1L, contentType)
        }
    }

    private fun Array<Header>.toHttp4kHeaders(): Headers = map { it.name to it.value }
}

 class ApacheServer(
    val port: Int = 8000,
    val address: InetAddress? = null,
    private val canonicalHostname: String? = null,
    override val stopMode: StopMode = StopMode.Graceful(Duration.ofSeconds(5))
) : ServerConfig {

    constructor(port: Int = 8000) : this(port, null, null)
    constructor(port: Int = 8000, address: InetAddress? = null, canonicalHostname: String? = null) : this (port, address, canonicalHostname, StopMode.Immediate)

    override fun toServer(http: HttpHandler): Http4kServer = object : Http4kServer {
        private val server: HttpServer

        init {
            val bootstrap = ServerBootstrap.bootstrap()
                .setListenerPort(port)
                .setSocketConfig(
                    SocketConfig.custom()
                        .setTcpNoDelay(true)
                        .setSoKeepAlive(true)
                        .setSoReuseAddress(true)
                        .setBacklogSize(1000)
                        .build()
                )
                .register("*", Http4kRequestHandler(http))

            if (canonicalHostname != null)
                bootstrap.setCanonicalHostName(canonicalHostname)

            if (address != null)
                bootstrap.setLocalAddress(address)

            server = bootstrap.create()
        }

        override fun start() = apply { server.start() }

        override fun stop() = apply {
            when (stopMode) {
                is StopMode.Immediate -> server.close(IMMEDIATE)
                is StopMode.Graceful -> {
                    server.initiateShutdown()
                    try {
                        server.awaitTermination(TimeValue.ofMilliseconds(stopMode.timeout.toMillis()))
                    } catch (ex: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    server.close(IMMEDIATE)
                }
            }
        }

        override fun port(): Int = if (port != 0) port else server.localPort
    }
}
