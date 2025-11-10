## Ebook Text-to-Speech (TTS) — Smart eBook Processor

This is a Jakarta EE web application that converts an uploaded PDF or DOCX ebook into
chapter summaries and audio. It performs the following tasks:

- Extracts text and metadata from a PDF/DOCX using Apache PDFBox / Apache POI
- Splits the text into chapters (heuristic headings)
- Summarizes each chapter using an LLM (Google Gemini by default or Ollama)
- Generates spoken audio for each chapter summary using iFLYTEK TTS (streaming WebSocket)
- Presents results in a web UI and allows downloading all chapter audio as a single zip

This README documents how to configure, run, and troubleshoot the project.

---

### Prerequisites

- Java 17+ (project compiled/tested with modern JDKs — use JDK 21 when possible)
- Apache Maven 3.6+ (3.8+/3.9+ recommended)
- Servlet container compatible with Jakarta EE (Tomcat 10, Payara, GlassFish 7, etc.)
- Optional services / accounts:
  - Google Gemini API access (or other LLM)
  - iFLYTEK TTS cloud credentials (for high-quality audio)
  - Ollama (local or cloud) if you prefer using Ollama models

---

### Environment configuration

The app reads credentials and options from environment variables or JVM system properties. Environment variables are preferred.

Core variables:

- GOOGLE_API_KEY or GEMINI_API_KEY (Gemini)
- IFLYTEK_APP_ID
- IFLYTEK_API_KEY
- IFLYTEK_API_SECRET

Ollama (optional, for local or cloud Ollama models):

- OLLAMA_API_KEY (when using ollama.com cloud API)
- OLLAMA_HOST (set to https://ollama.com to target cloud API; otherwise uses http://localhost:11434)
- OLLAMA_MODEL (defaults to a sensible cloud/local model depending on OLLAMA_API_KEY presence)

IFLYTEK optional overrides and tunables (also available as system properties under `iflytek.*`):

- IFLYTEK_TTS_ENDPOINT (default: wss://tts-api-sg.xf-yun.com/v2/tts)
- IFLYTEK_TTS_VOICE (default: xiaoyun)
- IFLYTEK_TTS_AUE / IFLYTEK_TTS_AUF / IFLYTEK_TTS_TTE
- IFLYTEK_TTS_SPEED / IFLYTEK_TTS_VOLUME / IFLYTEK_TTS_PITCH

Other useful environment flags:

- TTS_PREVIEW_ONLY=true to use a local preview/synth (no external API calls)

---

### Build

From the repository root:

```powershell
mvn clean package -DskipTests
```

This produces `target/TTS-1.0-SNAPSHOT.war`.

---

### Deploy

Deploy the WAR to Tomcat 10 / Payara / GlassFish. When running locally from your IDE, ensure the runtime provides the Jakarta Servlet 5 API.

---

### Usage

1. Open the app root (e.g., `http://localhost:8080/TTS/`) and go to `index.jsp`.
2. Upload a PDF or DOCX file.
3. The server will parse the file, detect chapters, summarize each chapter with the configured LLM, and generate TTS audio with iFLYTEK.
4. The results page lists chapter titles, summaries, and a download button to get all chapter audio as a single ZIP.

Download naming behaviour:

- The zip filename is derived from the ebook title metadata (falls back to the uploaded filename stem) and looks like: `<book>-audio.zip`.
- Inside the zip, audio files are ordered and named as `01-chapter-title.mp3`, with diacritics removed and unsafe characters replaced, so users can easily find chapters by number + title.

---

### Troubleshooting

- If you see HTTP 400 from Ollama complaining about invalid characters, ensure the app is running a version that sanitizes control characters. Prompts containing binary/control characters can break JSON encoding.
- If the download ZIP contains unexpected files, ensure you are using the app `Download` button (it zips only generated chapter audio stored in the session). Files under `target/` or other app directories are not included.
- For iFLYTEK HMAC errors, make sure your system clock is correct and credentials are valid.

Logs and debugging:

- Check your servlet container logs for stack traces printed by the servlet.
- For Ollama API responses, the servlet will raise descriptive errors (HTTP code + body) when the remote host returns unexpected data.

---

### Example: run locally with environment variables (PowerShell)

```powershell
$env:GOOGLE_API_KEY = 'your_key_here'
$env:IFLYTEK_APP_ID = 'your_app_id'
$env:IFLYTEK_API_KEY = 'your_iflytek_key'
$env:IFLYTEK_API_SECRET = 'your_iflytek_secret'
mvn clean package
```

Then deploy the WAR to your container.

---

### Development notes

- Generated audio is written to an `test-audio-output` directory in the project working directory for easy inspection.
- The servlet stores a lightweight list of `AudioDownloadItem` objects in the user session when audio is created; the ZIP generator reads only those entries for packaging.

---

### Contributing, License

Contributions welcome. The project is licensed under the MIT License (see `LICENSE`).
