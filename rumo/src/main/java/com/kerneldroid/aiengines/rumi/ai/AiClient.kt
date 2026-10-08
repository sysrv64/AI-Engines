// SPDX-License-Identifier: GPL-3.0-or-later
package com.kerneldroid.aiengines.rumi.ai

import android.util.Base64
import com.kerneldroid.aiengines.AppLog
import com.kerneldroid.aiengines.rumi.RumiHttp
import com.kerneldroid.aiengines.rumi.RumiProviders
import com.kerneldroid.aiengines.rumi.RumiStream
import com.kerneldroid.aiengines.rumi.optText
import com.kerneldroid.aiengines.rumi.optTextOr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * What one call to a generation service returned.
 *
 * Three states, not "success/error", because video really can be
 * "not ready yet": that is neither a failure nor a success, and calling it a failure would
 * make the model start over something that is already rendering (and pay a second
 * time).
 */
sealed interface AiOutcome {
    /** Ready bytes: sound, a picture or a downloaded video. */
    class Media(
        val bytes: ByteArray,
        /** The body MIME — it decides the file name and the record kind. */
        val mime: String,
        /** The file name with extension proposed by the service. */
        val fileName: String,
    ) : AiOutcome

    /**
     * The operation is still running. [operation] is its name at the vendor: the next call
     * with this argument will collect the result, not start a new generation.
     */
    class Pending(val operation: String, val waitedMs: Long) : AiOutcome

    /**
     * A failure. [text] is the vendor's own message, not "failed":
     * "the key was not accepted", "no access to the model" and "blocked by the filter"
     * require different actions from the user.
     */
    class Failed(val text: String) : AiOutcome
}

/**
 * Voices and output options confirmed by primary sources.
 *
 * They live here rather than in the tool schema: the schema is built from them, but the
 * request too must be able to substitute a default value when the model did not name one.
 */
internal object AiCaps {
    /** 30 preset Gemini voices (`docs/20` §2.6). */
    val geminiVoices: List<String> = listOf(
        "Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede",
        "Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba",
        "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar",
        "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird",
        "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat",
    )

    /** 13 OpenAI voices (`docs/21` §1b). */
    val openAiVoices: List<String> = listOf(
        "alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx",
        "sage", "shimmer", "verse", "marin", "cedar",
    )

    /** Gemini aspect ratios (`docs/20` §3.4). */
    val geminiAspectRatios: List<String> = listOf(
        "1:1", "1:4", "1:8", "2:3", "3:2", "3:4", "4:1", "4:3", "4:5", "5:4",
        "8:1", "9:16", "16:9", "21:9",
    )

    val geminiImageSizes: List<String> = listOf("1K", "2K", "4K")

    val openAiImageSizes: List<String> = listOf("auto", "1024x1024", "1536x1024", "1024x1536")

    val veoAspectRatios: List<String> = listOf("16:9", "9:16")

    /** Strings, not numbers: in Veo this is a string field (`docs/20` §4.3). */
    val veoDurations: List<String> = listOf("4", "6", "8")

    val veoResolutions: List<String> = listOf("720p", "1080p", "4k")

    /** BytePlus ModelArk (`docs/23` §23.2). */
    val seedanceResolutions: List<String> = listOf("480p", "720p", "1080p")

    val seedanceRatios: List<String> = listOf("16:9", "4:3", "1:1", "3:4", "9:16", "21:9", "adaptive")
}

/**
 * A conversation with one generation service.
 *
 * Protocol only here: assemble the request, read the response, return bytes or
 * an honest failure. Where to put the result is up to the caller — the engine knows
 * what a layer is, and this file does not and should not.
 *
 * Everything below blocks on network I/O and is therefore wrapped in
 * `withContext(Dispatchers.IO)` by [generate].
 */
internal object AiClient {

    /** A picture on disk is a few megabytes; the ceiling is generous but not bottomless. */
    private const val IMAGE_LIMIT_BYTES = 64L * 1024 * 1024

    /** A Veo 3.1 frame at 1080p is tens of megabytes; the ceiling has headroom. */
    private const val VIDEO_LIMIT_BYTES = 512L * 1024 * 1024

    /** How often to poll the Veo operation; the docs give a 10 s step. */
    private const val VEO_POLL_MS = 10_000L
    private const val VEO_DEFAULT_WAIT_S = 120.0

