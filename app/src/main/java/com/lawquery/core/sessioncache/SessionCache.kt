package com.lawquery.core.sessioncache

import com.lawquery.domain.model.LawDocument

/**
 * 会话缓存(需求 6.1 唯一允许的缓存形态):
 * - 内存级 LruCache,容量 ≤ 20 个文档,TTL 5 分钟
 * - 条目携带 fetchedAt 并由 UI 展示
 * - 进程结束即消失;任何路径都不会读取磁盘正文
 */
class SessionCache(
    private val maxSize: Int = 20,
    private val ttlMs: Long = 5 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private data class Entry(val doc: LawDocument, val fetchedAtMs: Long)

    private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > maxSize
    }

    @Synchronized
    fun get(key: String): LawDocument? {
        val entry = map[key] ?: return null
        if (clock() - entry.fetchedAtMs > ttlMs) {
            map.remove(key)
            return null
        }
        return entry.doc
    }

    @Synchronized
    fun put(key: String, doc: LawDocument, fetchedAtMs: Long = clock()) {
        map[key] = Entry(doc, fetchedAtMs)
    }

    @Synchronized
    fun clear() = map.clear()

    @Synchronized
    fun size(): Int = map.size
}
