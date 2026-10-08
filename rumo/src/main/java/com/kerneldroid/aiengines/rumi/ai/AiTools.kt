// SPDX-License-Identifier: GPL-3.0-or-later
package com.kerneldroid.aiengines.rumi.ai

import com.kerneldroid.aiengines.rumi.Prop
import com.kerneldroid.aiengines.rumi.RumiToolSpec
import com.kerneldroid.aiengines.rumi.num
import com.kerneldroid.aiengines.rumi.obj
import com.kerneldroid.aiengines.rumi.oneOf
import com.kerneldroid.aiengines.rumi.opt
import com.kerneldroid.aiengines.rumi.req
import com.kerneldroid.aiengines.rumi.str
import org.json.JSONObject

/**
 * The generating tools, built from whatever is actually configured.
 *
 * ## Why the schemas are built every time instead of sitting in a list
 *
 * A tool exists exactly when the service has a key — just as
 * `web_search` exists exactly when `searchReady` does. The service list and their
 * readiness change from settings, so [activeSpecs] is called on every model
 * request rather than once when the tool list is built: the user enters a key and
 * returns to the conversation, and this has to work without a new conversation.
 *
 * ## Why the call settings are in the schema, not in the screen settings
 *
 * Duration, voice, aspect ratio are properties of one call, not of the service:
 * two clips in a row want different lengths. The settings screen dictates *what*
 * can be used; the schema tells the model *what it may choose* — and is built from
 * the capabilities of the ready services, so Veo has `aspectRatio` and
 * `durationSeconds` in it, while TTS has voice and rate.
 *
 * ## What is honestly left unsaid here
 *
 * The schema offers only the fields that at least one of the ready *built-in*
 * services would accept. A custom service with its own field set cannot be
 * described, and promising `imageSize` to a server that does not know it would
 * mean a refusal out of nowhere; a service with no enumerable voices still gets a
 * voice offered as a string rather than an enum.
 */
object AiTools {

    /** The tool name for a kind. One place, so the name in the schema and in `call` do not diverge. */
    fun toolName(kind: AiKind): String = when (kind) {
        AiKind.Speech -> "speak"
        AiKind.Sound -> "generate_sound"
        AiKind.Image -> "generate_image"
        AiKind.Video -> "generate_video"
    }

    /**
     * Tools for every kind that has at least one ready service.
     *
     * A kind with no ready service does not appear at all — not as a refusal, but as
     * an absence: a tool that always answers "no key set" wastes a turn and promises
     * a capability the user does not have.
     */
    fun activeSpecs(state: AiServices.State, only: Set<String>?): List<RumiToolSpec> =
        AiKind.entries.mapNotNull { kind ->
            val ready = state.readyOf(kind)
            if (ready.isEmpty()) null else specFor(kind, ready)
        }.filter { only == null || it.name in only }

    /**
     * The ready service named by the `service` argument.
     *
     * An empty or missing argument reads as "the first ready one", so a call with a
     * single service does not have to name it. A name that is not among the ready
     * ones does not get the first one substituted: silently generating with another
     * service is not what was asked for, and the user would see a bill from the wrong
     * service.
     */
    fun resolve(state: AiServices.State, kind: AiKind, wanted: String?): AiEntry? {
        val ready = state.readyOf(kind)
        return if (wanted.isNullOrBlank()) ready.firstOrNull() else ready.firstOrNull { it.id == wanted }
    }

    /** One service call. The protocol is in [AiClient]; here only the call boundary. */
    suspend fun run(entry: AiEntry, args: JSONObject): AiOutcome = AiClient.generate(entry, args)

    // --- Schemas ---

    private fun specFor(kind: AiKind, ready: List<AiEntry>): RumiToolSpec = RumiToolSpec(
        name = toolName(kind),
        description = descriptionFor(kind, ready),
        schemaJson = schemaFor(kind, ready).toString(),
    )

    private fun schemaFor(kind: AiKind, ready: List<AiEntry>): JSONObject = when (kind) {
        AiKind.Speech -> speechSchema(ready)
        AiKind.Sound -> soundSchema(ready)
        AiKind.Image -> imageSchema(ready)
        AiKind.Video -> videoSchema(ready)
    }

