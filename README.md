# AI-Engines

Engines that talk to models, one directory per project. Nothing here runs on its
own: each engine is a library that a host application consumes.

| Directory | What it is |
| --- | --- |
| `rumo/` | The assistant for the Rumo video editor — providers, models, streaming, the conversation, its chat UI, and the generative media services |

The repository is a container rather than a single Gradle build, because the
engines belong to different projects and share no code. A second engine will be
a second directory, not a second branch.

## `rumo/`

The Rumo assistant, `com.kerneldroid.aiengines.rumi`.

It is a plain Gradle library module. The host application consumes it by
including it as a project whose sources live here:

```kotlin
// in Rumo's settings.gradle.kts
include(":ai-engines")
project(":ai-engines").projectDir = file("ai-engines/rumo")
```

`ai-engines/` is this repository, mounted as a git submodule, so `rumo/` is
`ai-engines/rumo` from the host's point of view. Editing the engine means
editing this repository; the host picks the change up on its next checkout.

### What it needs from its host

One interface, `com.kerneldroid.aiengines.RumiHost`:

```kotlin
val context: Context
fun activeToolSpecs(): List<RumiToolSpec>
suspend fun callTool(name: String, argumentsJson: String, callId: String = ""): RumiToolOutcome
fun navigate(action: String, panel: String?): String
fun mediaAccessGranted(): Boolean
fun requestMediaAccess(): String
```

That is the whole contract, and it is deliberately this small. The engine owns
the conversation, the providers, the wire protocols, the streaming, the models
catalogue, the generative services and its own UI; it does **not** own the
editor. The capabilities the model may use are *supplied* by the host as tools —
`RumiToolSpec` describes one to the model, `RumiToolOutcome` carries what it
produced — so the engine never learns what an editor is, and the host decides
what the model can touch.

Two things are worth knowing before changing it:

- **The engine owns its strings.** All of its text lives in
  `src/main/res/values*/strings_rumi.xml`, in four locale folders (English,
  Russian, Simplified Chinese, Traditional Chinese). It does not reach into the
  host's resources, and adding a string here does not touch the host.
- **It has its own spacing scale and section header**, rather than importing the
  host's design system, for the same reason a library should not import its
  host's private constants. That duplication is deliberate.

### Version catalog

`gradle/libs.versions.toml` here is a copy of the aliases this module needs. When
the module is built *as part of the host*, Gradle uses the host's catalog and
this file is ignored; it exists so this repository can also be opened and built
on its own. Keep the versions in step with the host's, and expect nothing to
fail loudly if you do not — the host's build is unaffected either way.

## Licence

GPL-3.0-or-later. The code came from Rumo's `app/` module, which is licensed the
same way; `or-later` rather than `only` so the terms can move to a later GPL
version without asking every contributor who ever touched the tree.

Copyright (C) 2026 Kerneldroid.
