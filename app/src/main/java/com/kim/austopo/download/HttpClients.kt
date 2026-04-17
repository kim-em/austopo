package com.kim.austopo.download

import com.kim.austopo.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Self-identifying User-Agent on every tile request — good practice for any
 * public tile service. The default `okhttp/x.y` header is anonymous and some
 * providers (e.g. OSM) explicitly block it.
 */
internal const val USER_AGENT =
    "AusTopo/${BuildConfig.VERSION_NAME} (+https://github.com/kim-em/austopo)"

internal val UserAgentInterceptor = Interceptor { chain ->
    val req = chain.request()
    val withUa = if (req.header("User-Agent") == null) {
        req.newBuilder().header("User-Agent", USER_AGENT).build()
    } else req
    chain.proceed(withUa)
}
