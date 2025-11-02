## Ebook Text-to-Speech Pipeline

This project is a Jakarta EE web application that:

- Uploads a PDF ebook and extracts full text with Apache PDFBox
- Summarises chapters with Google Gemini models
- Synthesises chapter summaries through iFLYTEK streaming TTS
- Serves the generated audio files for download or streaming

### 1. Prerequisites

- JDK 21+
- Apache Maven 3.9+
- Access to Google Gemini API
- Access to iFLYTEK Online TTS (stream) service
- Git (for version control)

### 2. Configure Secrets

All sensitive credentials are pulled from environment variables or JVM system properties.

| Purpose               | Environment variable                 | JVM property                         |
| --------------------- | ------------------------------------ | ------------------------------------ |
| Google Gemini API key | `GOOGLE_API_KEY` or `GEMINI_API_KEY` | `google.api.key` or `gemini.api.key` |
| iFLYTEK App ID        | `IFLYTEK_APP_ID`                     | `iflytek.app.id`                     |
| iFLYTEK API Key       | `IFLYTEK_API_KEY`                    | `iflytek.api.key`                    |
| iFLYTEK API Secret    | `IFLYTEK_API_SECRET`                 | `iflytek.api.secret`                 |

Optional overrides (if not set, sane defaults are used):

- `IFLYTEK_TTS_ENDPOINT` (`iflytek.tts.endpoint`) – defaults to `wss://tts-api-sg.xf-yun.com/v2/tts`
- `IFLYTEK_TTS_VOICE` (`iflytek.tts.voice`) – defaults to `xiaoyun`
- `IFLYTEK_TTS_AUE`, `IFLYTEK_TTS_AUF`, `IFLYTEK_TTS_TTE`
- `IFLYTEK_TTS_SPEED`, `IFLYTEK_TTS_VOLUME`, `IFLYTEK_TTS_PITCH`, `IFLYTEK_TTS_BGS`, `IFLYTEK_TTS_REG`, `IFLYTEK_TTS_RDN`

> **Tip:** Use a `.env` file or application server config for local development, and a secrets manager in production.

### 3. Build & Run

```bash
mvn clean package
```

This produces `target/TTS-1.0-SNAPSHOT.war`. Deploy the WAR to a compatible servlet container (e.g. Payara, Tomcat 10, GlassFish 7).

For rapid local testing you can also use Maven’s built-in Jetty/Tomcat plugins or run from your IDE.

### 4. Usage Flow

1. Visit `/index.jsp`
2. Upload a PDF ebook
3. The server extracts metadata, splits chapters, and generates Gemini summaries
4. Summaries are sent to iFLYTEK TTS via WebSocket; audio files are saved under `test-audio-output/`
5. The results page lists chapter summaries alongside download links for the generated audio

### 5. Troubleshooting

- **403 HMAC signature errors** – ensure system clock is within ±5 minutes of UTC, credentials are valid, and the selected voice is enabled in the iFLYTEK console.
- **IP whitelist errors** – configure the correct public IP in iFLYTEK console or disable the whitelist.
- **Large PDFs** – the Gemini prompt clips source text to 6000 characters per chapter; adjust `MAX_SUMMARY_SOURCE_CHARACTERS` if needed.

### 6. Git Hygiene

Secrets must never be committed. Verify with:

```bash
git status
git diff
```

Before pushing, inspect `pom.xml`, `src/main/java/.../EbookProcessorServlet.java`, and config files to ensure they only contain placeholders for credentials.

### 7. Push to GitHub

1. Authenticate with GitHub (`gh auth login` or Git credentials)
2. Add all relevant files:
   ```bash
   git add .
   ```
3. Review pending changes:
   ```bash
   git status
   git diff --cached
   ```
4. Commit:
   ```bash
   git commit -m "Refactor secrets handling and update docs"
   ```
5. Push to your remote:
   ```bash
   git push origin <your-branch>
   ```

> Make sure that `.gitignore` covers local build outputs and secret files (.env). Add entries if necessary before committing.

### 8. Security Checklist

- Rotate API keys immediately if a secret was previously committed.
- Limit permissions of each API key to the minimum required.
- Store production secrets outside the codebase (cloud secret manager, CI/CD vault, etc.).
- Monitor usage quotas on Gemini and iFLYTEK consoles.

### 9. License & Contributions

Update this section with your project’s licence and contribution guidelines.

This project is released under the [MIT License](LICENSE).
