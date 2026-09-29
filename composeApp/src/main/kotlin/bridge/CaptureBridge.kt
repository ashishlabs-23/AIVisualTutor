package bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import models.HighlightRegion
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

object CaptureBridge {
    sealed interface Result {
        data class Success(val pngPath: String, val exitCode: Int) : Result

        data class Failure(
            val diagnostic: String,
            val exitCode: Int? = null,
            val stdout: String? = null,
            val stderr: String? = null
        ) : Result
    }

    suspend fun capturePreviousWindow(): Result = withContext(Dispatchers.IO) {
        val bridgePath = resolveBridgeExecutable()
            ?: return@withContext missingBridgeResult()

        invokeBridge(
            bridgePath,
            listOf(
                "--capture-previous-window",
                "--caller-pid",
                ProcessHandle.current().pid().toString()
            )
        )
    }

    suspend fun captureRegion(x: Int, y: Int, width: Int, height: Int): Result = withContext(Dispatchers.IO) {
        if (width <= 0 || height <= 0) {
            return@withContext Result.Failure("Selected region must have a positive width and height.")
        }
        val bridgePath = resolveBridgeExecutable()
            ?: return@withContext missingBridgeResult()

        invokeBridge(
            bridgePath,
            listOf(
                "--capture-region",
                "--x", x.toString(),
                "--y", y.toString(),
                "--width", width.toString(),
                "--height", height.toString()
            )
        )
    }

    suspend fun captureRegion(region: HighlightRegion): Result {
        if (!region.x.isFinite() || !region.y.isFinite() ||
            !region.width.isFinite() || !region.height.isFinite() ||
            region.width <= 0f || region.height <= 0f
        ) {
            return Result.Failure("Selected region must have finite coordinates and a positive width and height.")
        }

        val left = floor(region.x.toDouble())
        val top = floor(region.y.toDouble())
        val right = ceil(region.x.toDouble() + region.width.toDouble())
        val bottom = ceil(region.y.toDouble() + region.height.toDouble())
        if (left < Int.MIN_VALUE || top < Int.MIN_VALUE ||
            right > Int.MAX_VALUE || bottom > Int.MAX_VALUE
        ) {
            return Result.Failure("Selected region coordinates are outside the supported range.")
        }

        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0 || width > Int.MAX_VALUE || height > Int.MAX_VALUE) {
            return Result.Failure("Selected region must have a positive width and height.")
        }

        return captureRegion(left.toInt(), top.toInt(), width.toInt(), height.toInt())
    }

    private fun invokeBridge(bridgePath: File, arguments: List<String>): Result {
        val process = try {
            ProcessBuilder(listOf(bridgePath.absolutePath) + arguments)
                .redirectErrorStream(false)
                .start()
        } catch (exception: Exception) {
            return Result.Failure(
                diagnostic = "Could not start wgc-bridge.exe: ${exception.message}"
            )
        }

        val stdoutFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            process.inputStream.use { readStream(it) }
        }
        val stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            process.errorStream.use { readStream(it) }
        }

        return try {
            val exitCode = process.waitFor()
            val stdout = stdoutFuture.get()
            val stderr = stderrFuture.get()
            val capturedStdout = stdout.trim()
            val validPngPath = capturedStdout
                .takeIf { it.isNotEmpty() }
                ?.takeIf { File(it).isFile }
                ?.takeIf { it.lowercase(Locale.ROOT).endsWith(".png") }
                ?.takeIf(::hasPngSignature)

            if (exitCode == 0 && validPngPath != null) {
                Result.Success(validPngPath, exitCode)
            } else {
                Result.Failure(
                    diagnostic = buildDiagnosticMessage(exitCode, capturedStdout, stderr),
                    exitCode = exitCode,
                    stdout = stdout,
                    stderr = stderr
                )
            }
        } catch (exception: Exception) {
            process.destroyForcibly()
            Result.Failure(
                diagnostic = "Could not complete wgc-bridge.exe: ${exception.message}"
            )
        }
    }

    private fun missingBridgeResult() = Result.Failure(
        diagnostic = "Unable to locate wgc-bridge.exe under project-relative Release/Debug paths."
    )

    private fun resolveBridgeExecutable(): File? {
        val packagedResourceDirectory = System.getProperty("compose.application.resources.dir")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)

        val applicationRoots = linkedSetOf<File>()
        packagedResourceDirectory?.let { applicationRoots += it }
        val codeLocation = try {
            File(CaptureBridge::class.java.protectionDomain.codeSource.location.toURI())
        } catch (_: Exception) {
            null
        }
        codeLocation?.let { location ->
            val base = if (location.isDirectory) location else location.parentFile
            base?.let { applicationRoots += it }
            base?.parentFile?.let { applicationRoots += it }
            base?.parentFile?.parentFile?.let { applicationRoots += it }
        }

        val packagedCandidates = applicationRoots.flatMap { root ->
            listOf(
                File(root, "wgc-bridge.exe"),
                File(root, "wgc-bridge\\wgc-bridge.exe"),
                File(root, "resources\\wgc-bridge\\wgc-bridge.exe"),
                File(root, "libs\\resources\\wgc-bridge\\wgc-bridge.exe")
            )
        }
        packagedCandidates.firstOrNull { it.isFile && it.canExecute() }?.let { return it }

        val searchRoots = linkedSetOf<File>()
        val current = File(System.getProperty("user.dir"))

        if (current.isDirectory) {
            searchRoots += current
        }

        current.parentFile?.let { searchRoots += it }
        current.absoluteFile.parentFile?.let { searchRoots += it }

        val candidates = ArrayList<File>()
        for (root in searchRoots) {
            val probePaths = listOf(
                File(root, "wgc-bridge\\bin\\Release\\net10.0-windows10.0.19041.0\\win-x64\\publish\\wgc-bridge.exe"),
                File(root, "wgc-bridge\\bin\\Release\\net10.0-windows10.0.19041.0\\win-x64\\wgc-bridge.exe"),
                File(root, "wgc-bridge\\bin\\Debug\\net10.0-windows10.0.19041.0\\wgc-bridge.exe"),
                File(root, "wgc-bridge\\wgc-bridge.exe")
            )
            for (candidate in probePaths) {
                if (candidate.isFile && candidate.canExecute()) {
                    candidates += candidate
                }
            }
        }

        return candidates.firstOrNull { it.name.equals("wgc-bridge.exe", ignoreCase = true) }
    }

    private fun buildDiagnosticMessage(exitCode: Int, stdout: String, stderr: String): String {
        return buildString {
            append("Bridge exited with code ")
            append(exitCode)
            if (stdout.isNotBlank()) {
                append(". stdout: ")
                append(stdout.trim())
            }
            if (stderr.isNotBlank()) {
                append(". stderr: ")
                append(stderr.trim())
            }
        }
    }

    private fun readStream(stream: InputStream): String {
        val bytes = stream.readBytes()
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun hasPngSignature(path: String): Boolean = try {
        File(path).inputStream().use { stream ->
            val signature = ByteArray(PNG_SIGNATURE.size)
            stream.read(signature) == signature.size && signature.contentEquals(PNG_SIGNATURE)
        }
    } catch (_: Exception) {
        false
    }

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )
}