    /**
     * One call: assemble the request by the service's adapter and return the result.
     *
     * `entry` has already been checked for readiness by the caller: only a
     * service with a key and an address gets here, so "the key is not set" is not a branch here.
     */
    suspend fun generate(entry: AiEntry, args: JSONObject): AiOutcome = withContext(Dispatchers.IO) {
        try {
            when (entry.service.adapter) {
                AiAdapter.GeminiSpeech -> geminiSpeech(entry, args)
                AiAdapter.OpenAiSpeech -> openAiSpeech(entry, args)
                AiAdapter.GeminiImage -> geminiImage(entry, args)
                AiAdapter.OpenAiImage -> openAiImage(entry, args)
                AiAdapter.GeminiVideo, AiAdapter.SeedanceVideo -> video(entry, args)
            }
        } catch (t: Throwable) {
            // A transport failure is not silence: the model must learn that the
            // request did not go out, otherwise it will tell of a result that does not exist.
            AppLog.error(TAG, "ai call to ${entry.id} failed: ${AppLog.describe(t)}")
            AiOutcome.Failed("the request to ${entry.label} failed: ${AppLog.describe(t)}")
        }
    }

    /** The service's auth headers — via the shared derivation, not its own branch. */
    private fun headersFor(entry: AiEntry): Map<String, String> =
        RumiProviders.authHeaders(entry.service.authHost(), entry.apiKey)

    // --- Speech ---

    /**
     * Gemini TTS through Interactions.
     *
     * This entry point is chosen, not `generateContent`: it is the current one for speech and pictures,
     * and one `steps` parser serves both kinds, whereas the classic
     * `generateContent` would return a second response format for the same WAV.
     * The voice is an element of the `speech_config` array (in multi-speech it is an object, and
     * confusing them is a silent request failure).
     */
    private fun geminiSpeech(entry: AiEntry, args: JSONObject): AiOutcome {
        val prompt = args.optTextOr("prompt", "").trim()
        if (prompt.isEmpty()) return AiOutcome.Failed("prompt is required: the text to speak")
        val voice = args.optText("voice")?.trim().orEmpty().ifEmpty { "Kore" }
        val style = args.optText("style")?.trim().orEmpty()

        val text = JSONObject().put("type", "text").put("text", prompt)
        if (style.isNotEmpty()) {
            text.put(
                "annotations",
                JSONArray().put(
                    JSONObject().put("type", "speech_metadata").put("style", style),
                ),
            )
        }
        val body = JSONObject()
            .put("model", entry.model)
            .put(
                "input",
                JSONArray().put(
                    JSONObject()
                        .put("type", "user_input")
                        .put("content", JSONArray().put(text)),
                ),
            )
            .put("response_format", JSONObject().put("type", "audio"))
            .put(
                "generation_config",
                JSONObject().put(
                    "speech_config",
                    JSONArray().put(JSONObject().put("voice", voice)),
                ),
            )

        val url = "${entry.baseUrl}/v1beta/interactions"
        val reply = RumiHttp.postJson(url, headersFor(entry), body.toString(), timeoutMs = TTS_TIMEOUT_MS)
        if (!reply.ok) return AiOutcome.Failed(RumiStream.httpReason(reply.status, reply.body))
        val root = jsonOrNull(reply.body)
            ?: return AiOutcome.Failed(notJson(reply, "audio"))
        val block = lastBlock(root, "audio")
            ?: return AiOutcome.Failed(
                "the service answered without audio: there is no `audio` block in `steps`. " +
                    "Nothing was written.",
            )
        val bytes = decodeBase64(block.optText("data"))
            ?: return AiOutcome.Failed("the audio block carried no usable base64 data")
        // A one-shot request returns a complete WAV; older models served
        // headerless L16 — but the format must not be guessed from the response header,
        // so an unknown mime is treated as WAV, as with the current models.
        val mime = block.optText("mime_type") ?: "audio/wav"
        return AiOutcome.Media(bytes, mime, "rumi-speech-${stamp()}.${extFor(mime)}")
    }

