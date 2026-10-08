<!--
  Short on purpose. This is a library, and the one thing a change here can break
  that a change in an application cannot is the host that consumes it — so that
  is what the checklist asks about.
-->

## What this changes

<!-- One paragraph: what is different after this change. -->

## Why

<!-- The cause, or the reason. If it is a fix, say what was actually wrong. -->

## Checked

- [ ] The module compiles on its own: `./gradlew :rumo:compileDebugKotlin`.
- [ ] It still compiles as the host's submodule — a change to `RumiHost` or to a tool spec breaks Rumo, not this repository.
- [ ] Nothing the engine sends changed without saying so: tool specs, model ids, HTTP headers, request bodies.
- [ ] Comments are English; user-facing text is in a string resource, in all four locale folders.

## Not verified

<!--
  What you did not check, and what a reviewer should look at first. There is no
  device in this repository's CI and no host application, so most behaviour ends
  here as "unverified" — that is a normal line, and a useful one.
-->
