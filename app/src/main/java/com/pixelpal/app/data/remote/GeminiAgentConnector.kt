package com.pixelpal.app.data.remote

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GenerateContentResponse
import com.google.ai.client.generativeai.type.content
import com.pixelpal.app.domain.model.AgentCheckResult
import com.pixelpal.app.domain.model.AgentState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Direct Google Gemini AI integration for conversational intelligence,
 * real-time token streaming, and dynamic companion personality reactions.
 */
@Singleton
class GeminiAgentConnector @Inject constructor() : AgentConnector {

    private var generativeModel: GenerativeModel? = null
    private var activeKey: String = ""

    init {
        val defaultKey = com.pixelpal.app.BuildConfig.GEMINI_API_KEY
        if (isUsableKey(defaultKey)) {
            initialize(defaultKey)
        }
    }

    /** True when a real (non-placeholder) key is active. */
    fun isConfigured(): Boolean = generativeModel != null && isUsableKey(activeKey)

    companion object {
        /** Default model, referenced by the local status message. */
        private const val MODEL_NAME = "gemini-1.5-flash"

        /** Gradle injects "" or the literal placeholder when no key is set. */
        fun isUsableKey(key: String): Boolean {
            if (key.isBlank()) return false
            val t = key.trim()
            if (t.length < 20) return false
            val u = t.uppercase()
            return !(u.contains("YOUR_") || u.contains("PLACEHOLDER") || u.contains("REPLACE") ||
                u == "YOUR_GEMINI_API_KEY_HERE")
        }
    }

    /**
     * Initializes the Gemini Generative Model with the provided API key and model type.
     */
    fun initialize(apiKey: String, modelName: String = MODEL_NAME) {
        if (!isUsableKey(apiKey)) {
            generativeModel = null
            activeKey = ""
            return
        }
        activeKey = apiKey.trim()
        generativeModel = GenerativeModel(
            modelName = modelName,
            apiKey = activeKey
        )
    }

    /** Clears the in-memory key (e.g. on logout / key removal). */
    fun clear() {
        generativeModel = null
        activeKey = ""
    }

    /**
     * Standard status check adhering to [AgentConnector].
     */
    /**
     * Gemini "availability" check — deliberately LOCAL and FREE.
     *
     * This used to call `generateContent` on every periodic poll, which spent
     * real API tokens/quota to ask "are you alive?" every 15 minutes, forever.
     * That is pure waste: Gemini is a chat provider, not a polling agent, so
     * its status is simply "configured or not".
     *
     * A real round-trip happens only when the user explicitly asks for one
     * (see [verifyNow]); periodic polling never bills.
     */
    override suspend fun checkNow(endpointUrl: String): AgentCheckResult {
        return if (generativeModel != null) {
            AgentCheckResult(
                state = AgentState.ONLINE,
                message = "Gemini ready — $MODEL_NAME"
            )
        } else {
            AgentCheckResult(
                AgentState.DISCONNECTED,
                "Gemini API key not configured — add one in AI Agent settings"
            )
        }
    }

    /**
     * Explicit, user-initiated live check. Costs one (tiny) API call, so it is
     * never scheduled — only called from "Check Now".
     */
    suspend fun verifyNow(): AgentCheckResult {
        val model = generativeModel
            ?: return AgentCheckResult(
                AgentState.DISCONNECTED,
                "Gemini API key not configured — add one in AI Agent settings"
            )
        return withContext(Dispatchers.IO) {
            try {
                val response = model.generateContent("Reply with the single word: ok")
                val text = response.text?.trim().orEmpty()
                AgentCheckResult(
                    state = AgentState.ONLINE,
                    message = text.ifBlank { "Gemini reachable" }
                )
            } catch (e: Exception) {
                Timber.e(e, "Gemini verification failed")
                AgentCheckResult(AgentState.ERROR, e.localizedMessage ?: "Gemini API error")
            }
        }
    }

    /**
     * Streams real-time token chunks from Gemini for interactive typewriter responses.
     */
    fun streamChatResponse(
        prompt: String,
        companionName: String,
        personality: String,
        bondLevel: Int
    ): Flow<String> = flow {
        val model = generativeModel
        if (model == null) {
            emit("Error: Gemini is not initialized. Please configure your API key.")
            return@flow
        }

        try {
            val systemInstruction = """
                You are $companionName, a loyal virtual companion with a $personality personality.
                Bond Level: $bondLevel.
                Respond in character. Keep responses concise, warm, helpful, and under 3 sentences.
            """.trimIndent()

            val fullPrompt = "$systemInstruction\n\nUser: $prompt\n$companionName:"
            val responseStream = model.generateContentStream(fullPrompt)
            responseStream.collect { chunk: GenerateContentResponse ->
                chunk.text?.let { emit(it) }
            }
        } catch (e: Exception) {
            Timber.e(e, "Gemini stream error")
            emit("\n[Connection error: ${e.localizedMessage ?: "Unknown error"}]")
        }
    }.flowOn(Dispatchers.IO)

    /**
     * One-shot reply used by "Talk to your agent" and reaction fallbacks.
     * Returns the text or null when unconfigured/failed (caller falls back).
     */
    suspend fun generateReply(
        prompt: String,
        companionName: String = "PixelPal",
        personality: String = "friendly",
        bondLevel: Int = 0
    ): String? {
        val model = generativeModel ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val systemInstruction = """
                    You are $companionName, a loyal virtual companion with a $personality personality.
                    Bond Level: $bondLevel.
                    Respond in character. Keep responses concise, warm, helpful, and under 3 sentences.
                """.trimIndent()
                val response = model.generateContent("$systemInstruction\n\nUser: $prompt\n$companionName:")
                response.text?.trim()?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                Timber.e(e, "Gemini reply failed")
                null
            }
        }
    }
}
