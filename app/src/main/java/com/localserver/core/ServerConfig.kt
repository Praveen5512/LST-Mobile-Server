package com.localserver.core

data class ServerConfig(
    val port: Int = DEFAULT_PORT,
    val bindAddress: String = DEFAULT_BIND_ADDRESS
) {
    init {
        require(isValidPort(port)) {
            "Port must be between $MIN_PORT and $MAX_PORT. Provided: $port"
        }
    }

    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_BIND_ADDRESS = "0.0.0.0"
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        fun isValidPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT
    }
}
