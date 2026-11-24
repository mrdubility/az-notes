package com.az.notes.data.storage

import androidx.room.withTransaction
import com.az.notes.data.local.AppDatabase
import com.az.notes.data.local.LinkAliasEntity
import com.az.notes.data.local.LinkCacheDao
import com.az.notes.data.local.LinkNoteEntity
import com.az.notes.data.local.LinkRefEntity
import com.az.notes.domain.markdown.NoteAlias
import com.az.notes.domain.markdown.RawLinkRef
import com.az.notes.domain.markdown.WikiLinkResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 双链索引缓存的 Room 实现：一个仓库作用域对应三张表的若干行。
 *
 * 写入一律"先删该文件旧行、再插入新行"，并放在同一事务内——中途失败不会留下
 * "指纹已更新但引用缺失"的状态（那会被误判成该文件 0 条引用）。
 */
@Singleton
class RoomLinkCacheStore @Inject constructor(
    private val database: AppDatabase,
    private val dao: LinkCacheDao
) : LinkCacheStore {

    override suspend fun load(scope: String): LinkCacheSnapshot? = withContext(Dispatchers.IO) {
        val notes = dao.notes(scope)
        if (notes.isEmpty()) return@withContext null
        val fingerprints = HashMap<String, CachedFingerprint>(notes.size)
        notes.forEach { fingerprints[it.path] = CachedFingerprint(it.size, it.mtime) }

        val refs = HashMap<String, MutableList<RawLinkRef>>()
        for (row in dao.refs(scope)) {
            refs.getOrPut(row.sourcePath) { ArrayList() } += RawLinkRef(
                line = row.line,
                raw = row.raw,
                target = row.target,
                anchor = row.anchor,
                alias = row.alias,
                isEmbed = row.isEmbed,
                snippet = row.snippet,
                startOffset = row.startOffset,
                endOffset = row.endOffset
            )
        }

        val aliases = HashMap<String, MutableList<NoteAlias>>()
        for (row in dao.aliases(scope)) {
            aliases.getOrPut(WikiLinkResolver.foldName(row.alias)) { ArrayList() } += NoteAlias(
                name = row.alias,
                relativePath = row.relativePath,
                declarationIndex = row.declarationIndex
            )
        }
        LinkCacheSnapshot(fingerprints, refs, aliases)
    }

    override suspend fun saveFiles(scope: String, files: List<CachedFile>) {
        if (files.isEmpty()) return
        withContext(Dispatchers.IO) {
            val notes = ArrayList<LinkNoteEntity>(files.size)
            val refs = ArrayList<LinkRefEntity>()
            val aliases = ArrayList<LinkAliasEntity>()
            for (file in files) {
                notes += LinkNoteEntity(scope, file.path, file.fingerprint.size, file.fingerprint.mtime)
                file.refs.forEachIndexed { index, ref ->
                    refs += LinkRefEntity(
                        scope = scope,
                        sourcePath = file.path,
                        ordinal = index,
                        line = ref.line,
                        startOffset = ref.startOffset,
                        endOffset = ref.endOffset,
                        raw = ref.raw,
                        target = ref.target,
                        anchor = ref.anchor,
                        alias = ref.alias,
                        isEmbed = ref.isEmbed,
                        snippet = ref.snippet
                    )
                }
                file.aliases.forEachIndexed { index, alias ->
                    aliases += LinkAliasEntity(scope, alias, file.relativePath, index)
                }
            }
            val paths = files.map { it.path }
            val relativePaths = files.map { it.relativePath }
            database.withTransaction {
                dao.deleteNotes(scope, paths)
                dao.deleteRefs(scope, paths)
                dao.deleteAliases(scope, relativePaths)
                dao.upsertNotes(notes)
                if (refs.isNotEmpty()) dao.upsertRefs(refs)
                if (aliases.isNotEmpty()) dao.upsertAliases(aliases)
            }
        }
    }

    override suspend fun removeFiles(scope: String, removed: List<RemovedFile>) {
        if (removed.isEmpty()) return
        withContext(Dispatchers.IO) {
            val paths = removed.map { it.absolutePath }
            val relativePaths = removed.map { it.relativePath }
            database.withTransaction {
                dao.deleteNotes(scope, paths)
                dao.deleteRefs(scope, paths)
                dao.deleteAliases(scope, relativePaths)
            }
        }
    }

    override suspend fun clear(scope: String) {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                dao.clearNotes(scope)
                dao.clearRefs(scope)
                dao.clearAliases(scope)
            }
        }
    }
}
