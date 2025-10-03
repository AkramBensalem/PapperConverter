# PDF to Markdown (OCR) IntelliJ Plugin

An IntelliJ IDEA plugin that converts selected PDF files into Markdown (with images) using an OCR API.

Features
- Project View context action: right-click PDF(s) or folder → "Convert PDF to Markdown"
- Settings page under Tools → "PDF to Markdown OCR" to configure options and API key
- Progress indicator, notifications, and auto-opening the generated Markdown
- Output layout per PDF in its own folder; optional project-root output

Requirements
- IntelliJ IDEA 2023.3+
- JBR/Java 17 runtime (configured by Gradle toolchain)

Build & Run
- Run the IDE with the plugin: `./gradlew runIde`
- Build the plugin distribution: `./gradlew buildPlugin`
- Run unit tests: `./gradlew test`

Configuration
- Open Settings/Preferences → Tools → "PDF to Markdown OCR"
- Set your OCR API key (stored securely via PasswordSafe). The settings also include:
  - Output Mode: Alongside PDF or under a Project Output Root
  - Include images, Combine pages, Open after convert
  - Overwrite policy: Skip Existing, Overwrite, With Suffix
- Notification group: "PdfOcr"

OCR API
- This plugin uses HTTP calls (OkHttp) to upload and process PDFs.
- JSON response format expected from OCR API:
  ```json
  {
    "pages": [
      {
        "markdown": "...",
        "images": [ { "id": "image-name.png", "image_base64": "..." } ]
      }
    ]
  }
  ```
- Endpoint configuration: set `-Dpdf.ocr.endpoint=https://your-ocr-endpoint` JVM option when running IDE or set environment `PDF_OCR_ENDPOINT`.
- Proxy: The client attempts to honor IDE proxy settings.
- If no endpoint is set or a call fails, the plugin will still generate a stub Markdown file to allow end‑to‑end flow testing.

Usage
1) In the Project tool window, select one or more PDFs or a directory.
2) Right-click → "Convert PDF to Markdown".
3) Confirm the dialog (shows count, output mode, overwrite policy).
4) Watch progress; on completion, a summary notification appears. If only one file was converted and the setting is enabled, the Markdown opens automatically.

Overwrite Policy Details
- Skip Existing: existing files are left untouched
- Overwrite: existing files are replaced
- With Suffix: target files get a numeric suffix like `name (1).md`

Troubleshooting
- Authentication errors or rate limits: check your API key in Settings.
- Proxy environments: ensure IDE proxy settings are configured; the plugin attempts to use them.
- Endpoint missing: set `pdf.ocr.endpoint` system property or `PDF_OCR_ENDPOINT` environment variable.

Manual Test Checklist
- Single PDF → generates folder with .md and images, opens markdown
- Multiple PDFs → progress increments; summary notification
- Folder selection → recursive discovery
- Overwrite policy behaviors verified
- Settings persist across IDE restarts; Test connection indicates basic validation
- Proxy honored (if configured)
- You can bind a keyboard shortcut to the action (default example: Ctrl+Alt+M)

Notes
- No Python dependency. Any existing scripts in repo are not used by the plugin.
- API key is stored securely with PasswordSafe (shadow boolean stored in settings state).
