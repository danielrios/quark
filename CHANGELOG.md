# Changelog

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
