# Contributing to Vela

Thanks for considering a contribution. Vela is small and opinionated; a few notes will keep your PR moving.

## Fork & pull request flow

1. Fork the repo on GitHub.
2. Clone your fork and create a branch off `main`:
   ```bash
   git clone git@github.com:<your-username>/vela.git
   cd vela
   git checkout -b my-change
   ```
3. Make your change. Run a debug build locally to confirm it compiles:
   ```bash
   cd android
   ./gradlew assembleDebug
   ./gradlew :app:testDebugUnitTest
   ```
4. Commit with a clear message describing **why**, not just **what**. Push to your fork.
5. Open a PR against `main`. Link any related issue. Describe what you changed, how you tested it, and any caveats.

GitHub Actions runs `assembleDebug` and the unit tests on every PR. Get those green before requesting review.

## Adding a new tool

Tools are how Gemma 4 acts on the device. Each one is a Kotlin function annotated with `@Tool` / `@ToolParam`, registered into a Hilt-bound `Set<ToolProvider>` via `di/ToolModule.kt`, and dispatched natively by LiteRT-LM.

The recipe:

1. **Write the tool class** under `data/tool/`. Group related actions in a single class — see `MusicTools.kt` as a reference. Each method becomes one tool exposed to the model:
   ```kotlin
   @Singleton
   class WeatherTools @Inject constructor(
       @ApplicationContext private val context: Context,
   ) : ToolSet {
       @Tool(description = "Returns the current weather for a city. Pass ONLY the city name as `city`.")
       fun getWeather(
           @ToolParam(description = "Name of the city, e.g. 'Madrid'") city: String,
       ): String {
           // ... implementation that returns a short, model-readable string
           return "Weather lookup is not implemented yet."
       }
   }
   ```
2. **Register the tool** in `di/ToolModule.kt` with a `@Provides @Singleton @IntoSet` provider:
   ```kotlin
   @Provides @Singleton @IntoSet
   fun provideWeatherToolsProvider(tools: WeatherTools): ToolProvider = tool(tools)
   ```
3. **Declare any required permission** in `AndroidManifest.xml` and request it at runtime if it's a runtime-permission category (location, calendar, contacts, etc.).
4. **Update the system prompt** in `Gemma4ModelWrapper.systemInstruction()` only if the model needs guidance about *when* to call the tool — most tools are self-explanatory from the `@Tool` description alone.
5. **Test it manually.** The tool description is the model's only context; if the LLM can't pick the right tool from the `description`, refine it. Logs flow through Timber:
   ```bash
   adb logcat | grep -i "vela\|gemma\|TOOL "
   ```

Keep tool methods **idempotent and defensive**. Return a short string the model can verbalise back to the user. Do not throw — handle errors and return a string explaining the failure ("Calendar permission is not granted; ask the user to enable it.").

## Code style

- **Kotlin**, official Kotlin style. No reformatting just-because; only touch lines you have a reason to touch.
- **Compose** for any UI. No XML layouts for new screens (`res/layout/` only contains the system-assistant overlay shell).
- **Hilt** for DI. New singletons go through `@Inject` constructors and are scoped at `SingletonComponent` unless they are explicitly per-ViewModel.
- **Timber** for logging. No `println`, no `Log.d` directly.
- **No comments that restate the code.** Comments should explain *why* a non-obvious decision was made, not what the code does. Match the existing tone in `Gemma4ModelWrapper.kt` for reference.
- **No new dependencies** without justification in the PR description. Vela's dependency list is intentionally small.

## Reporting bugs

File issues on GitHub. Useful bug reports include:

- **Phone model** (e.g. "Samsung Galaxy S22").
- **Android version** (Settings → About phone → Android version).
- **Vela version** (read `versionName` from the app, or from `app/build.gradle.kts`).
- **Backend** the model loaded on — grep the logcat for `Gemma 4 ready on` and you'll see `gpu` or `cpu`.
- **Steps to reproduce.** What did you say or type? What happened? What did you expect?
- **Logcat** if relevant: `adb logcat -d | grep -i "vela\|gemma\|mediapipe" > vela.log` and attach.
- **Whether the model finished downloading** (~2.5 GB; first-launch failures often turn out to be partial downloads).

For security-relevant issues, please open a private security advisory on GitHub instead of a public issue.

## License

By contributing you agree that your contribution is licensed under GPL-3.0-or-later (the project's license).