    /**
     * OpenAI-compatible speech: the response body is the sound itself.
     *
     * `response_format` is pinned to mp3: the file name and the record MIME depend on
     * it, and letting the model change the format here would mean getting the bytes of
     * one format under the name of another.
     */
    private fun openAiSpeech(entry: AiEntry, args: JSONObject): AiOutcome {
        val prompt = args.optTextOr("prompt", "").trim()
        if (prompt.isEmpty()) return AiOutcome.Failed("prompt is required: the text to speak")
        val voice = args.optText("voice")?.trim().orEmpty().ifEmpty { "marin" }
        val body = JSONObject()
            .put("model", entry.model)
            .put("input", prompt)
            .put("voice", voice)
            .put("response_format", "mp3")
        args.optDouble("speed", Double.NaN).takeIf { !it.isNaN() }?.let { body.put("speed", it) }
        // `instructions` is accepted only by gpt-4o-mini-tts; on other models the
        // service answers with a failure, so the style is silently dropped as an
        // inapplicable field rather than cancelling the whole call.
        val style = args.optText("style")?.trim().orEmpty()
        if (style.isNotEmpty() && entry.model.contains("4o-mini-tts")) body.put("instructions", style)

        val url = "${entry.baseUrl}/audio/speech"
        val reply = RumiHttp.postBytes(url, headersFor(entry), body.toString(), timeoutMs = TTS_TIMEOUT_MS)
        if (!reply.ok) {
            return AiOutcome.Failed(RumiStream.httpReason(reply.status, String(reply.bytes, Charsets.UTF_8)))
        }
        if (reply.tooLarge) {
            return AiOutcome.Failed("the speech was longer than the ${TTS_LIMIT_BYTES / 1024 / 1024} MB cap")
        }
        if (reply.bytes.isEmpty()) {
            return AiOutcome.Failed("the service answered ${reply.status} with an empty body; nothing was written")
        }
        return AiOutcome.Media(reply.bytes, "audio/mpeg", "rumi-speech-${stamp()}.mp3")
    }

    // --- Pictures ---

    /**
     * A Gemini picture through Interactions.
     *
     * Here `input` is a string, not an array of blocks as with speech: that is what the
     * current example shows (`docs/20` §3.2), and this divergence is the vendor's
     * own, not our liberty.
     */
    private fun geminiImage(entry: AiEntry, args: JSONObject): AiOutcome {
        val prompt = args.optTextOr("prompt", "").trim()
        if (prompt.isEmpty()) return AiOutcome.Failed("prompt is required: what to draw")
        val format = JSONObject().put("type", "image")
        args.optText("aspectRatio")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { format.put("aspect_ratio", it) }
        args.optText("imageSize")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { format.put("image_size", it) }
        val body = JSONObject()
            .put("model", entry.model)
            .put("input", prompt)
            .put("response_format", format)

        val url = "${entry.baseUrl}/v1beta/interactions"
        val reply = RumiHttp.postJson(url, headersFor(entry), body.toString(), timeoutMs = IMAGE_TIMEOUT_MS)
        if (!reply.ok) return AiOutcome.Failed(RumiStream.httpReason(reply.status, reply.body))
        val root = jsonOrNull(reply.body) ?: return AiOutcome.Failed(notJson(reply, "image"))
        val block = lastBlock(root, "image")
            ?: return AiOutcome.Failed(
                "the service answered without an image: there is no `image` block in `steps`, " +
                    "and one image per call is what the model is documented to return. Nothing " +
                    "was written.",
            )
        val bytes = decodeBase64(block.optText("data"))
            ?: return AiOutcome.Failed("the image block carried no usable base64 data")
        val mime = block.optText("mime_type") ?: "image/png"
        return AiOutcome.Media(bytes, mime, "rumi-image-${stamp()}.${extFor(mime)}")
    }

