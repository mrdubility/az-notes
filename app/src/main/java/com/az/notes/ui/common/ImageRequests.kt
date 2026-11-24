package com.az.notes.ui.common

import android.net.Uri
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import java.io.File

/**
 * 本地图片的内存缓存键：路径 + 大小 + 修改时间。
 *
 * Coil 默认的缓存键只含数据源与请求尺寸，不包含文件版本——同步把同名图片覆盖成新图后
 * 路径没变，就会命中旧缓存，表现为「系统文件管理器里已经是新图，App 里还是旧图，
 * 杀掉进程再进来才更新」。把大小与 mtime 并入键后，文件一变就重新解码。
 */
fun localImageCacheKey(file: File): String =
    "${file.absolutePath}:${file.length()}:${file.lastModified()}"

/** 文件版本令牌：作为组合期 `remember` 的键，文件被覆盖后重建请求。 */
fun localFileVersion(file: File): String = "${file.length()}:${file.lastModified()}"

/**
 * 以本地文件为数据源构建请求，并带上文件版本化的内存缓存键。
 * 其余参数（尺寸 / crossfade 等）由调用方继续链式设置。
 */
fun ImageRequest.Builder.localFile(file: File): ImageRequest.Builder =
    data(Uri.fromFile(file)).memoryCacheKey(MemoryCache.Key(localImageCacheKey(file)))
