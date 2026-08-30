package ai.argvid.gen0.media.catalog

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper

class MediaStoreChangeObserver(
    private val resolver: ContentResolver,
    private val watchedUri: Uri,
    private val scheduleRefresh: (Uri?) -> Unit,
) : ContentObserver(Handler(Looper.getMainLooper())) {
    fun start() {
        resolver.registerContentObserver(watchedUri, true, this)
    }

    fun stop() {
        resolver.unregisterContentObserver(this)
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        scheduleRefresh(uri)
    }
}
