package com.xyoye.common_component.utils.danmu.source.impl

import com.xyoye.common_component.network.repository.ResourceRepository

import com.xyoye.common_component.utils.danmu.source.AbstractDanmuSource
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder

/**
 * Created by xyoye on 2024/1/14.
 */

class NetworkDanmuSource(
    private val url: String,
    private val headers: Map<String, String> = emptyMap()
) : AbstractDanmuSource() {

    /** 算 hash 只需要前 16MB，避免整集下载 */
    private val hashHeaders: Map<String, String> by lazy {
        if (headers.keys.any { it.equals("Range", ignoreCase = true) }) {
            headers
        } else {
            headers + ("Range" to "bytes=0-16777215")
        }
    }

    /**
     * 文件名（含扩展名）。当前服务端不支持按文件名匹配，
     * 保留它只是为了日志/后续能力，不参与匹配。
     */
    override fun fileName(): String? {
        return try {
            val path = URI(url).path ?: return null
            val name = path.substringAfterLast('/')
            if (name.isBlank()) null else URLDecoder.decode(name, "UTF-8")
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getStream(): InputStream? {
        return ResourceRepository.getResourceResponseBody(url, hashHeaders)
            .getOrNull()
            ?.byteStream()
    }
}
