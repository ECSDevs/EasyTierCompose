package cc.ptoe.easytier.compose.core

fun interface NativeConfigParser {
    /** Returns null when the document is valid, otherwise the native failure text. */
    fun parse(toml: String): String?
}

object EasyTierNativeConfigParser : NativeConfigParser {
    override fun parse(toml: String): String? = try {
        if (EasyTierJni.parseConfig(toml) == 0) null else EasyTierJni.getLastError() ?: "EasyTier rejected the configuration"
    } catch (error: RuntimeException) {
        EasyTierJni.getLastError() ?: error.message ?: "EasyTier rejected the configuration"
    }
}
