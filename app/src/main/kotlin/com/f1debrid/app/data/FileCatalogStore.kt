package com.f1debrid.app.data

import android.content.Context
import android.util.Log
import com.f1debrid.core.sync.Catalog
import com.f1debrid.core.sync.CatalogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** The library of releases, persisted as JSON in the app's private storage. */
class FileCatalogStore(context: Context) : CatalogStore {
    private val file = File(context.filesDir, "catalog.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()

    private val _catalog = MutableStateFlow(readFromDisk())
    val catalog: StateFlow<Catalog> = _catalog

    override suspend fun load(): Catalog = _catalog.value

    override suspend fun save(catalog: Catalog) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json.encodeToString(Catalog.serializer(), catalog))
                if (!tmp.renameTo(file)) {
                    file.writeText(json.encodeToString(Catalog.serializer(), catalog))
                    tmp.delete()
                }
            }
            _catalog.value = catalog
        }
    }

    suspend fun update(transform: (Catalog) -> Catalog): Catalog {
        val updated = transform(_catalog.value)
        save(updated)
        return updated
    }

    private fun readFromDisk(): Catalog {
        if (!file.exists()) return Catalog()
        return runCatching { json.decodeFromString(Catalog.serializer(), file.readText()) }
            .onFailure { Log.w("F1Debrid", "Could not read catalog, starting fresh", it) }
            .getOrDefault(Catalog())
    }
}
