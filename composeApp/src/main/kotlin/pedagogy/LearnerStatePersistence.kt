package pedagogy

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persists learner progress to disk and reloads it across application restarts.
 * Isolates progress by learner ID and task profiles.
 */
class LearnerStatePersistence(
    private val rootDirectory: Path = defaultStateDirectory()
) {
    companion object {
        fun defaultStateDirectory(): Path {
            val localAppData = System.getenv("LOCALAPPDATA")
                ?: System.getProperty("user.home")
            return Paths.get(localAppData, "AIVisualTutor", "learner-state")
        }
    }

    suspend fun save(state: LearnerState) = withContext(Dispatchers.IO) {
        Files.createDirectories(rootDirectory)
        val file = rootDirectory.resolve("${state.learnerId}.json")
        val tempFile = rootDirectory.resolve(".${state.learnerId}.tmp")

        val json = serializeState(state)
        Files.writeString(tempFile, json, StandardCharsets.UTF_8)
        try {
            Files.move(tempFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    suspend fun load(learnerId: String): LearnerState? = withContext(Dispatchers.IO) {
        val file = rootDirectory.resolve("$learnerId.json")
        if (!Files.exists(file)) return@withContext null

        try {
            val content = Files.readString(file, StandardCharsets.UTF_8)
            val state = deserializeState(content)
            if (!state.learnerId.equals(learnerId, ignoreCase = true)) {
                System.err.println("Learner ID mismatch in file '$file': expected '$learnerId' but found '${state.learnerId}'")
                return@withContext null
            }
            state
        } catch (ex: Exception) {
            System.err.println("Failed to load learner state for '$learnerId': ${ex.message}")
            null
        }
    }

    fun serializeState(state: LearnerState): String = buildString {
        append("{\n")
        append("  \"learnerId\": \"${escape(state.learnerId)}\",\n")
        append("  \"currentTaskId\": \"${escape(state.currentTaskId)}\",\n")
        append("  \"currentSkillId\": \"${escape(state.currentSkillId)}\",\n")
        append("  \"currentGuidanceStage\": \"${state.currentGuidanceStage.name}\",\n")
        append("  \"consecutiveSuccesses\": ${state.consecutiveSuccesses},\n")
        append("  \"consecutiveFailures\": ${state.consecutiveFailures},\n")
        append("  \"stageConsecutiveSuccesses\": ${state.stageConsecutiveSuccesses},\n")
        append("  \"totalVerifiedSuccesses\": ${state.totalVerifiedSuccesses},\n")
        append("  \"totalVerifiedFailures\": ${state.totalVerifiedFailures},\n")
        append("  \"totalUncertainOutcomes\": ${state.totalUncertainOutcomes},\n")
        append("  \"competenceEstimate\": ${state.competenceEstimate},\n")
        append("  \"adaptationReason\": \"${escape(state.adaptationReason)}\",\n")
        append("  \"requiresReassessment\": ${state.requiresReassessment},\n")
        append("  \"taskProfiles\": {\n")
        val profiles = state.taskProfiles.entries.toList()
        profiles.forEachIndexed { index, (taskId, profile) ->
            append("    \"${escape(taskId)}\": {\n")
            append("      \"taskId\": \"${escape(profile.taskId)}\",\n")
            append("      \"skillId\": \"${escape(profile.skillId)}\",\n")
            append("      \"guidanceStage\": \"${profile.guidanceStage.name}\",\n")
            append("      \"consecutiveSuccesses\": ${profile.consecutiveSuccesses},\n")
            append("      \"consecutiveFailures\": ${profile.consecutiveFailures},\n")
            append("      \"stageConsecutiveSuccesses\": ${profile.stageConsecutiveSuccesses},\n")
            append("      \"totalSuccesses\": ${profile.totalSuccesses},\n")
            append("      \"totalFailures\": ${profile.totalFailures},\n")
            append("      \"totalUncertain\": ${profile.totalUncertain},\n")
            append("      \"competenceEstimate\": ${profile.competenceEstimate}\n")
            append("    }${if (index < profiles.size - 1) "," else ""}\n")
        }
        append("  }\n")
        append("}\n")
    }

    fun deserializeState(json: String): LearnerState {
        val trimmed = json.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw IllegalArgumentException("Malformed JSON: LearnerState content must be a valid JSON object.")
        }

        // Robust lightweight JSON parser for the known state schema
        fun extractString(key: String): String =
            Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1) ?: ""

        fun extractInt(key: String): Int =
            Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 0

        fun extractDouble(key: String): Double =
            Regex("\"$key\"\\s*:\\s*([0-9.]+)").find(json)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

        fun extractBoolean(key: String): Boolean =
            Regex("\"$key\"\\s*:\\s*(true|false)").find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: false

        val learnerId = extractString("learnerId").ifBlank { "default_learner" }
        val currentTaskId = extractString("currentTaskId").ifBlank { "default_task" }
        val currentSkillId = extractString("currentSkillId").ifBlank { "general" }
        val stageStr = extractString("currentGuidanceStage")
        val stage = runCatching { GuidanceStage.valueOf(stageStr) }.getOrDefault(GuidanceStage.STAGE_1_EXACT_GUIDANCE)
        val consecutiveSuccesses = extractInt("consecutiveSuccesses")
        val consecutiveFailures = extractInt("consecutiveFailures")
        val stageConsecutiveSuccesses = extractInt("stageConsecutiveSuccesses")
        val totalSuccesses = extractInt("totalVerifiedSuccesses")
        val totalFailures = extractInt("totalVerifiedFailures")
        val totalUncertain = extractInt("totalUncertainOutcomes")
        val competence = extractDouble("competenceEstimate").coerceIn(0.0, 1.0)
        val reason = extractString("adaptationReason")
        val reassess = extractBoolean("requiresReassessment")

        // Parse taskProfiles map
        val profilesMap = mutableMapOf<String, TaskLearnerProfile>()
        val profileBlockRegex = Regex("\"([^\"]+)\"\\s*:\\s*\\{([^}]+)\\}")
        val taskProfilesIndex = json.indexOf("\"taskProfiles\"")
        if (taskProfilesIndex != -1) {
            val openBrace = json.indexOf('{', taskProfilesIndex)
            if (openBrace != -1) {
                val profilesSubstring = json.substring(openBrace + 1)
                profileBlockRegex.findAll(profilesSubstring).forEach { match ->
                    val taskId = match.groupValues[1]
                    val block = match.groupValues[2]
                    fun inBlockString(k: String) = Regex("\"$k\"\\s*:\\s*\"([^\"]*)\"").find(block)?.groupValues?.get(1) ?: ""
                    fun inBlockInt(k: String) = Regex("\"$k\"\\s*:\\s*(\\d+)").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    fun inBlockDouble(k: String) = Regex("\"$k\"\\s*:\\s*([0-9.]+)").find(block)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

                    val pSkill = inBlockString("skillId").ifBlank { "general" }
                    val pStage = runCatching { GuidanceStage.valueOf(inBlockString("guidanceStage")) }.getOrDefault(GuidanceStage.STAGE_1_EXACT_GUIDANCE)
                    val pSucc = inBlockInt("consecutiveSuccesses")
                    val pFail = inBlockInt("consecutiveFailures")
                    val pStageSucc = inBlockInt("stageConsecutiveSuccesses")
                    val pTotalSucc = inBlockInt("totalSuccesses")
                    val pTotalFail = inBlockInt("totalFailures")
                    val pTotalUncertain = inBlockInt("totalUncertain")
                    val pComp = inBlockDouble("competenceEstimate").coerceIn(0.0, 1.0)

                    profilesMap[taskId] = TaskLearnerProfile(
                        taskId = taskId,
                        skillId = pSkill,
                        guidanceStage = pStage,
                        consecutiveSuccesses = pSucc,
                        consecutiveFailures = pFail,
                        stageConsecutiveSuccesses = pStageSucc,
                        totalSuccesses = pTotalSucc,
                        totalFailures = pTotalFail,
                        totalUncertain = pTotalUncertain,
                        competenceEstimate = pComp
                    )
                }
            }
        }

        return LearnerState(
            learnerId = learnerId,
            currentTaskId = currentTaskId,
            currentSkillId = currentSkillId,
            currentGuidanceStage = stage,
            consecutiveSuccesses = consecutiveSuccesses,
            consecutiveFailures = consecutiveFailures,
            stageConsecutiveSuccesses = stageConsecutiveSuccesses,
            totalVerifiedSuccesses = totalSuccesses,
            totalVerifiedFailures = totalFailures,
            totalUncertainOutcomes = totalUncertain,
            competenceEstimate = competence,
            adaptationReason = reason,
            requiresReassessment = reassess,
            taskProfiles = profilesMap
        )
    }

    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
}
