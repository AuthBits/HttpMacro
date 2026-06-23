# HttpMacro

An Android app for storing and triggering HTTP request macros — perfect for quick API calls, webhooks, and automation.

## Features

- **Macro management** — Create, edit, and delete HTTP request macros with name, URL, method (GET/POST/PUT/DELETE), body, custom headers, and response options
- **Persistent storage** — All macros saved locally with Room database
- **Dynamic shortcuts** — Each macro gets a system launcher shortcut for one-tap triggering
- **Custom URI scheme** — Trigger macros externally via `httpmacro://trigger/{id}`
- **Notifications** — Response status code and body snippet shown in Toast + notification
- **Clipboard** — Save text or image responses to clipboard for quick paste elsewhere

## Architecture

```
app/src/main/java/com/httpmacro/app/
├── Database.kt         # Room DB (MacroEntry, MacroDao, HttpMacroDatabase) + OkHttp executor
├── MainActivity.kt     # Main UI — RecyclerView, add/edit dialog, shortcut management
└── TriggerActivity.kt  # Headless trigger — fires HTTP request, shows result notification
```

| Component     | Library              |
|---------------|----------------------|
| Persistence   | Room 2.6.1           |
| HTTP client   | OkHttp 4.12.0        |
| UI            | Material Components  |
| Language      | Kotlin 2.0.21        |

## Building

Requires Android SDK 35 (compile), minSdk 26.

```bash
./gradlew assembleDebug
```

The debug APK is output to `app/build/outputs/apk/debug/app-debug.apk`.

## Usage

1. Open the app and tap **+ New** to create a macro
2. Fill in the name, URL, method, optional body/headers
3. Tap **▶ Fire** from the list, or use the launcher shortcut to trigger it
4. Results appear as a Toast + notification with the HTTP status code and response body

## License

MIT
