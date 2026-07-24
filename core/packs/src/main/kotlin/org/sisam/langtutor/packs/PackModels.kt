package org.sisam.langtutor.packs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PackKind {
    @SerialName("llm") LLM,
    @SerialName("asr") ASR,
    @SerialName("tts") TTS,
    @SerialName("content") CONTENT,
}

/**
 * One downloadable enhancement pack. The base install is always complete on its
 * own — packs only improve quality (bigger models, more voices, more content).
 */
@Serializable
data class PackDescriptor(
    val id: String,
    val version: Int,
    val nameEn: String,
    val nameHe: String,
    val descriptionEn: String,
    val descriptionHe: String,
    val kind: PackKind,
    val sizeBytes: Long,
    val sha256: String,
    val url: String,
    /**
     * Where the pack's file is written, relative to the install root. For an LLM
     * pack this is the exact path the engine loads (e.g.
     * "models/gemma-4-E4B-it.litertlm"); empty falls back to the URL's basename.
     */
    val installPath: String = "",
    /** Device RAM gate; 0 = no requirement. Checked before offering the pack. */
    val minRamGb: Int = 0,
    val experimental: Boolean = false,
) {
    /** Resolved install path relative to the install root (never empty). */
    val resolvedInstallPath: String
        get() = installPath.ifEmpty { url.substringAfterLast('/').ifEmpty { id } }
}

@Serializable
data class PackCatalog(
    val catalogVersion: Int,
    val packs: List<PackDescriptor>,
)

sealed interface InstallState {
    data object NotInstalled : InstallState
    data class Downloading(val progressPercent: Int) : InstallState
    data object Verifying : InstallState
    data class Installed(val version: Int) : InstallState
    data class Failed(val reason: String) : InstallState
}

data class PackUpdate(
    val packId: String,
    val installedVersion: Int,
    val availableVersion: Int,
)
