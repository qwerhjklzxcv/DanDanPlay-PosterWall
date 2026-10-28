package com.xyoye.common_component.utils.danmu.source

/**
 * Created by xyoye on 2024/1/14.
 */

interface DanmuSource {

    suspend fun hash(): String?

    /**
     * 文件名（含扩展名），用于「按文件名匹配」这条更轻量的路径。
     *
     * 网络媒体库（WebDAV / SMB / FTP / Alist）没有本地文件，
     * 为了算 hash 去下载前 16MB 又慢又容易超时，所以优先用文件名匹配。
     */
    fun fileName(): String? = null
}
