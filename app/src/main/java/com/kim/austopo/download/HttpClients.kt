package com.kim.austopo.download

import com.kim.austopo.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Outbound HTTP politeness. OSM (and therefore OpenTopoMap, which serves
 * OSM-derived tiles) explicitly prohibit the default `okhttp/x.y` User-Agent:
 *   https://operations.osmfoundation.org/policies/tiles/
 *
 * Every tile request — state servers included, so there's nothing to remember —
 * goes out with a clear, self-identifying User-Agent.
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
