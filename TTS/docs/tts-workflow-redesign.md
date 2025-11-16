# TTS Workflow Redesign

## Objectives

- Align the ebook processing pipeline with the new UX requirement where audio is generated only when the user explicitly requests it.
- Provide deterministic chapter-level summaries while still supporting whole-document analysis for unstructured uploads.
- Preserve the existing adaptive question bank workflow without behavioral changes.

## Current Flow Pain Points

1. Single-step processing forces the user to wait for every chapter audio file even if they only need a subset.
2. Audio generation logic is tightly coupled with summarization, making it hard to reuse summaries later.
3. No storage layer exists for parsed chapters or summaries, so the servlet must reprocess the file for every new action.

## Proposed Multi-Step Flow

1. **Upload & Parse**

   - Default action remains `process-audio`, but the servlet now branches into an "upload" stage.
   - Read the multipart file once, extract metadata, language, and the raw text using the existing helpers.
   - Detect chapters using `splitIntoChapters`.
   - Persist a lightweight DTO (e.g., `UploadedDocumentContext`) in the HTTP session containing:
     - Original filename and metadata
     - Document language
     - Full text (if acceptable) or a temporary file reference
     - List of `ChapterContent` records with stable numeric IDs and body text
   - Redirect/forward to results.jsp to display summaries without generating audio.

2. **Summarize Content**

   - For documents with ≥2 chapters:
    - Generate summaries for each chapter immediately after parsing using the existing Gemini helper.
     - Store the summaries in session alongside chapter IDs, but mark audio status as "not generated".
   - For documents with <2 chapters:
     - Run a whole-document analysis helper that produces:
       - Executive summary
       - Key topics / bullet breakdown
       - Recommended follow-up questions
     - Store this analysis under a special pseudo-chapter ID (e.g., -1) so the UI can still offer audio generation for the entire document.

3. **User-Initiated Audio Generation**

   - Add a new POST endpoint on `EbookProcessorServlet` (action = `generate-audio`) that accepts a chapter ID.
   - Retrieve the stored summary for the requested chapter/analysis from session.
   - Invoke `generateAudioWithIflytek` on demand and persist the resulting filename back into the chapter record.
   - Return the user to results.jsp with updated state so only the selected chapter shows an embedded player.
   - Only add the generated file to `audioDownloadItems` when the audio actually exists. The ZIP download will therefore contain only requested chapters.

4. **Question Bank Generation**
   - Keep the existing action = `generate-question-bank` logic untouched.
   - Reuse the parsed chapters from session when available; otherwise fall back to reparsing the uploaded file.

## Data Model Changes

- Introduce `UploadedDocumentContext` (session-scoped) encapsulating metadata, language, chapters, analysis, and a timestamp.
- Extend `ChapterResult` or replace with `ChapterViewModel` that tracks {id, title, summary, audioFileName, audioStatus}.
- Maintain backward compatibility by keeping `AudioDownloadItem` but populate it dynamically from chapters that have audio.

## Servlet Updates

- Refactor the `doPost` handler to switch on action: upload (default), generate-audio, generate-question-bank, clear-session.
- Extract helper `prepareSummaries(UploadedDocumentContext)` that fills summaries into session after parsing.
- Add helper `ensureDocumentContext(HttpSession)` to guard against expired sessions and provide clear error messaging.
- Update audio generation branch to validate chapter ownership, call iFLYTEK, and update both the context and session download list.

## JSP Updates

- Modify [`results.jsp`](src/main/webapp/results.jsp) to iterate over stored chapter view models and render:
  - Summary text
  - Audio status indicator (e.g., "Chưa tạo" vs. embedded player)
  - Form button to POST action=generate-audio with the chapter ID
- Add a whole-document analysis panel when only the fallback pseudo-chapter exists.
- Display a notice if the session expires, guiding the user back to upload.

## Session & Cleanup

- Store generation timestamp and discard contexts older than a configurable TTL to avoid stale files.
- Provide a "Clear session" action or automatically clear the context after ZIP download to prevent accidental reuse.

## Testing Considerations

- Verify Vietnamese and English uploads still route through the same summarization models.
- Ensure question bank generation continues to operate from either session context or freshly parsed chapters.
- Cover edge cases: missing file uploads, large documents exceeding summary limit, consecutive audio requests, and session expiration.
