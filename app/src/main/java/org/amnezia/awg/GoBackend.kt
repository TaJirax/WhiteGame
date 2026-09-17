package org.amnezia.awg

/**
 * JNI binding for the bundled amneziawg-go engine (`libwg-go.so`).
 *
 * The native library exports `Java_org_amnezia_awg_GoBackend_*`, so this class MUST stay in
 * package `org.amnezia.awg` with this exact name and these exact signatures, otherwise the
 * symbols will not bind at runtime. See NOTICE.md.
 *
 * The engine speaks both vanilla WireGuard and AmneziaWG: the obfuscation keys (jc/jmin/jmax,
 * s1..s4, h1..h4, i1..i5, …) are optional UAPI fields, and omitting them yields plain WireGuard.
 */
object GoBackend {

    /** True when `libwg-go.so` for this ABI loaded successfully. */
    @JvmStatic
    val isAvailable: Boolean

    /** Non-empty when loading failed — surfaced in the UI instead of crashing. */
    @JvmStatic
    var loadError: String = ""
        private set

    init {
        var ok = false
        try {
            System.loadLibrary("wg-go")
            ok = true
        } catch (t: Throwable) {
            loadError = t.message ?: t.javaClass.simpleName
        }
        isAvailable = ok
    }

    /**
     * Hands [tunFd] to the engine and applies [settings] (UAPI `set=1` text).
     * @return a tunnel handle >= 0, or -1 on failure. The engine owns (and closes) [tunFd] either way.
     */
    @JvmStatic
    external fun awgTurnOn(ifName: String, tunFd: Int, settings: String): Int

    @JvmStatic
    external fun awgTurnOff(handle: Int)

    /** Underlying UDP socket, to be passed to `VpnService.protect()`. -1 when unavailable. */
    @JvmStatic
    external fun awgGetSocketV4(handle: Int): Int

    @JvmStatic
    external fun awgGetSocketV6(handle: Int): Int

    /** Current device state as UAPI `get=1` text (rx/tx bytes, last handshake, …), or null. */
    @JvmStatic
    external fun awgGetConfig(handle: Int): String?

    @JvmStatic
    external fun awgVersion(): String?

    /** Engine version, or a reason string when the library is not usable. */
    fun versionOrError(): String =
        if (!isAvailable) "unavailable: ${loadError.ifBlank { "not loaded" }}"
        else runCatching { awgVersion() ?: "unknown" }.getOrElse { "unknown" }
}
