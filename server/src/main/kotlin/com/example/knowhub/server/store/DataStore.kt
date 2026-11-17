package com.example.knowhub.server.store

import com.example.knowhub.core.AppJson
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * JSON 快照存储：所有读走 [snapshot]，所有写走 [mutate]。
 * 写操作串行化，先写临时文件再原子替换，避免进程中断导致文件损坏。
 */
class DataStore(dataDir: File) {

    private val lock = Any()
    private val file = File(dataDir, "knowhub-db.json")
    private var db: Db = load()

    fun snapshot(): Db = synchronized(lock) { db }

    fun <T> mutate(block: (Db) -> Pair<Db, T>): T = synchronized(lock) {
        val (next, result) = block(db)
        db = next.copy(revision = db.revision + 1)
        persist(db)
        result
    }

    private fun load(): Db {
        if (!file.exists()) return Db()
        return runCatching {
            AppJson.decodeFromString(Db.serializer(), file.readText(Charsets.UTF_8))
        }.getOrElse { Db() }
    }

    private fun persist(value: Db) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(AppJson.encodeToString(Db.serializer(), value), Charsets.UTF_8)
        Files.move(
            tmp.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }
}

/**
 * 原始文档文本落盘（重导入/重切块时使用），与元数据分离。
 */
class DocumentStorage(dataDir: File) {

    private val dir = File(dataDir, "documents").apply { mkdirs() }

    fun save(docId: String, text: String): File {
        val file = File(dir, "$docId.txt")
        file.writeText(text, Charsets.UTF_8)
        return file
    }

    fun read(docId: String): String? {
        val file = File(dir, "$docId.txt")
        return if (file.exists()) file.readText(Charsets.UTF_8) else null
    }

    fun delete(docId: String) {
        File(dir, "$docId.txt").delete()
    }
}
