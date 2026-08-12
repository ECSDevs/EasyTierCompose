package cc.ptoe.easytier.compose.core

import cc.ptoe.easytier.compose.R

fun interface NativeConfigParser {
    /** Returns null when the document is valid, otherwise a localized or native failure message. */
    fun parse(toml: String): ValidationMessage?
}

object EasyTierNativeConfigParser : NativeConfigParser {
    override fun parse(toml: String): ValidationMessage? = try {
        if (EasyTierJni.parseConfig(toml) == 0) {
            null
        } else {
            EasyTierJni.getLastError()?.let(ValidationMessage::Raw)
                ?: ValidationMessage.Resource(R.string.error_native_rejected)
        }
    } catch (error: RuntimeException) {
        ValidationMessage.Raw(EasyTierJni.getLastError() ?: error.message.orEmpty())
            .takeUnless { it.value.isEmpty() }
            ?: ValidationMessage.Resource(R.string.error_native_rejected)
    }
}
