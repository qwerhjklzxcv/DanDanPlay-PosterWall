package com.xyoye.common_component.network.helper

import com.xyoye.common_component.base.app.BaseApplication
import com.xyoye.common_component.utils.SecurityHelper
import okhttp3.Interceptor
import okhttp3.Response


/**
 *    author: xyoye1997@outlook.com
 *    time  : 2025/1/21
 *    desc  : 签名验证拦截器
 */
class SignatureInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val oldRequest = chain.request()
        val newRequest = oldRequest.newBuilder()

        // 非官方签名构建下 native 层拿不到签名表，这里可能是 null；
        // 上游直接 .forEach 会抛 NPE，导致所有请求变成「未知错误」
        SecurityHelper.getInstance().getSignatureMap(
            oldRequest.url.encodedPath,
            BaseApplication.getAppContext()
        )?.forEach {
            newRequest.addHeader(it.key, it.value ?: "")
        }

        return chain.proceed(newRequest.build())
    }
}