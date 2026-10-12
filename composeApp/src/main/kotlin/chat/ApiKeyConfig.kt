package chat

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves the Google Gemini API key from multiple convenient locations:
 * 1. Runtime memory override (set via UI)
 * 2. .env file in the current working directory or user home
 * 3. local.properties in the project root
 * 4. System environment variable GEMINI_API_KEY
 * 5. JVM system property gemini.api.key
 */
object ApiKeyConfig {

    private var runtimeApiKeyOverride: String? = null
    private var runtimeGroqKeyOverride: String? = null

    fun setRuntimeKey(key: String) {
        runtimeApiKeyOverride = key.trim().takeIf(String::isNotEmpty)
    }

    fun setRuntimeGroqKey(key: String) {
        runtimeGroqKeyOverride = key.trim().takeIf(String::isNotEmpty)
    }

    fun getGroqApiKey(): String {
        // 1. Runtime override
        runtimeGroqKeyOverride?.let { return it }

        // 2. System environment variable
        val envKey = System.getenv("GROQ_API_KEY")?.trim()
        if (!envKey.isNullOrEmpty()) return envKey

        // 3. JVM system property
        val propKey = System.getProperty("groq.api.key")?.trim()
        if (!propKey.isNullOrEmpty()) return propKey

        // 4. .env file in current directory, parent directory, or user home
        val dotEnvKey = resolveKeyFromFiles(".env", "GROQ_API_KEY")
        if (!dotEnvKey.isNullOrEmpty()) return dotEnvKey

        // 5. local.properties in current directory or parent directory
        val localPropKey = resolveKeyFromFiles("local.properties", "GROQ_API_KEY")
        if (!localPropKey.isNullOrEmpty()) return localPropKey

        return ""
    }

    fun getGeminiApiKey(): String {
        // 1. Runtime override
        runtimeApiKeyOverride?.let { return it }

        // 2. System environment variable
        val envKey = System.getenv("GEMINI_API_KEY")?.trim()
        if (!envKey.isNullOrEmpty()) return envKey

        // 3. JVM system property
        val propKey = System.getProperty("gemini.api.key")?.trim()
        if (!propKey.isNullOrEmpty()) return propKey

        // 4. .env file in current directory, parent directory, or user home
        val dotEnvKey = resolveKeyFromFiles(".env", "GEMINI_API_KEY")
        if (!dotEnvKey.isNullOrEmpty()) return dotEnvKey

        // 5. local.properties in current directory or parent directory
        val localPropKey = resolveKeyFromFiles("local.properties", "GEMINI_API_KEY")
        if (!localPropKey.isNullOrEmpty()) return localPropKey

        return ""
    }

    fun getActiveProvider(): String {
        return when {
            getGroqApiKey().isNotEmpty() -> "Groq"
            getGeminiApiKey().isNotEmpty() -> "Gemini"
            else -> "Offline"
        }
    }

    private fun resolveKeyFromFiles(fileName: String, keyName: String): String? {
        val candidates = mutableListOf<Path>()
        candidates.add(Paths.get(fileName))
        candidates.add(Paths.get("..", fileName))
        val userDir = Paths.get(System.getProperty("user.dir", "."))
        candidates.add(userDir.resolve(fileName))
        userDir.parent?.let { candidates.add(it.resolve(fileName)) }
        candidates.add(Paths.get(System.getProperty("user.home")).resolve(fileName))

        for (path in candidates) {
            val key = loadKeyFromFile(path, keyName)
            if (!key.isNullOrEmpty()) return key
        }
        return null
    }

    private fun loadKeyFromFile(path: Path, keyName: String): String? {
        return try {
            if (!Files.exists(path)) return null
            val lines = Files.readAllLines(path)
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("#") || trimmed.isEmpty()) continue
                if (trimmed.startsWith("$keyName=")) {
                    val value = trimmed.substring("$keyName=".length).trim()
                    // Strip quotes if present
                    val cleanValue = value.removeSurrounding("\"").removeSurrounding("'")
                    if (cleanValue.isNotEmpty()) return cleanValue
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