    private fun descriptionFor(kind: AiKind, ready: List<AiEntry>): String {
        val summary = when (kind) {
            AiKind.Speech -> "Speak text aloud and add it to the timeline as an audio layer. " +
                "The audio is written into the project's media folder " +
                "(Download/Rumo/<project>/) and attached so it plays with the timeline. " +
                "Generation is billed by the provider and counts against its quota."
            AiKind.Sound -> "Create a sound effect and add it to the timeline as an audio " +
                "layer. Nothing is built in for this: the kind works only through a service " +
                "you configured, and the field list below is what such a service is expected " +
                "to take."
            AiKind.Image -> "Draw a picture and add it to the timeline as an image layer. " +
                "The file is written into the project's media folder " +
                "(Download/Rumo/<project>/) and staged, so it shows in the frame immediately. " +
                "Generation is billed by the provider. Note that Gemini image models have no " +
                "free tier: a key on a project without billing will be refused."
            AiKind.Video -> "Render a short clip from a prompt and add it to the timeline as " +
                "a video layer. Rendering is asynchronous — the provider took 11 s to about " +
                "6 minutes in its own documentation — so this call waits a bounded time and, " +
                "if the job is not finished, answers with its `operation` name instead of " +
                "failing: call again with `operation` to collect it, which never starts a " +
                "second render. The finished file is downloaded into the project's media " +
                "folder (Download/Rumo/<project>/). Paid tier only."
        }
        val services = ready.joinToString("\n") { "  - `${it.id}`: ${it.label}, model `${it.model}`" }
        return "$summary\n\nServices you may pass as `service`:\n$services"
    }

    private fun serviceProp(ready: List<AiEntry>): Prop = req(
        "service",
        oneOf(
            "Which configured service to use. Only services that are both switched on and " +
                "given a key are listed.",
            *ready.map { it.id }.toTypedArray(),
        ),
    )

