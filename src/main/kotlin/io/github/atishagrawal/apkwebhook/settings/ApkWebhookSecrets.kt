package io.github.atishagrawal.apkwebhook.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service

/**
 * PasswordSafe wrapper for the Google Chat webhook URL. Application-scoped because a
 * single user typically posts to one Chat space regardless of which IDE project is open.
 *
 * Note: PasswordSafe `get()` can block on the EDT in some IDE versions; prefer calling
 * [getWebhookUrl] from a background thread (e.g. `executeOnPooledThread { ... }`).
 */
@Service(Service.Level.APP)
class ApkWebhookSecrets {

    private val attrs = CredentialAttributes("APKWebhook:chatWebhookUrl")

    fun getWebhookUrl(): String? =
        PasswordSafe.instance.get(attrs)?.password?.toString()?.takeIf { it.isNotBlank() }

    fun setWebhookUrl(url: String?) {
        PasswordSafe.instance.set(attrs, Credentials("", url))
    }

    companion object {
        fun getInstance(): ApkWebhookSecrets =
            ApplicationManager.getApplication().getService(ApkWebhookSecrets::class.java)
    }
}
