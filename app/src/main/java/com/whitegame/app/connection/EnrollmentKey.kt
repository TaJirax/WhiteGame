package com.whitegame.app.connection

/** Persist before posting so a timeout or process restart retries the same server peer. */
object EnrollmentKey {
    fun loadOrCreate(read: () -> String?, write: (String) -> Boolean, generate: () -> String = Keys::generatePrivateKey): String {
        val previous = read()
        if (previous != null) {
            try { Keys.toHex(previous) } catch (_: Exception) {
                throw ConfigException("The stored device key could not be read. Restore the profile before enrolling again.")
            }
            return previous
        }
        val key = generate()
        Keys.toHex(key)
        if (!write(key)) throw ConfigException("Could not save the device key. No enrollment request was sent.")
        return key
    }
}
