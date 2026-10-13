package pedagogy

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Persists learner progress to disk and reloads it across application restarts.
 * Isolates progress by learner ID, supporting schema versions, skill mastery,
 * recorded difficulties, explicit preferences, and idempotent event tracking.
 */
class LearnerStatePersistence(
    private val rootDirectory: Path = defaultStateDirectory()
) {
    private val fileMutex = Mutex()

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        fun defaultStateDirectory(): Path {
            val localAppData = System.getenv("LOCALAPPDATA")
                ?: System.getProperty("user.home")
            return Paths.get(localAppData, "AIVisualTutor", "learner-state")
        }
    }

    suspend fun save(state: LearnerState): Boolean = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            val tempFile = rootDirectory.resolve(".${state.learnerId}.${UUID.randomUUID()}.tmp")
            try {
                Files.createDirectories(rootDirectory)
                val file = rootDirectory.resolve("${state.learnerId}.json")

                val json = serializeState(state)
                Files.writeString(tempFile, json, StandardCharsets.UTF_8)
                try {
                    Files.move(tempFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING)
                }
                true
            } catch (ex: Exception) {
                System.err.println("Failed to persist learner state for '${state.learnerId}': ${ex.message}")
                false
            } finally {
                try {
                    Files.deleteIfExists(tempFile)
                } catch (_: Exception) {
                }
            }
        }
    }

    suspend fun load(learnerId: String): LearnerState? = withContext(Dispatchers.IO) {
        val file = rootDirectory.resolve("$learnerId.json")
        if (!Files.exists(file)) return@withContext null

        try {
            val content = Files.readString(file, StandardCharsets.UTF_8)
            val trimmed = content.trim()
            if (trimmed.isEmpty()) {
                System.err.println("Learner state file '$file' is empty.")
                return@withContext null
            }
            val state = deserializeState(trimmed)
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

    suspend fun clear(learnerId: String): Boolean = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            try {
                val file = rootDirectory.resolve("$learnerId.json")
                Files.deleteIfExists(file)
                true
            } catch (ex: Exception) {
                System.err.println("Failed to clear learner state for '$learnerId': ${ex.message}")
                false
            }
        }
    }

    fun serializeState(state: LearnerState): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": ${state.schemaVersion},\n")
        append("  \"createdAt\": \"${state.createdAt}\",\n")
        append("  \"updatedAt\": \"${Instant.now()}\",\n")
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

        // Preferences
        append("  \"preferences\": {\n")
        append("    \"detailPreference\": \"${state.preferences.detailPreference.name}\",\n")
        append("    \"preferredGuidanceStage\": ${state.preferences.preferredGuidanceStage?.let { "\"${it.name}\"" } ?: "null"},\n")
        append("    \"wantsSmallerSteps\": ${state.preferences.wantsSmallerSteps},\n")
        append("    \"practiceRecommendationsEnabled\": ${state.preferences.practiceRecommendationsEnabled}\n")
        append("  },\n")

        // Demonstrated Skills
        append("  \"demonstratedSkills\": {\n")
        val skills = state.demonstratedSkills.entries.toList()
        skills.forEachIndexed { index, (skillId, skill) ->
            append("    \"${escape(skillId)}\": {\n")
            append("      \"skillId\": \"${escape(skill.skillId)}\",\n")
            append("      \"displayName\": \"${escape(skill.displayName)}\",\n")
            append("      \"masteryLevel\": \"${skill.masteryLevel.name}\",\n")
            append("      \"verifiedSuccessCount\": ${skill.verifiedSuccessCount},\n")
            append("      \"consecutiveSuccessCount\": ${skill.consecutiveSuccessCount},\n")
            append("      \"totalAttempts\": ${skill.totalAttempts},\n")
            append("      \"lastVerifiedAt\": ${skill.lastVerifiedAt?.let { "\"$it\"" } ?: "null"}\n")
            append("    }${if (index < skills.size - 1) "," else ""}\n")
        }
        append("  },\n")

        // Verified Outcomes
        append("  \"verifiedOutcomes\": [")
        append(state.verifiedOutcomes.joinToString(", ") { "\"${escape(it)}\"" })
        append("],\n")

        // Recorded Difficulties
        append("  \"recordedDifficulties\": {\n")
        val diffs = state.recordedDifficulties.entries.toList()
        diffs.forEachIndexed { index, (diffId, diff) ->
            append("    \"${escape(diffId)}\": {\n")
            append("      \"difficultyId\": \"${escape(diff.difficultyId)}\",\n")
            append("      \"skillId\": \"${escape(diff.skillId)}\",\n")
            append("      \"errorCategory\": \"${escape(diff.errorCategory)}\",\n")
            append("      \"occurrenceCount\": ${diff.occurrenceCount},\n")
            append("      \"explanation\": \"${escape(diff.explanation)}\",\n")
            append("      \"firstOccurredAt\": \"${diff.firstOccurredAt}\",\n")
            append("      \"lastOccurredAt\": \"${diff.lastOccurredAt}\"\n")
            append("    }${if (index < diffs.size - 1) "," else ""}\n")
        }
        append("  },\n")

        // Processed Event IDs
        append("  \"processedEventIds\": [")
        append(state.processedEventIds.take(500).joinToString(", ") { "\"${escape(it)}\"" })
        append("],\n")

        // Task Profiles
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

        // Robust lightweight JSON helpers
        fun extractString(key: String, source: String = json): String =
            Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(source)?.groupValues?.get(1) ?: ""

        fun extractInt(key: String, source: String = json): Int =
            Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(source)?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 0

        fun extractDouble(key: String, source: String = json): Double =
            Regex("\"$key\"\\s*:\\s*([0-9.]+)").find(source)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

        fun extractBoolean(key: String, source: String = json): Boolean =
            Regex("\"$key\"\\s*:\\s*(true|false)").find(source)?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: false

        val schemaVersion = extractInt("schemaVersion").takeIf { it > 0 } ?: 1
        if (schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw IllegalArgumentException("Unsupported schema version $schemaVersion (maximum supported is $CURRENT_SCHEMA_VERSION).")
        }

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

        val createdAtStr = extractString("createdAt")
        val createdAt = runCatching { Instant.parse(createdAtStr) }.getOrDefault(Instant.now())
        val updatedAtStr = extractString("updatedAt")
        val updatedAt = runCatching { Instant.parse(updatedAtStr) }.getOrDefault(Instant.now())

        // Preferences
        val prefBlockRegex = Regex("\"preferences\"\\s*:\\s*\\{([^}]+)\\}")
        val prefBlock = prefBlockRegex.find(json)?.groupValues?.get(1).orEmpty()
        val detailPrefStr = extractString("detailPreference", prefBlock)
        val detailPref = runCatching { DetailPreference.valueOf(detailPrefStr) }.getOrDefault(DetailPreference.NORMAL)
        val preferredStageStr = extractString("preferredGuidanceStage", prefBlock)
        val preferredStage = if (preferredStageStr.isNotBlank() && preferredStageStr != "null") {
            runCatching { GuidanceStage.valueOf(preferredStageStr) }.getOrNull()
        } else null
        val wantsSmaller = extractBoolean("wantsSmallerSteps", prefBlock)
        val practiceEnabled = if (prefBlock.contains("practiceRecommendationsEnabled")) {
            extractBoolean("practiceRecommendationsEnabled", prefBlock)
        } else true
        val preferences = LearnerPreferences(
            detailPreference = detailPref,
            preferredGuidanceStage = preferredStage,
            wantsSmallerSteps = wantsSmaller,
            practiceRecommendationsEnabled = practiceEnabled
        )

        // Demonstrated Skills
        val demonstratedSkills = mutableMapOf<String, DemonstratedSkill>()
        val skillBlockRegex = Regex("\"([^\"]+)\"\\s*:\\s*\\{([^}]+)\\}")
        val skillsIndex = json.indexOf("\"demonstratedSkills\"")
        if (skillsIndex != -1) {
            val openBrace = json.indexOf('{', skillsIndex)
            val closeBrace = findMatchingBrace(json, openBrace)
            if (openBrace != -1 && closeBrace != -1) {
                val blockContent = json.substring(openBrace + 1, closeBrace)
                skillBlockRegex.findAll(blockContent).forEach { match ->
                    val skillKey = match.groupValues[1]
                    val b = match.groupValues[2]
                    val sId = extractString("skillId", b).ifBlank { skillKey }
                    val dName = extractString("displayName", b).ifBlank { sId }
                    val mLevelStr = extractString("masteryLevel", b)
                    val mLevel = runCatching { SkillMasteryLevel.valueOf(mLevelStr) }.getOrDefault(SkillMasteryLevel.NOT_OBSERVED)
                    val sCount = extractInt("verifiedSuccessCount", b)
                    val cCount = extractInt("consecutiveSuccessCount", b)
                    val aCount = extractInt("totalAttempts", b)
                    val lTimeStr = extractString("lastVerifiedAt", b)
                    val lTime = runCatching { Instant.parse(lTimeStr) }.getOrNull()
                    demonstratedSkills[skillKey] = DemonstratedSkill(
                        skillId = sId,
                        displayName = dName,
                        masteryLevel = mLevel,
                        verifiedSuccessCount = sCount,
                        consecutiveSuccessCount = cCount,
                        totalAttempts = aCount,
                        lastVerifiedAt = lTime
                    )
                }
            }
        }

        // Verified Outcomes
        val verifiedOutcomes = mutableSetOf<String>()
        val outcomesRegex = Regex("\"verifiedOutcomes\"\\s*:\\s*\\[([^\\]]*)\\]")
        outcomesRegex.find(json)?.groupValues?.get(1)?.let { rawList ->
            Regex("\"([^\"]*)\"").findAll(rawList).forEach { m ->
                val v = m.groupValues[1].trim()
                if (v.isNotBlank()) verifiedOutcomes.add(v)
            }
        }

        // Recorded Difficulties
        val recordedDifficulties = mutableMapOf<String, LearnerDifficulty>()
        val diffIndex = json.indexOf("\"recordedDifficulties\"")
        if (diffIndex != -1) {
            val openBrace = json.indexOf('{', diffIndex)
            val closeBrace = findMatchingBrace(json, openBrace)
            if (openBrace != -1 && closeBrace != -1) {
                val blockContent = json.substring(openBrace + 1, closeBrace)
                skillBlockRegex.findAll(blockContent).forEach { match ->
                    val diffKey = match.groupValues[1]
                    val b = match.groupValues[2]
                    val dId = extractString("difficultyId", b).ifBlank { diffKey }
                    val sId = extractString("skillId", b).ifBlank { "general" }
                    val eCat = extractString("errorCategory", b).ifBlank { "Mismatch" }
                    val oCount = extractInt("occurrenceCount", b).coerceAtLeast(1)
                    val expl = extractString("explanation", b)
                    val fTime = runCatching { Instant.parse(extractString("firstOccurredAt", b)) }.getOrDefault(Instant.now())
                    val lTime = runCatching { Instant.parse(extractString("lastOccurredAt", b)) }.getOrDefault(Instant.now())
                    recordedDifficulties[diffKey] = LearnerDifficulty(
                        difficultyId = dId,
                        skillId = sId,
                        errorCategory = eCat,
                        occurrenceCount = oCount,
                        explanation = expl,
                        firstOccurredAt = fTime,
                        lastOccurredAt = lTime
                    )
                }
            }
        }

        // Processed Event IDs
        val processedEventIds = mutableSetOf<String>()
        val eventsRegex = Regex("\"processedEventIds\"\\s*:\\s*\\[([^\\]]*)\\]")
        eventsRegex.find(json)?.groupValues?.get(1)?.let { rawList ->
            Regex("\"([^\"]*)\"").findAll(rawList).forEach { m ->
                val v = m.groupValues[1].trim()
                if (v.isNotBlank()) processedEventIds.add(v)
            }
        }

        // Parse taskProfiles map
        val profilesMap = mutableMapOf<String, TaskLearnerProfile>()
        val taskProfilesIndex = json.indexOf("\"taskProfiles\"")
        if (taskProfilesIndex != -1) {
            val openBrace = json.indexOf('{', taskProfilesIndex)
            val closeBrace = findMatchingBrace(json, openBrace)
            if (openBrace != -1 && closeBrace != -1) {
                val profilesSubstring = json.substring(openBrace + 1, closeBrace)
                skillBlockRegex.findAll(profilesSubstring).forEach { match ->
                    val taskId = match.groupValues[1]
                    val block = match.groupValues[2]
                    val pSkill = extractString("skillId", block).ifBlank { "general" }
                    val pStage = runCatching { GuidanceStage.valueOf(extractString("guidanceStage", block)) }.getOrDefault(GuidanceStage.STAGE_1_EXACT_GUIDANCE)
                    val pSucc = extractInt("consecutiveSuccesses", block)
                    val pFail = extractInt("consecutiveFailures", block)
                    val pStageSucc = extractInt("stageConsecutiveSuccesses", block)
                    val pTotalSucc = extractInt("totalSuccesses", block)
                    val pTotalFail = extractInt("totalFailures", block)
                    val pTotalUncertain = extractInt("totalUncertain", block)
                    val pComp = extractDouble("competenceEstimate", block).coerceIn(0.0, 1.0)

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
            schemaVersion = schemaVersion,
            createdAt = createdAt,
            updatedAt = updatedAt,
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
            taskProfiles = profilesMap,
            demonstratedSkills = demonstratedSkills,
            verifiedOutcomes = verifiedOutcomes,
            recordedDifficulties = recordedDifficulties,
            preferences = preferences,
            processedEventIds = processedEventIds
        )
    }

    private fun findMatchingBrace(source: String, openPos: Int): Int {
        if (openPos == -1 || openPos >= source.length || source[openPos] != '{') return -1
        var depth = 0
        for (i in openPos until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
}
