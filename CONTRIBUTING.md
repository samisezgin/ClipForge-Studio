# Contributing

## Workflow
- Development happens on `main`. Releases are merged into the protected `release` branch through pull requests
  and tagged there (`vMAJOR.MINOR.PATCH`).
- Run `./mvnw verify` before opening a pull request.
- Never commit media files, `.cfproj` project files or `*.work/` folders (they are ignored).

## Adding a language
UI texts live in Java resource bundles in `clipforge-core/src/main/resources/i18n/`:

- `messages.properties` — English (default, the reference for all keys)
- `messages_tr.properties` — Turkish

To add a language:

1. Copy `messages.properties` to `messages_<code>.properties`, where `<code>` is an
   [IETF language tag](https://en.wikipedia.org/wiki/IETF_language_tag) using underscores (e.g. `de`, `pt_BR`).
2. Translate the values (keep keys and `{0}` placeholders). Files are UTF-8.
3. Set `language.name` to the language's own name (e.g. `Deutsch`).
4. Either open a pull request with the file, or drop it into a `lang/` folder next to the application
   (or `~/.clipforge/lang/`) — ClipForge discovers it on start-up without rebuilding.

Missing keys fall back to English.
