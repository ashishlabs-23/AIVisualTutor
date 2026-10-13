package tutor

/**
 * Shared JSON parser for multimodal screenshot analysis responses from Gemini and Groq.
 * Parses structured visual observations, detected UI elements, and uncertainty notes
 * without requiring external JSON dependencies.
 */
object MultimodalAnalysisJsonParser {

    fun parseJsonAnalysis(jsonText: String, modelId: String, latency: Long): ScreenshotAnalysisResult {
        val summary = extractRootStringField(jsonText, "summary")
            ?: extractStringField(jsonText, "summary")
            ?: "Visual analysis of Blender workspace completed."
        val suggestedNextStep = extractRootStringField(jsonText, "suggestedNextStepEvidence")
            ?: extractStringField(jsonText, "suggestedNextStepEvidence")
        val confidence = extractRootDoubleField(jsonText, "confidence")

        val observations = parseObservations(jsonText)
        val uiElements = parseUiElements(jsonText)
        val uncertaintyNotes = parseStringArray(jsonText, "uncertaintyNotes")

        return ScreenshotAnalysisResult(
            summary = summary,
            observations = observations,
            relevantUiElements = uiElements,
            uncertaintyNotes = uncertaintyNotes,
            suggestedNextStepEvidence = suggestedNextStep,
            confidence = confidence,
            modelIdentifier = modelId,
            latencyMillis = latency,
            rawModelResponse = jsonText.take(1500)
        )
    }

