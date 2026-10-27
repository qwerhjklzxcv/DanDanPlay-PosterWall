package com.xyoye.user_component.ui.dialog

import android.app.Activity
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.DevelopConfig
import com.xyoye.common_component.extension.startUrlActivity
import com.xyoye.common_component.network.repository.UserRepository
import com.xyoye.common_component.utils.SupervisorScope
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.common_component.weight.dialog.BaseBottomDialog
import com.xyoye.user_component.R
import com.xyoye.user_component.databinding.DialogDeveloperAuthenticateBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 *    author: xyoye1997@outlook.com
 *    time  : 2025/1/22
 *    desc  :
 */

class DeveloperAuthenticateDialog(
    private val activity: Activity,
    private val onAuthenticate: () -> Unit
) : BaseBottomDialog<DialogDeveloperAuthenticateBinding>(activity) {

    private lateinit var binding: DialogDeveloperAuthenticateBinding

    override fun getChildLayoutId(): Int {
        return R.layout.dialog_developer_authenticate
    }

    override fun initView(binding: DialogDeveloperAuthenticateBinding) {
        this.binding = binding

        setTitle("开发者认证")

        binding.inputAppId.setText(DevelopConfig.getAppId())
        binding.inputAppSecret.setText(DevelopConfig.getAppSecret())

        setNegativeText("忽略")
        setNegativeListener {
            dismiss()
        }

        setPositiveListener {
            checkAuthenticate()
        }

        binding.tvReadDocument.setOnClickListener {
            activity.startUrlActivity("https://doc.dandanplay.com/open/")
        }
    }

    /**
     * 开发者认证
     */
    private fun checkAuthenticate() {
        val appId = binding.inputAppId.text.toString().trim()
        val appSecret = binding.inputAppSecret.text.toString().trim()

        if (appId.isEmpty() || appSecret.isEmpty()) {
            ToastCenter.showWarning("请输入AppId和AppSecret")
            return
        }

        SupervisorScope.IO.launch {
            loading(true)

            // login/renew 依赖用户登录态，未登录时可能返回 401；先试它，失败再用公开接口兜底
            val primary = UserRepository.checkAuthenticate(appId, appSecret)
            var code = primary.getOrNull()?.code()
            var detail = primary.exceptionOrNull()?.let {
                "${it.javaClass.simpleName}: ${it.message.orEmpty()}"
            }

            if (code != 200) {
                val fallback = UserRepository.checkAuthenticateBySearch(appId, appSecret)
                val fallbackCode = fallback.getOrNull()?.code()
                if (fallbackCode == 200) {
                    loading(false)
                    authenticateSuccess(appId, appSecret)
                    return@launch
                }
                fallback.exceptionOrNull()?.let {
                    detail = "${it.javaClass.simpleName}: ${it.message.orEmpty()}"
                }
                code = fallbackCode ?: code
            }

            loading(false)

            when {
                code == 200 -> authenticateSuccess(appId, appSecret)
                code == 403 -> ToastCenter.showError("认证失败：AppId 或 AppSecret 无效（403）")
                code == 401 -> ToastCenter.showError("认证失败：需要先在 App 内登录账号（401）")
                code != null -> ToastCenter.showError("认证失败（HTTP $code）")
                else -> ToastCenter.showError("网络请求失败：${detail ?: "未知错误"}")
            }
        }
    }

    /**
     * 显示/隐藏加载框
     */
    private suspend fun loading(show: Boolean) {
        if (activity is BaseActivity<*, *>) {
            withContext(Dispatchers.Main) {
                if (show) {
                    activity.showLoading()
                } else {
                    activity.hideLoading()
                }
            }
        }
    }

    /**
     * 认证成功
     */
    private fun authenticateSuccess(appId: String, appSecret: String) {
        DevelopConfig.putAppId(appId)
        DevelopConfig.putAppSecret(appSecret)
        ToastCenter.showSuccess("认证成功")
        onAuthenticate.invoke()
        dismiss()
    }
}