    private fun nameProp(kind: AiKind): Prop = opt(
        "name",
        str(
            "File name inside the project's media folder; the extension is added from the " +
                "format that comes back. Defaults to a timestamped " +
                "`rumi-${
                    when (kind) {
                        AiKind.Speech -> "speech"
                        AiKind.Sound -> "sound"
                        AiKind.Image -> "image"
                        AiKind.Video -> "video"
                    }
                }-…`.",
        ),
    )

    private fun speechSchema(ready: List<AiEntry>): JSONObject {
        val adapters = adaptersOf(ready)
        val props = mutableListOf(
            serviceProp(ready),
            req(
                "prompt",
                str("The text to speak, verbatim. It is not rewritten, translated or summarised."),
            ),
            opt("voice", voiceProp(ready)),
            nameProp(AiKind.Speech),
        )
        if (AiAdapter.GeminiSpeech in adapters || AiAdapter.OpenAiSpeech in adapters) {
            props += opt(
                "style",
                str(
                    "How it should sound, e.g. \"cheerful and friendly\". Gemini reads it as " +
                        "`speech_metadata.style`; OpenAI sends it as `instructions`, which " +
                        "only `gpt-4o-mini-tts` accepts and other models silently drop.",
                ),
            )
        }
        if (AiAdapter.OpenAiSpeech in adapters) {
            props += opt(
                "speed",
                num("Speaking rate, 0.25–4.0 (OpenAI). Default 1.0.", 0.25, 4.0),
            )
        }
        return obj(*props.toTypedArray())
    }

    private fun soundSchema(ready: List<AiEntry>): JSONObject = obj(
        serviceProp(ready),
        req(
            "prompt",
            str("What the sound is — \"a wooden door creaking open\", not an instruction about the file."),
        ),
        opt(
            "durationSeconds",
            num("How long the effect should run, in seconds.", 1.0, 22.0),
        ),
        nameProp(AiKind.Sound),
    )

    private fun imageSchema(ready: List<AiEntry>): JSONObject {
        val adapters = adaptersOf(ready)
        val props = mutableListOf(
            serviceProp(ready),
            req("prompt", str("What to draw. Describe the picture, not the file.")),
            nameProp(AiKind.Image),
        )
        if (AiAdapter.GeminiImage in adapters) {
            props += opt(
                "aspectRatio",
                oneOf("Canvas ratio (Gemini).", *AiCaps.geminiAspectRatios.toTypedArray()),
            )
            props += opt(
                "imageSize",
                oneOf("Output resolution (Gemini). Default 1K.", *AiCaps.geminiImageSizes.toTypedArray()),
            )
        }
        if (AiAdapter.OpenAiImage in adapters) {
            props += opt(
                "size",
                oneOf("Output size (OpenAI). Default auto.", *AiCaps.openAiImageSizes.toTypedArray()),
            )
            props += opt(
                "quality",
                oneOf("Rendering effort (OpenAI). Default auto.", "auto", "low", "medium", "high"),
            )
        }
        return obj(*props.toTypedArray())
    }

    /**
     * Video: fields per adapter.
     *
     * Veo and Seedance do not accept the same names (`aspectRatio` versus
     * `ratio`, the string `"4"`/`"6"`/`"8"` versus integer seconds), so the names
     * differ in the schema too — reducing them to one would break both requests. The
     * only shared name is `resolution`, but their value sets differ (`4k` exists only
     * for Veo): when both services are ready, an `enum` built from the union would
     * offer half the services a foreign value, and the field becomes a string with
     * both lists in the description.
     */
    private fun videoSchema(ready: List<AiEntry>): JSONObject {
        val adapters = adaptersOf(ready)
        val props = mutableListOf(
            serviceProp(ready),
            // `prompt` is not required: a call with `operation` only fetches an
            // already-started job, and requiring a prompt there would make the model
            // invent it again for the same frame.
            opt(
                "prompt",
                str("What happens in the clip. Required unless you pass `operation`."),
            ),
        )
        if (AiAdapter.GeminiVideo in adapters) {
            props += opt(
                "aspectRatio",
                oneOf("Frame ratio (Veo). Default 16:9.", *AiCaps.veoAspectRatios.toTypedArray()),
            )
            props += opt(
                "durationSeconds",
                oneOf(
                    "Clip length in seconds (Veo) — a string, not a number. 8 is required " +
                        "with 1080p or 4k.",
                    *AiCaps.veoDurations.toTypedArray(),
                ),
            )
        }
        if (AiAdapter.SeedanceVideo in adapters) {
            props += opt(
                "ratio",
                oneOf(
                    "Frame ratio (Seedance); `adaptive` lets the model pick.",
                    *AiCaps.seedanceRatios.toTypedArray(),
                ),
            )
            props += opt(
                "duration",
                num(
                    "Clip length in whole seconds (Seedance); -1 lets the model decide.",
                    -1.0,
                    null,
                ),
            )
            props += opt(
                "seed",
                num(
                    "Random seed (Seedance); -1, the default, means the provider picks one.",
                    -1.0,
                    null,
                ),
            )
        }
        props += videoResolutionProp(adapters)
        props += opt(
            "waitSeconds",
            num(
                "How long to wait for the render inside this call before answering with the " +
                    "job name so a later call can collect it. Default 120, cap 300.",
                0.0,
                300.0,
            ),
        )
        props += opt(
            "operation",
            str(
                "A job name from an earlier reply. Pass it to collect that job instead of " +
                    "starting a new render — this never starts a second one. Pass the same " +
                    "`service` it came from.",
            ),
        )
        props += nameProp(AiKind.Video)
        return obj(*props.toTypedArray())
    }

    private fun videoResolutionProp(adapters: Set<AiAdapter>): Prop {
        val veo = AiAdapter.GeminiVideo in adapters
        val seedance = AiAdapter.SeedanceVideo in adapters
        return when {
            veo && seedance -> opt(
                "resolution",
                str(
                    "Output resolution. Veo accepts ${AiCaps.veoResolutions.joinToString(", ")}; " +
                        "Seedance accepts ${AiCaps.seedanceResolutions.joinToString(", ")}. Omit " +
                        "for the provider's default.",
                ),
            )
            seedance -> opt(
                "resolution",
                oneOf("Output resolution (Seedance). Default 720p.", *AiCaps.seedanceResolutions.toTypedArray()),
            )
            else -> opt(
                "resolution",
                oneOf("Output resolution (Veo). Default 720p.", *AiCaps.veoResolutions.toTypedArray()),
            )
        }
    }

    // --- Schema helpers ---

    /** Adapters of built-in services only: a custom one's field set is unknown. */
    private fun adaptersOf(ready: List<AiEntry>): Set<AiAdapter> =
        ready.filter { !it.custom }.map { it.service.adapter }.toSet()

    /**
     * Voice: an enum when all ready services are built-in, and a string when one of
     * them is custom.
     *
     * A custom service takes its own voice names, which we do not know, and an
     * `enum` of foreign values would reject a valid call. A string with the list in
     * the description leaves the choice to the model without forbidding anything.
     */
    private fun voiceProp(ready: List<AiEntry>): JSONObject {
        val builtIn = ready.filter { !it.custom }
        val names = builtIn.map { voicesOf(it.service) }.flatten().distinct()
        val enumerable = builtIn.size == ready.size &&
            builtIn.isNotEmpty() &&
            builtIn.none { voicesOf(it.service).isEmpty() }
        if (enumerable) {
            return oneOf(
                "Voice name. The service you choose accepts one of these.",
                *names.toTypedArray(),
            )
        }
        val known = if (names.isEmpty()) "" else "Built-in services accept one of: ${names.joinToString(", ")}. "
        return str(
            "Voice name. $known A service you added yourself takes whatever voice names it " +
                "defines; an unknown name is the provider's to accept or refuse.",
        )
    }

    private fun voicesOf(service: AiService): List<String> = when (service.adapter) {
        AiAdapter.GeminiSpeech -> AiCaps.geminiVoices
        AiAdapter.OpenAiSpeech -> AiCaps.openAiVoices
        else -> emptyList()
    }
}