    fun unescapeJsonString(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (raw[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'u' -> {
                        if (i + 5 < raw.length) {
                            val hex = raw.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        sb.append(c)
                        i++
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    fun extractGroqMessageContent(responseBody: String): String {
        val messageIdx = responseBody.indexOf("\"message\"")
        val searchStart = if (messageIdx != -1) messageIdx else 0
        val contentMarker = "\"content\":"
        val idx = responseBody.indexOf(contentMarker, searchStart)
        if (idx != -1) {
            val start = responseBody.indexOf('"', idx + contentMarker.length)
            if (start != -1) {
                val end = findClosingQuote(responseBody, start + 1)
                if (end != -1) {
                    val raw = responseBody.substring(start + 1, end)
                    return unescapeJsonString(raw)
                }
            }
        }
        return responseBody
    }

    fun extractGeminiCandidateText(responseBody: String): String {
        val textMarker = "\"text\":"
        val idx = responseBody.indexOf(textMarker)
        if (idx != -1) {
            val start = responseBody.indexOf('"', idx + textMarker.length)
            if (start != -1) {
                val end = findClosingQuote(responseBody, start + 1)
                if (end != -1) {
                    val raw = responseBody.substring(start + 1, end)
                    return unescapeJsonString(raw)
                }
            }
        }
        return responseBody
    }

    fun extractRootStringField(json: String, fieldName: String): String? {
        val pattern = "\"$fieldName\""
        var idx = 0
        while (idx < json.length) {
            val match = json.indexOf(pattern, idx)
            if (match == -1) break
            val prefix = json.substring(0, match)
            val braceDepth = prefix.count { it == '{' } - prefix.count { it == '}' }
            val bracketDepth = prefix.count { it == '[' } - prefix.count { it == ']' }
            if (braceDepth == 1 && bracketDepth == 0) {
                val colonIdx = json.indexOf(':', match + pattern.length)
                if (colonIdx != -1) {
                    val quoteStart = json.indexOf('"', colonIdx + 1)
                    if (quoteStart != -1) {
                        val quoteEnd = findClosingQuote(json, quoteStart + 1)
                        if (quoteEnd != -1) {
                            return unescapeJsonString(json.substring(quoteStart + 1, quoteEnd))
                        }
                    }
                }
            }
            idx = match + pattern.length
        }
        return null
    }

    fun extractRootDoubleField(json: String, fieldName: String): Double? {
        val pattern = "\"$fieldName\""
        var idx = 0
        while (idx < json.length) {
            val match = json.indexOf(pattern, idx)
            if (match == -1) break
            val prefix = json.substring(0, match)
            val braceDepth = prefix.count { it == '{' } - prefix.count { it == '}' }
            val bracketDepth = prefix.count { it == '[' } - prefix.count { it == ']' }
            if (braceDepth == 1 && bracketDepth == 0) {
                val colonIdx = json.indexOf(':', match + pattern.length)
                if (colonIdx != -1) {
                    val remainder = json.substring(colonIdx + 1).trimStart()
                    val numRegex = Regex("""^([0-9]+(?:\.[0-9]+)?)""")
                    val m = numRegex.find(remainder)
                    if (m != null) {
                        return m.groupValues[1].toDoubleOrNull()
                    }
                }
            }
            idx = match + pattern.length
        }
        return null
    }

    fun extractStringField(json: String, fieldName: String): String? {
        val regex = Regex("""\"$fieldName\"\s*:\s*\"((?:\\\"|[^\"])*)\"""")
        val match = regex.find(json) ?: return null
        return unescapeJsonString(match.groupValues[1])
    }

    fun extractDoubleField(json: String, fieldName: String): Double? {
        val regex = Regex("""\"$fieldName\"\s*:\s*([0-9]+(?:\.[0-9]+)?)""")
        return regex.find(json)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    fun extractBooleanField(json: String, fieldName: String): Boolean? {
        val regex = Regex("""\"$fieldName\"\s*:\s*(true|false)""")
        return regex.find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()
    }

    fun findClosingQuote(str: String, startIdx: Int): Int {
        var i = startIdx
        while (i < str.length) {
            if (str[i] == '"') {
                var backslashes = 0
                var j = i - 1
                while (j >= 0 && str[j] == '\\') {
                    backslashes++
                    j--
                }
                if (backslashes % 2 == 0) {
                    return i
                }
            }
            i++
        }
        return -1
    }

    fun findClosingBracket(str: String, startIdx: Int): Int {
        var depth = 0
        var i = startIdx
        while (i < str.length) {
            when (str[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return i
                }
                '"' -> {
                    val closing = findClosingQuote(str, i + 1)
                    if (closing != -1) i = closing
                }
            }
            i++
        }
        return -1
    }

    private fun parseObservations(json: String): List<VisualObservation> {
        val list = mutableListOf<VisualObservation>()
        val marker = "\"observations\""
        val startIdx = json.indexOf(marker)
        if (startIdx == -1) return list

        val arrayStart = json.indexOf('[', startIdx)
        if (arrayStart == -1) return list
        val arrayEnd = findClosingBracket(json, arrayStart)
        if (arrayEnd == -1) return list

        val arrayContent = json.substring(arrayStart + 1, arrayEnd)
        val objectRegex = Regex("""\{([^{}]+)\}""")
        for (match in objectRegex.findAll(arrayContent)) {
            val obj = match.groupValues[1]
            val desc = extractStringField("{$obj}", "description") ?: continue
            val evidence = extractStringField("{$obj}", "evidence")
            val conf = extractDoubleField("{$obj}", "confidence")
            list.add(VisualObservation(description = desc, evidence = evidence, confidence = conf))
        }
        return list
    }

    private fun parseUiElements(json: String): List<VisibleUiElement> {
        val list = mutableListOf<VisibleUiElement>()
        val marker = "\"relevantUiElements\""
        val startIdx = json.indexOf(marker)
        if (startIdx == -1) return list

        val arrayStart = json.indexOf('[', startIdx)
        if (arrayStart == -1) return list
        val arrayEnd = findClosingBracket(json, arrayStart)
        if (arrayEnd == -1) return list

        val arrayContent = json.substring(arrayStart + 1, arrayEnd)
        val objectRegex = Regex("""\{([^{}]+)\}""")
        for (match in objectRegex.findAll(arrayContent)) {
            val obj = match.groupValues[1]
            val name = extractStringField("{$obj}", "name") ?: continue
            val visible = extractBooleanField("{$obj}", "visible") ?: true
            val evidence = extractStringField("{$obj}", "evidence")
            list.add(VisibleUiElement(name = name, visible = visible, evidence = evidence))
        }
        return list
    }

    private fun parseStringArray(json: String, fieldName: String): List<String> {
        val list = mutableListOf<String>()
        val marker = "\"$fieldName\""
        val startIdx = json.indexOf(marker)
        if (startIdx == -1) return list

        val arrayStart = json.indexOf('[', startIdx)
        if (arrayStart == -1) return list
        val arrayEnd = findClosingBracket(json, arrayStart)
        if (arrayEnd == -1) return list

        val arrayContent = json.substring(arrayStart + 1, arrayEnd)
        val regex = Regex("""\"((?:\\\"|[^\"])*)\"""")
        for (match in regex.findAll(arrayContent)) {
            list.add(unescapeJsonString(match.groupValues[1]))
        }
        return list
    }
}
