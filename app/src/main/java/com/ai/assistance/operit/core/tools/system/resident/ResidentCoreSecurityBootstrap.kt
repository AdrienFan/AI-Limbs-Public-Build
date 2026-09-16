package com.ai.assistance.operit.core.tools.system.resident

import android.content.Context
import android.os.Build
import java.security.KeyStore
import java.security.Security
import javax.net.ssl.SSLContext
import org.json.JSONObject

/**
 * app_process skips Zygote's JCA warm-up and ActivityThread's application binding.
 * Restore those process-local prerequisites before any Host business is retired. Do not create
 * an Application, replace credentials, generate keys, or weaken trust/SELinux checks.
 */
internal object ResidentCoreSecurityBootstrap {
    fun initialize(context: Context): JSONObject {
        val providerPreinstalled = Security.getProvider("AndroidKeyStore") != null
        if (!providerPreinstalled || Security.getProvider("AndroidKeyStoreBCWorkaround") == null) {
            val providerClass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "android.security.keystore2.AndroidKeyStoreProvider"
            } else {
                "android.security.keystore.AndroidKeyStoreProvider"
            }
            Class.forName(providerClass).getDeclaredMethod("install").invoke(null)
        }
        val provider = checkNotNull(Security.getProvider("AndroidKeyStore")) {
            "Resident Core AndroidKeyStore provider was not registered"
        }
        checkNotNull(Security.getProvider("AndroidKeyStoreBCWorkaround")) {
            "Resident Core AndroidKeyStore cipher provider was not registered"
        }
        checkNotNull(provider.getService("KeyGenerator", "AES")) {
            "Resident Core AndroidKeyStore AES key generator is unavailable"
        }
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        // Force an actual read through the keystore service; provider registration alone is not
        // evidence that this UID/SELinux domain can access it. Never log aliases or key material.
        keyStore.aliases().hasMoreElements()

        Class.forName("android.security.net.config.NetworkSecurityConfigProvider")
            .getDeclaredMethod("install", Context::class.java)
            .invoke(null, context)
        SSLContext.getInstance("TLS").apply { init(null, null, null) }

        return JSONObject()
            .put("android_keystore_provider_preinstalled", providerPreinstalled)
            .put("android_keystore_ready", true)
            .put("android_keystore_provider_class", provider.javaClass.name)
            .put("network_security_config_ready", true)
            .put("tls_initialized", true)
    }
}
