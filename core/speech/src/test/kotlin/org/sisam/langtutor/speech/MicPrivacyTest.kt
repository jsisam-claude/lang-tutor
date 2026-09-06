package org.sisam.langtutor.speech

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate on one promise: nothing the microphone hears is ever written down
 * (docs/privacy.md).
 *
 * The app is offline, so no transcript can leave the device over a network —
 * but logcat is readable by adb, survives the app, and is the one place a
 * spoken sentence can escape without anyone meaning it to. This test was
 * written after finding exactly that: `Log.i(TAG, "stream preview ended:
 * \"$it\"")` in the ASR engine, printing every child's sentence verbatim.
 *
 * Two rules, both checked against the source rather than the runtime, because
 * the failure mode is a line nobody thought about rather than a code path
 * nobody takes.
 */
class MicPrivacyTest {

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    /** Every production Kotlin file in the repo, tests excluded. */
    private val sources: List<File> = root.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filter { it.path.contains("/src/main/") }
        .toList()

    /**
     * Words that name something the recogniser produced. A log that prints a
     * variable called any of these is printing what was said.
     */
    private val speechNames = listOf(
        "transcript", "utterance", "heard", "spoken", "recognised", "recognized",
        "hypothesis", "asrtext", "saidtext", "partialtext",
    )

    @Test
    fun `no log anywhere prints a variable that holds what was said`() {
        val offenders = mutableListOf<String>()
        for (file in sources) {
            for (call in logCalls(file.readText())) {
                for (expr in interpolations(call)) {
                    val name = expr.lowercase()
                    if (speechNames.any { it in name }) {
                        offenders += "${file.toRelativeString(root)}: \$$expr"
                    }
                }
            }
        }
        assertTrue("a log prints recognised speech: $offenders", offenders.isEmpty())
    }

    @Test
    fun `no log in a file that touches the microphone prints an unnamed value`() {
        // `$it` is the trap. In a file that runs the recogniser, the value a
        // lambda just produced is very often the sentence it just decoded, and
        // an interpolation with no name is an interpolation nobody audited.
        // `${it.message}` and `${it.size}` are fine — they name what they take.
        val offenders = mutableListOf<String>()
        for (file in sources.filter { touchesTheMic(it.readText()) }) {
            for (call in logCalls(file.readText())) {
                for (expr in interpolations(call)) {
                    if (expr.trim() in setOf("it", "this")) {
                        offenders += "${file.toRelativeString(root)}: \$$expr in `$call`"
                    }
                }
            }
        }
        assertTrue("a mic-side log prints an unnamed value: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the scanner actually finds the calls it claims to check`() {
        // A source scan that silently matches nothing passes forever. These
        // are the files the two rules above exist for.
        val mic = sources.filter { touchesTheMic(it.readText()) }.map { it.name }
        assertTrue("no mic-side file found — the detector is broken: $mic", mic.size >= 3)
        val calls = sources.sumOf { logCalls(it.readText()).size }
        assertTrue("no log calls found — the parser is broken", calls >= 50)
    }

    /** A file that can see raw audio or a decoded sentence. */
    private fun touchesTheMic(source: String): Boolean =
        listOf("AudioRecord", "AsrResult", "AudioClip", "fun transcribe").any { it in source }

    /**
     * The argument text of every `Log.x(...)` / `println(...)` call, brace-
     * matched so a multi-line call is read whole. String literals are skipped
     * so a bracket inside a message does not end the call early.
     */
    private fun logCalls(source: String): List<String> {
        val out = mutableListOf<String>()
        for (m in Regex("""\b(?:Log\.[vdiwe]|println)\(""").findAll(source)) {
            val start = m.range.last + 1
            var i = start
            var depth = 1
            var inString = false
            var inRaw = false
            var escape = false
            while (i < source.length && depth > 0) {
                val c = source[i]
                when {
                    escape -> escape = false
                    inRaw -> if (source.startsWith("\"\"\"", i)) { inRaw = false; i += 2 }
                    inString -> when (c) {
                        '\\' -> escape = true
                        '"' -> inString = false
                    }
                    source.startsWith("\"\"\"", i) -> { inRaw = true; i += 2 }
                    c == '"' -> inString = true
                    c == '(' -> depth++
                    c == ')' -> depth--
                }
                i++
            }
            out += source.substring(start, (i - 1).coerceAtLeast(start))
        }
        return out
    }

    /** The expression inside each `$name` and `${expr}` of a call's arguments. */
    private fun interpolations(call: String): List<String> =
        Regex("""\$\{([^{}]*)}|\$([A-Za-z_][A-Za-z0-9_]*)""").findAll(call)
            .map { (it.groupValues[1] + it.groupValues[2]).trim() }
            .filter { it.isNotEmpty() }
            .toList()
}
