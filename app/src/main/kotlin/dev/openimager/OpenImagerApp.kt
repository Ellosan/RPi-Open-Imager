package dev.openimager

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import dev.openimager.net.CatalogueRepository
import dev.openimager.settings.SettingsStore
import dev.openimager.storage.StorageRepository
import dev.openimager.write.WriteService

/** Hand rolled dependency graph: the app has few enough moving parts not to need a framework. */
class AppGraph(context: Context) {
    val catalogueRepository = CatalogueRepository(context)
    val storageRepository = StorageRepository(context)
    val settings = SettingsStore(context)
}

class OpenImagerApp : Application(), ImageLoaderFactory {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        WriteService.createChannel(this)
    }

    /** A few OS entries in the catalogue ship SVG icons. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(SvgDecoder.Factory()) }
            .crossfade(true)
            .build()
}

val Context.appGraph: AppGraph
    get() = (applicationContext as OpenImagerApp).graph
