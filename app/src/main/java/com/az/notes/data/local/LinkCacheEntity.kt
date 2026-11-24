package com.az.notes.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * 双链索引缓存：已索引笔记的文件指纹。
 *
 * 一行存在即表示"该文件已按当前指纹完成过提取（含 0 条引用的文件）"，
 * 冷启动据此判断哪些文件需要重读正文。
 */
@Entity(tableName = "link_note", primaryKeys = ["scope", "path"])
data class LinkNoteEntity(
    /** 仓库作用域（规范化后的仓库根绝对路径） */
    @ColumnInfo(name = "scope") val scope: String,
    val path: String,
    val size: Long,
    val mtime: Long
)

/** 双链索引缓存：单条引用（按文件内出现顺序编号，作为稳定主键）。 */
@Entity(tableName = "link_ref", primaryKeys = ["scope", "source_path", "ordinal"])
data class LinkRefEntity(
    @ColumnInfo(name = "scope") val scope: String,
    @ColumnInfo(name = "source_path") val sourcePath: String,
    /** 该文件内的第几条引用（0 基） */
    val ordinal: Int,
    val line: Int,
    @ColumnInfo(name = "start_offset") val startOffset: Int,
    @ColumnInfo(name = "end_offset") val endOffset: Int,
    val raw: String,
    val target: String,
    val anchor: String?,
    val alias: String?,
    @ColumnInfo(name = "is_embed") val isEmbed: Boolean,
    val snippet: String
)

/** 双链索引缓存：笔记别名声明（供解析回退，无需每次启动读文件头）。 */
@Entity(tableName = "link_alias", primaryKeys = ["scope", "alias", "relative_path"])
data class LinkAliasEntity(
    @ColumnInfo(name = "scope") val scope: String,
    val alias: String,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    /** aliases 列表内的下标（同名别名消歧用） */
    @ColumnInfo(name = "declaration_index") val declarationIndex: Int
)
