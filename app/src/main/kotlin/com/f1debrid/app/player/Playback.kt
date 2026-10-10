package com.f1debrid.app.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.f1debrid.app.Graph
import com.f1debrid.core.sync.CatalogFile

object Playback {
    /** Turns the Real-Debrid hoster link of a file into a direct, playable URL. */
    suspend fun resolve(graph: Graph, file: CatalogFile): String {
        val link = file.link ?: throw IllegalStateException("This file has no Real-Debrid link yet")
        return graph.rd.unrestrict(link).download
    }

    fun launchBuiltIn(context: Context, url: String, title: String, resumeKey: String): Boolean {
        val intent = Intent(context, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_URL, url)
            .putExtra(PlayerActivity.EXTRA_TITLE, title)
            .putExtra(PlayerActivity.EXTRA_RESUME_KEY, resumeKey)
        context.startActivity(intent)
        return true
    }

    /** Hands the stream to whatever video player the user has installed (Kodi, VLC, MX Player…). */
    fun launchExternal(context: Context, url: String, title: String): Boolean {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(url), "video/*")
            .putExtra("title", title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val chooser = Intent.createChooser(view, "Play with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(chooser)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
