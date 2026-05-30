# Changelog

## [0.3.0](https://github.com/danielrios/quark/compare/v0.2.0...v0.3.0) (2026-05-30)


### Features

* **chat:** per-session conversation memory via @MemoryId ([8d3697a](https://github.com/danielrios/quark/commit/8d3697a09c00f951fc9897832c66b52847f72d76))
* **harness:** advisory §8 lifecycle-deletion guard hook ([c221c07](https://github.com/danielrios/quark/commit/c221c07240544709c492452e91af32a703944584))
* **plan-2:** per-session memory + /reset command ([0f891c3](https://github.com/danielrios/quark/commit/0f891c3c83bae8a3deb2539fecceb7c06d82c75b))
* **telegram:** /reset clears the session's conversation memory ([ec240a1](https://github.com/danielrios/quark/commit/ec240a11b05788cdb140e425846892348dfa8bab))
* **telegram:** add pure /reset command parser ([18bd893](https://github.com/danielrios/quark/commit/18bd8933239b61e1388905feb1e2741f4e0df538))


### Bug Fixes

* **memory:** make Assistant @ApplicationScoped, drop redundant store ([8ad9bab](https://github.com/danielrios/quark/commit/8ad9babf59123ac0f9af6e5a07dc19a444e3dc9d))
* **memory:** preserve chat memory across requests ([76dfeaf](https://github.com/danielrios/quark/commit/76dfeaf46b49718a79e83f767e24113b96adc067))
* **telegram:** add rationale comment to RESET dispatch placeholder ([21ac44c](https://github.com/danielrios/quark/commit/21ac44c7ae1952da2f44bd9d8c49410651d21001))
* **telegram:** clean up ChatMemoryStore import and add test teardown ([6f22771](https://github.com/danielrios/quark/commit/6f22771f8c681000b710e303fee4c3450d34001a))

## [0.2.0](https://github.com/danielrios/quark/compare/v0.1.0...v0.2.0) (2026-05-29)


### Features

* add gemini and rest-client extensions ([155515f](https://github.com/danielrios/quark/commit/155515fb6715c41f8822d54b93a97cb5ad8b3006))
* add stateless gemini assistant ai service ([28cc5fe](https://github.com/danielrios/quark/commit/28cc5fee9c655dbca88f1646c808b376d5400672))
* parse telegram updates and compute poll offset ([8a6bc4c](https://github.com/danielrios/quark/commit/8a6bc4c22d66771d72244006aaf6f24d5ba5a3df))
* Telegram + Gemini walking skeleton (Plan 1) ([8dc5c84](https://github.com/danielrios/quark/commit/8dc5c8444c3317e3f89490037b21cea38c5a48a0))
* telegram long-poll loop answering with gemini ([4547168](https://github.com/danielrios/quark/commit/454716895cf0e6f9397393432ecef64be9d4c5fb))


### Bug Fixes

* activate CDI request context in virtual-thread poll handler ([716ffa1](https://github.com/danielrios/quark/commit/716ffa1900d87d509061552fe7c14470fc7afa57))
* clamp telegram replies to 4096-char limit ([38b9e03](https://github.com/danielrios/quark/commit/38b9e039d06e92c1b9fe74abc66bd9224134d4de))
* move BOM platform entries before regular deps in build.gradle.kts ([9a898bd](https://github.com/danielrios/quark/commit/9a898bd84f4f0adf32d0089655edbe31f71730d7))
* use gemini-2.5-flash (2.0-flash retired for new keys) ([48d92f9](https://github.com/danielrios/quark/commit/48d92f9ec97966ad10877769e8d9f6c4b6c23c35))

## [0.1.0](https://github.com/danielrios/quark/compare/v0.0.1...v0.1.0) (2026-05-27)


### Features

* **harness:** add release-please for auto tags + GitHub Releases on merge ([14ed8f0](https://github.com/danielrios/quark/commit/14ed8f0e050abe734df8712ac2e17654877a87c4))


### Bug Fixes

* **harness:** tighten --continuous guard, drop sed fallback, prune no-op Spotless wiring ([c2dc05d](https://github.com/danielrios/quark/commit/c2dc05d15b18efe1ea818e7558708b5015ee88a6))
* **release:** seed manifest at 0.0.1 so first feat: bumps to 0.1.0 ([4ec02d2](https://github.com/danielrios/quark/commit/4ec02d207de9296c1873388b9a90bf21f9d27fb0))
