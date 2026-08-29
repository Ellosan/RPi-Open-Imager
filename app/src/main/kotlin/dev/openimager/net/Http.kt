package dev.openimager.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {

    const val USER_AGENT = "RPi-Open-Imager/1.0 (Android)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // Writes run for many minutes; a write timeout would kill a healthy slow download.
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