    /**
     * An OpenAI-compatible picture.
     *
     * GPT models always serve base64, but older and third-party servers may
     * answer with a link: both cases are supported, because otherwise "a link arrived"
     * would look like "there is no picture".
     */
    private fun openAiImage(entry: AiEntry, args: JSONObject): AiOutcome {
        val prompt = args.optTextOr("prompt", "").trim()
        if (prompt.isEmpty()) return AiOutcome.Failed("prompt is required: what to draw")
        val body = JSONObject()
            .put("model", entry.model)
            .put("prompt", prompt)
            .put("output_format", "png")
        args.optText("size")?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("size", it) }
        args.optText("quality")?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("quality", it) }

        val url = "${entry.baseUrl}/images/generations"
        val reply = RumiHttp.postJson(url, headersFor(entry), body.toString(), timeoutMs = IMAGE_TIMEOUT_MS)
        if (!reply.ok) return AiOutcome.Failed(RumiStream.httpReason(reply.status, reply.body))
        val root = jsonOrNull(reply.body) ?: return AiOutcome.Failed(notJson(reply, "image"))
        val item = root.optJSONArray("data")?.optJSONObject(0)
            ?: return AiOutcome.Failed("the service answered without any image in `data`")
        val inline = decodeBase64(item.optText("b64_json"))
        val bytes = inline ?: item.optText("url")?.let { link ->
            // The link is signed by the service itself, so the key is not
            // sent to it: an auth header to someone else's host is a leak of the
            // key for a file that is already open via the link.
            val fetched = RumiHttp.getBytes(link, emptyMap(), IMAGE_LIMIT_BYTES, IMAGE_TIMEOUT_MS)
            if (!fetched.ok || fetched.bytes.isEmpty()) null else fetched.bytes
        } ?: return AiOutcome.Failed(
            "the service returned no image: neither `b64_json` nor a downloadable `url`",
        )
        return AiOutcome.Media(bytes, "image/png", "rumi-image-${stamp()}.png")
    }

    // --- Video ---

    /**
     * Video: create the job, wait for it, download the file.
     *
     * The wait is inside one call but **bounded**: both Veo and Seedance
     * render for minutes, and a call that waits silently is no better than a failure — it also
     * achieves nothing. Once `waitSeconds` elapses the job name is returned, and
     * the next call with it collects the finished result without starting the render a second time.
     *
     * Both services serve the result **as a link**, not as bytes, and the links live
     * 24 hours (Seedance) and 2 days (Veo). So the download happens right
     * here, not "sometime later": a deferred download means a guaranteed
     * expired link.
     */
    private suspend fun video(entry: AiEntry, args: JSONObject): AiOutcome {
        val headers = headersFor(entry)
        val requested = args.optText("operation")?.trim()
        val operation = if (requested.isNullOrEmpty()) {
            when (val created = createVideoJob(entry, headers, args)) {
                is JobCreated.Failed -> return AiOutcome.Failed(created.text)
                is JobCreated.Started -> created.operation
            }
        } else {
            requested
        }

        val waitMs = (args.optDouble("waitSeconds", VEO_DEFAULT_WAIT_S)
            .takeIf { !it.isNaN() } ?: VEO_DEFAULT_WAIT_S)
            .coerceIn(0.0, VEO_MAX_WAIT_S.toDouble())
            .toLong() * 1000L
        val deadline = System.currentTimeMillis() + waitMs
        while (true) {
            when (val polled = pollVideoJob(entry, headers, operation)) {
                is JobPoll.Failed -> return AiOutcome.Failed(polled.text)
                is JobPoll.Waiting -> {
                    if (System.currentTimeMillis() >= deadline) {
                        return AiOutcome.Pending(operation, waitMs)
                    }
                    delay(VEO_POLL_MS)
                }
                is JobPoll.Ready -> {
                    val fetched = RumiHttp.getBytes(polled.url, headers, VIDEO_LIMIT_BYTES, VIDEO_TIMEOUT_MS)
                    if (fetched.tooLarge) {
                        return AiOutcome.Failed(
                            "the video is larger than the ${VIDEO_LIMIT_BYTES / 1024 / 1024} MB " +
                                "cap, so it was not downloaded",
                        )
                    }
                    if (!fetched.ok || fetched.bytes.isEmpty()) {
                        return AiOutcome.Failed(
                            "the video was produced but could not be downloaded " +
                                "(HTTP ${fetched.status}); the provider's link may have expired",
                        )
                    }
                    return AiOutcome.Media(fetched.bytes, "video/mp4", "rumi-video-${stamp()}.mp4")
                }
            }
        }
    }

    /** The result of creating the job: a name to poll or an honest failure. */
    private sealed interface JobCreated {
        class Started(val operation: String) : JobCreated
        class Failed(val text: String) : JobCreated
    }

    /** The state of one poll. */
    private sealed interface JobPoll {
        class Waiting : JobPoll
        class Ready(val url: String) : JobPoll
        class Failed(val text: String) : JobPoll
    }

    /**
     * Create the render job: in Veo it is the `predictLongRunning` operation, in
     * Seedance a record in `contents/generations/tasks`.
     *
     * The parameters are named differently in the two services (`aspectRatio` versus
     * `ratio`, the strings `"4"`/`"6"`/`"8"` versus whole seconds), and this
     * is the vendors' difference, not our sloppiness: trying to reduce them to one
     * name would break both requests. The tool schema offers both sets, and
     * here each adapter reads only its own fields.
     */
    private suspend fun createVideoJob(
        entry: AiEntry,
        headers: Map<String, String>,
        args: JSONObject,
    ): JobCreated {
        val prompt = args.optTextOr("prompt", "").trim()
        if (prompt.isEmpty()) {
            return JobCreated.Failed(
                "prompt is required to start a video; pass `operation` only to collect a job " +
                    "that an earlier call started",
            )
        }
        return when (entry.service.adapter) {
            AiAdapter.GeminiVideo -> {
                val parameters = JSONObject()
                args.optText("aspectRatio")?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { parameters.put("aspectRatio", it) }
                args.optText("durationSeconds")?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { parameters.put("durationSeconds", it) }
                args.optText("resolution")?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { parameters.put("resolution", it) }
                val body = JSONObject()
                    .put("instances", JSONArray().put(JSONObject().put("prompt", prompt)))
                    .put("parameters", parameters)
                val url = "${entry.baseUrl}/v1beta/models/${entry.model}:predictLongRunning"
                val reply = RumiHttp.postJson(url, headers, body.toString(), timeoutMs = 60_000)
                if (!reply.ok) return JobCreated.Failed(RumiStream.httpReason(reply.status, reply.body))
                val root = jsonOrNull(reply.body) ?: return JobCreated.Failed(notJson(reply, "video job"))
                root.optText("name")?.let { return JobCreated.Started(it) }
                JobCreated.Failed(
                    "the service did not start a video job" + providerDetail(root).suffix(),
                )
            }
            AiAdapter.SeedanceVideo -> {
                val body = JSONObject()
                    .put("model", entry.model)
                    .put(
                        "content",
                        JSONArray().put(JSONObject().put("type", "text").put("text", prompt)),
                    )
                args.optText("resolution")?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { body.put("resolution", it) }
                args.optText("ratio")?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { body.put("ratio", it) }
                args.optDouble("duration", Double.NaN).takeIf { !it.isNaN() }
                    ?.let { body.put("duration", it.toInt()) }
                args.optDouble("seed", Double.NaN).takeIf { !it.isNaN() }
                    ?.let { body.put("seed", it.toInt()) }
                // `camera_fixed` is NOT sent: it exists only in Seedance 1.5
                // pro and 1.0 pro, while in the 2.x line an extra field is a failure, not
                // "they will ignore it" (`docs/23` §23.2).
                val url = "${entry.baseUrl}/contents/generations/tasks"
                val reply = RumiHttp.postJson(url, headers, body.toString(), timeoutMs = 60_000)
                if (!reply.ok) return JobCreated.Failed(RumiStream.httpReason(reply.status, reply.body))
                val root = jsonOrNull(reply.body) ?: return JobCreated.Failed(notJson(reply, "video job"))
                // The exact response shape for creating a job did not render in the
                // documentation, so the id is looked up under both plausible
                // names rather than guessed: an unknown shape is more honestly shown
                // than polled at an invented path.
                val id = root.optText("id") ?: root.optText("task_id")
                if (id != null) {
                    JobCreated.Started(id)
                } else {
                    JobCreated.Failed(
                        "the service accepted the video task but the reply carried no task id" +
                            providerDetail(root).suffix() + "; nothing was rendered by this call",
                    )
                }
            }
            else -> JobCreated.Failed("this service cannot render video")
        }
    }

    /**
     * One poll of the job. `done` in Veo versus `status` in Seedance are different
     * shapes of the same question, and parsing them in one place means getting an
     * error that shows in only one of the two services.
     */
    private suspend fun pollVideoJob(
        entry: AiEntry,
        headers: Map<String, String>,
        operation: String,
    ): JobPoll {
        val url = when (entry.service.adapter) {
            AiAdapter.SeedanceVideo -> "${entry.baseUrl}/contents/generations/tasks/$operation"
            else -> "${entry.baseUrl}/$operation"
        }
        val status = RumiHttp.getJson(url, headers, timeoutMs = 60_000)
        if (!status.ok) return JobPoll.Failed(RumiStream.httpReason(status.status, status.body))
        val root = jsonOrNull(status.body) ?: return JobPoll.Failed(notJson(status, "video job"))
        return when (entry.service.adapter) {
            AiAdapter.GeminiVideo -> {
                root.optJSONObject("error")?.let { error ->
                    return JobPoll.Failed("the video job failed: ${trim(error.optText("message") ?: error.toString())}")
                }
                if (!root.optBoolean("done", false)) {
                    JobPoll.Waiting()
                } else {
                    videoUriOf(root)?.let { JobPoll.Ready(it) } ?: JobPoll.Failed(
                        "the video job finished without a video uri — the request was most " +
                            "likely filtered. The service said: " + trim(root.toString()),
                    )
                }
            }
            AiAdapter.SeedanceVideo -> when (val state = root.optText("status")) {
                "succeeded" -> root.optJSONObject("content")?.optText("video_url")
                    ?.let { JobPoll.Ready(it) }
                    ?: JobPoll.Failed(
                        "the video job succeeded without `content.video_url`: " + trim(root.toString()),
                    )
                "failed" -> JobPoll.Failed(
                    "the video job failed" + providerDetail(root).suffix(),
                )
                "expired" -> JobPoll.Failed(
                    "the video job expired before it was collected; it has to be started again",
                )
                "cancelled" -> JobPoll.Failed("the video job was cancelled")
                // `queued` and `running` mean still going; an unknown status reads
                // the same way, because waiting until the deadline is cheaper than declaring
                // a failure for work that is actually running.
                else -> JobPoll.Waiting().also {
                    if (state == null) AppLog.warn(TAG, "seedance task $operation has no status")
                }
            }
            else -> JobPoll.Failed("this service cannot render video")
        }
    }

    // --- Small helpers ---

    private const val TAG = "ai-client"
    private const val TTS_TIMEOUT_MS = 240_000
    private const val IMAGE_TIMEOUT_MS = 300_000
    private const val VIDEO_TIMEOUT_MS = 300_000
    private const val TTS_LIMIT_BYTES = 64L * 1024 * 1024

    /** The Veo wait ceiling: per the docs it does not render for longer than six minutes either. */
    private const val VEO_MAX_WAIT_S = 300.0

    private fun stamp(): Long = System.currentTimeMillis()

    private fun jsonOrNull(body: String): JSONObject? =
        runCatching { JSONObject(body) }.getOrNull()

    private fun notJson(reply: RumiHttp.Reply, what: String): String =
        "the service answered ${reply.status} with something that is not JSON, so there is no " +
            "$what: ${trim(reply.body)}"

    private fun decodeBase64(data: String?): ByteArray? {
        if (data.isNullOrEmpty()) return null
        return runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * The last block of the required type across all `steps[].content[]`.
     *
     * By traversal, not a fixed index: a response can have intermediate
     * text blocks, and `parts[0]` from the classic API does not work here.
     * The last one specifically: when text and pictures alternate, we are interested in the
     * result, not the reasoning about it.
     */
    private fun lastBlock(root: JSONObject, type: String): JSONObject? {
        val steps = root.optJSONArray("steps") ?: return null
        var found: JSONObject? = null
        for (i in 0 until steps.length()) {
            val content = steps.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val block = content.optJSONObject(j) ?: continue
                if (block.optString("type") == type) found = block
            }
        }
        return found
    }

    /** `.response.generateVideoResponse.generatedSamples[0].video.uri`. */
    private fun videoUriOf(root: JSONObject): String? =
        root.optJSONObject("response")
            ?.optJSONObject("generateVideoResponse")
            ?.optJSONArray("generatedSamples")
            ?.optJSONObject(0)
            ?.optJSONObject("video")
            ?.optText("uri")

    /**
     * A message from a body with code 200: in Gemini an error sometimes arrives together with a
     * successful status (quota, region, access to the model).
     */
    private fun providerDetail(root: JSONObject): String {
        root.optJSONObject("error")?.let { error ->
            error.optText("message")?.let { return trim(it) }
        }
        root.optText("error")?.let { return trim(it) }
        root.optText("message")?.let { return trim(it) }
        return ""
    }

    private fun trim(text: String): String =
        text.replace('\n', ' ').let { if (it.length <= 400) it else it.take(400) + "…" }

    /** Append the detail to the failure if there is one: `: <detail>` or empty. */
    private fun String.suffix(): String = if (isEmpty()) "" else ": $this"

    private fun extFor(mime: String): String = when {
        mime.contains("wav") -> "wav"
        mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
        mime.contains("mp4") -> "mp4"
        mime.contains("webp") -> "webp"
        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
        else -> "png"
    }
}
