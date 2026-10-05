package com.az.notes.ui.reader

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import java.io.File
import java.net.URLDecoder

/**
 * 预览页图片加载器：把 Markdown 中的本地图片链接解析为 Vault 内的真实文件，
 * 用 Coil 异步加载后交给渲染库的默认图片组件展示；点击回调 [onImageClick]（全屏预览）。
 *
 * 支持 `![x](a.png)`、`./`、`../` 相对路径（相对笔记所在目录）与 `/` Vault 根路径；
 * 网络图片（http/https/data/content）不做处理（返回 null 即不渲染）。
 */
class VaultImageTransformer(
    private val vaultRoot: String,
    private val baseDir: File,
    private val onImageClick: (File) -> Unit
) : ImageTransformer {

    @Composable
    override fun transform(link: String): ImageData? {
        val file = remember(link) { resolve(link) } ?: return null
        val painter = rememberAsyncImagePainter(
            model = ImageRequest.Builder(LocalPlatformContext.current)
                .data(Uri.fromFile(file))
                .crossfade(true)
                .build()
        )
        return ImageData(
            painter = painter,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onImageClick(file) }
        )
    }

    /** 解析图片链接为 Vault 内文件；非本地 / 越出 Vault / 文件不存在时返回 null。 */
    private fun resolve(link: String): File? {
        if (link.startsWith("http://", true) ||
            link.startsWith("https://", true) ||
            link.startsWith("data:", true) ||
            link.startsWith("content:", true)
        ) {
            return null
        }
        val cleaned = decode(link.substringBefore('#').substringBefore('?'))
            .removePrefix("<")
            .removeSuffix(">")
        if (cleaned.isBlank()) return null
        val root = File(vaultRoot).normalize()
        val target = (if (cleaned.startsWith("/")) {
            File(root, cleaned.trimStart('/'))
        } else {
            File(baseDir, cleaned)
        }).normalize()
        // 安全护栏：不加载越出 Vault 的文件
        if (target != root && !target.absolutePath.startsWith(root.absolutePath + File.separator)) {
            return null
        }
        return target.takeIf { it.isFile }
    }

    /** 百分号解码（保留 '+' 字符本身，不做表单语义转换）。 */
    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
}
