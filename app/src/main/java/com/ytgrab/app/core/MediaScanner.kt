package com.ytgrab.app.core

import android.content.Context
import android.media.MediaScannerConnection

/** Tells Android's media indexer about newly-downloaded files so they instantly
 *  show up in Files apps, gallery, and music players without a reboot. */
object MediaScanner {
    fun scan(context: Context, path: String) {
        MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
    }
}
