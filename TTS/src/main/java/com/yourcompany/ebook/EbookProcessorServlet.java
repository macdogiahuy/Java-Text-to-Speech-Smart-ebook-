package com.yourcompany.ebook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.Part;

@WebServlet(name = "EbookProcessorServlet", urlPatterns = "/process-ebook")
@MultipartConfig(maxFileSize = 1024 * 1024 * 100, fileSizeThreshold = 1024 * 1024 * 5)
public class EbookProcessorServlet extends HttpServlet {

  private static final long serialVersionUID = 1L;

  // TODO: Replace placeholders with actual keys before production deploy.
  private static final String GEMINI_API_KEY = "AIzaSyDhqpdjSISltQ1tZk7ej7oXc54yfs2UNhA"; // Set via secure configbefore
                                                                                          // deployment
  private static final String IFLYTEK_TTS_ENDPOINT = "wss://tts-api-sg.xf-yun.com/v2/tts";
  // TODO: Replace with secure configuration before deployment.
  private static final String IFLYTEK_APP_ID = "ga808480";
  private static final String IFLYTEK_API_KEY = "d742d908cae15e294634326dcb3b48c2";
  private static final String IFLYTEK_API_SECRET = "1ef679ae0116ef363d4b4b3c5f8d3a4b";
  private static final String IFLYTEK_DEFAULT_VOICE = "xiaoyun";
  private static final String IFLYTEK_DEFAULT_AUE = "lame";
  private static final String IFLYTEK_DEFAULT_TTE = "UTF8";
  private static final String IFLYTEK_DEFAULT_AUF = "audio/L16;rate=16000";
  private static final int IFLYTEK_TEXT_CHAR_LIMIT = 2_000;
  private static final String IFLYTEK_TRUNCATION_NOTICE = "\n(Đoạn tóm tắt đã được rút gọn do giới hạn ký tự TTS iFLYTEK.)";
  private static final Duration IFLYTEK_WS_TIMEOUT = Duration.ofSeconds(90);
  private static final String LOCAL_AUDIO_OUTPUT_DIR_NAME = "test-audio-output";
  private static final int MAX_SUMMARY_SOURCE_CHARACTERS = 6000;
  private static final String[] GEMINI_MODEL_VI = {
      "gemini-2.5-flash",
      "gemini-2.5-pro",
      "gemini-2.5-flash-lite" };
  private static final String[] GEMINI_MODEL_EN = {
      "gemini-2.5-flash",
      "gemini-2.5-pro",
      "gemini-2.5-flash-lite" };
  private static final DateTimeFormatter IFLYTEK_RFC1123_FORMATTER = DateTimeFormatter
      .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
  private static final char COMBINING_BREVE = '\u0306';
  private static final char COMBINING_HORN = '\u031B';
  private static final int DEFAULT_POI_MAX_FILE_COUNT = 20_000;
  private static final int MIN_POI_MAX_FILE_COUNT = 1_000;
  private static final int MAX_POI_MAX_FILE_COUNT = 200_000;
  private static final double DEFAULT_POI_MIN_INFLATE_RATIO = 0.001d;
  private static final double MIN_POI_MIN_INFLATE_RATIO = 0.0d;
  private static final double MAX_POI_MIN_INFLATE_RATIO = 0.01d;
  private static volatile boolean docxZipSecureConfigured;

  // Ollama configuration defaults
  private static final String OLLAMA_DEFAULT_LOCAL_HOST = "http://localhost:11434";
  private static final String OLLAMA_DEFAULT_CLOUD_HOST = "https://ollama.com";
  private static final String OLLAMA_DEFAULT_LOCAL_MODEL = "gemma3:1b";
  private static final String OLLAMA_DEFAULT_CLOUD_MODEL = "gpt-oss:120b-cloud";

  private static final Pattern CHAPTER_HEADING_PATTERN = Pattern.compile(
      "(?im)^(chapter\\s+(\\d+|[ivxlcdm]+)\\b.*|chương\\s+\\d+\\b.*|chapitre\\s+\\d+\\b.*|ch\\.?\\s*\\d+\\b.*|\\d+\\.\\s+chapter\\b.*)$");
  private static final Pattern[] AUTHOR_HINT_PATTERNS = {
      Pattern.compile(
          "(?im)^\\s*(?:tác giả|tac gia|author|biên soạn|bien soan|chủ biên|chu bien)\\s*[:：]\\s*([^\\n\\r]+)"),
      Pattern.compile("(?im)^\\s*(?:nhóm biên soạn|group author|soạn giả)\\s*[:：]\\s*([^\\n\\r]+)"),
      Pattern.compile("(?im)\\b(?:by|edited by|compiled by)\\s+([\\p{L}0-9 .,'-]{3,})") };
  private static final int QUESTION_BANK_TARGET_COUNT = 150;
  private static final int QUESTION_BANK_MIN_OPTIONS = 4;
  private static final int QUESTION_BANK_MAX_CONTEXT_CHARS = 18_000;
  private static final double QUESTION_BANK_EASY_RATIO = 0.34d;
  private static final double QUESTION_BANK_HARD_RATIO = 0.22d;
  private static final DateTimeFormatter QUESTION_BANK_FILENAME_FORMATTER = DateTimeFormatter
      .ofPattern("yyyyMMdd-HHmmss", Locale.US);

  private transient volatile Client geminiClient;
  private transient volatile boolean localPreviewOverride;

  // Reuse a single HTTP client for outbound API calls.
  private final HttpClient httpClient = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(30))
      .build();
  private static final Gson GSON = new Gson();
  private static final String SESSION_ATTR_DOCUMENT_CONTEXT = "uploadedDocumentContext";

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.sendRedirect(request.getContextPath() + "/index.jsp");
  }

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
    String action = request.getParameter("action");
    if (action == null || action.isBlank()) {
      action = "process-audio";
    }

    if ("generate-audio".equalsIgnoreCase(action)) {
      try {
        handleGenerateAudioRequest(request, response);
      } catch (IllegalStateException ex) {
        forwardWithError(request, response, ex.getMessage());
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        forwardWithError(request, response, "Có lỗi xảy ra khi tạo audio: " + ex.getMessage());
      }
      return;
    }

    Part ebookPart;
    try {
      ebookPart = request.getPart("ebook");
    } catch (IllegalStateException ex) {
      forwardWithError(request, response, "Tệp tải lên quá lớn hoặc yêu cầu multipart không hợp lệ.");
      return;
    }

    if (ebookPart == null || ebookPart.getSize() == 0) {
      forwardWithError(request, response, "Vui lòng chọn một tệp PDF hoặc DOCX để tải lên.");
      return;
    }

    String uploadedFileName = Paths.get(ebookPart.getSubmittedFileName()).getFileName().toString();

    try {
      DocumentContents document = loadDocument(ebookPart, uploadedFileName);

      List<ChapterContent> chapters = splitIntoChapters(document.getFullText());
      if (chapters.isEmpty()) {
        forwardWithError(request, response, "Không tìm thấy nội dung chương hợp lệ trong tài liệu.");
        return;
      }

      if ("generate-question-bank".equalsIgnoreCase(action)) {
        handleQuestionBankResponse(request, response, document, chapters, uploadedFileName);
        return;
      }

      // Tóm tắt các chương và hiển thị danh sách để người dùng chọn
      DocumentContext context = prepareDocumentContext(uploadedFileName, document, chapters);
      HttpSession session = request.getSession(true);
      session.setAttribute(SESSION_ATTR_DOCUMENT_CONTEXT, context);

      request.setAttribute("metadata", context.getMetadata());
      request.setAttribute("chapters", context.getChapters());
      request.setAttribute("uploadedFileName", uploadedFileName);
      request.setAttribute("isStructuredDocument", context.isStructured());

      request.getRequestDispatcher("/chapters.jsp").forward(request, response);
    } catch (IllegalStateException ex) {
      forwardWithError(request, response, ex.getMessage());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      forwardWithError(request, response, "Có lỗi xảy ra khi xử lý tài liệu: " + ex.getMessage());
    }
  }

  private String extractFileExtension(String fileName) {
    if (fileName == null) {
      return "";
    }
    int dotIndex = fileName.lastIndexOf('.');
    if (dotIndex < 0 || dotIndex >= fileName.length() - 1) {
      return "";
    }
    return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
  }

  private DocumentContents loadDocument(Part ebookPart, String uploadedFileName) throws IOException {
    String extension = extractFileExtension(uploadedFileName);
    if ("pdf".equals(extension)) {
      try (InputStream inputStream = ebookPart.getInputStream(); PDDocument document = PDDocument.load(inputStream)) {
        String fullText = extractFullText(document);
        EbookMetadata metadata = extractPdfMetadata(document, uploadedFileName, fullText);
        DocumentLanguage language = detectDocumentLanguage(fullText);
        return new DocumentContents(fullText, metadata, language);
      }
    }
    if ("docx".equals(extension)) {
      configureZipSecureFileLimits();
      try (InputStream inputStream = ebookPart.getInputStream();
          XWPFDocument document = new XWPFDocument(inputStream)) {
        String fullText = extractFullText(document);
        EbookMetadata metadata = extractDocxMetadata(document, uploadedFileName, fullText);
        DocumentLanguage language = detectDocumentLanguage(fullText);
        return new DocumentContents(fullText, metadata, language);
      }
    }
    throw new IllegalStateException("Định dạng tệp không được hỗ trợ. Vui lòng tải lên PDF hoặc DOCX.");
  }

  private EbookMetadata extractPdfMetadata(PDDocument document, String uploadedFileName, String fullText) {
    PDDocumentInformation info = document.getDocumentInformation();
    String title = safeTrim(info != null ? info.getTitle() : null);
    String author = safeTrim(info != null ? info.getAuthor() : null);
    String subject = safeTrim(info != null ? info.getSubject() : null);
    String keywords = safeTrim(info != null ? info.getKeywords() : null);
    String producer = safeTrim(info != null ? info.getProducer() : null);

    String year = null;
    if (info != null && info.getCreationDate() != null) {
      Calendar calendar = info.getCreationDate();
      year = String.valueOf(calendar.get(Calendar.YEAR));
    }

    String fallbackBaseName = humanizeFilenameStem(extractBaseName(uploadedFileName));
    if (title == null || title.isEmpty() || isGenericTitle(title)) {
      title = fallbackBaseName != null ? fallbackBaseName : "Không rõ";
    }

    if (author == null || author.isEmpty()) {
      author = guessAuthorFromText(fullText);
    }
    if (author == null || author.isEmpty()) {
      author = "Không rõ";
    }

    if (subject != null && subject.equalsIgnoreCase(title)) {
      subject = null;
    }
    if (keywords != null && keywords.equalsIgnoreCase(title)) {
      keywords = null;
    }
    if (producer != null) {
      String normalizedProducer = producer.trim().toLowerCase(Locale.ROOT);
      if (normalizedProducer.contains("microsoft word")
          || normalizedProducer.contains("acrobat")
          || normalizedProducer.contains("pdf")
          || normalizedProducer.contains("office")) {
        producer = null;
      }
    }

    if (year == null || year.isEmpty()) {
      year = "Không rõ";
    }

    return new EbookMetadata(title, author, year, subject, keywords, producer);
  }

  private EbookMetadata extractDocxMetadata(XWPFDocument document, String uploadedFileName, String fullText) {
    POIXMLProperties properties = document.getProperties();
    POIXMLProperties.CoreProperties core = properties != null ? properties.getCoreProperties() : null;

    String title = core != null ? safeTrim(core.getTitle()) : null;
    String author = core != null ? safeTrim(core.getCreator()) : null;
    String subject = core != null ? safeTrim(core.getSubject()) : null;
    String keywords = core != null ? safeTrim(core.getKeywords()) : null;
    String producer = null;

    String year = null;
    if (core != null) {
      Date created = core.getCreated();
      if (created != null) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(created);
        year = String.valueOf(calendar.get(Calendar.YEAR));
      } else {
        Date modified = core.getModified();
        if (modified != null) {
          Calendar calendar = Calendar.getInstance();
          calendar.setTime(modified);
          year = String.valueOf(calendar.get(Calendar.YEAR));
        }
      }
    }

    String fallbackBaseName = humanizeFilenameStem(extractBaseName(uploadedFileName));
    if (title == null || title.isEmpty() || isGenericTitle(title)) {
      title = fallbackBaseName != null ? fallbackBaseName : "Không rõ";
    }

    if (author == null || author.isEmpty()) {
      author = guessAuthorFromText(fullText);
    }
    if (author == null || author.isEmpty()) {
      author = "Không rõ";
    }

    if (subject != null && subject.equalsIgnoreCase(title)) {
      subject = null;
    }
    if (keywords != null && keywords.equalsIgnoreCase(title)) {
      keywords = null;
    }
    if (year == null || year.isEmpty()) {
      year = "Không rõ";
    }

    return new EbookMetadata(title, author, year, subject, keywords, producer);
  }

  private String extractBaseName(String fileName) {
    if (fileName == null) {
      return null;
    }
    String trimmed = fileName.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    int slashIndex = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
    if (slashIndex >= 0 && slashIndex < trimmed.length() - 1) {
      trimmed = trimmed.substring(slashIndex + 1);
    }
    int dotIndex = trimmed.lastIndexOf('.');
    if (dotIndex > 0) {
      trimmed = trimmed.substring(0, dotIndex);
    }
    trimmed = trimmed.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private String humanizeFilenameStem(String baseName) {
    if (baseName == null || baseName.isBlank()) {
      return null;
    }
    String humanized = baseName.replace('_', ' ').replace('-', ' ').trim();
    humanized = humanized.replaceAll("\\s{2,}", " ");
    if (humanized.length() > 120) {
      humanized = humanized.substring(0, 120);
    }
    return humanized.isEmpty() ? null : humanized;
  }

  private boolean isGenericTitle(String title) {
    if (title == null) {
      return true;
    }
    String normalized = title.trim().toLowerCase(Locale.ROOT);
    if (normalized.isEmpty()) {
      return true;
    }
    return normalized.startsWith("microsoft word")
        || normalized.startsWith("document")
        || normalized.startsWith("untitled")
        || normalized.startsWith("bản nháp")
        || normalized.equals("không rõ")
        || normalized.equals("unknown")
        || normalized.equals("new document");
  }

  private String guessAuthorFromText(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    String snippet = text.length() > 8000 ? text.substring(0, 8000) : text;
    snippet = snippet.replace('\r', '\n');

    for (Pattern pattern : AUTHOR_HINT_PATTERNS) {
      Matcher matcher = pattern.matcher(snippet);
      if (matcher.find()) {
        String candidate = safeTrim(matcher.group(1));
        if (candidate == null || candidate.isEmpty()) {
          continue;
        }
        candidate = candidate.replaceAll("\\s{2,}", " ").replaceAll("[.:]+$", "").trim();
        if (!candidate.isEmpty() && candidate.length() <= 120) {
          return candidate;
        }
      }
    }
    return null;
  }

  private String extractFullText(PDDocument document) throws IOException {
    PDFTextStripper textStripper = new PDFTextStripper();
    textStripper.setSortByPosition(true);
    return textStripper.getText(document);
  }

  private String extractFullText(XWPFDocument document) {
    StringBuilder builder = new StringBuilder();
    document.getParagraphs().forEach(paragraph -> {
      String text = paragraph.getText();
      if (text != null && !text.isEmpty()) {
        builder.append(text.trim()).append(System.lineSeparator());
      }
    });
    document.getTables().forEach(table -> table.getRows().forEach(row -> row.getTableCells().forEach(cell -> {
      String text = cell.getText();
      if (text != null && !text.isEmpty()) {
        builder.append(text.trim()).append(System.lineSeparator());
      }
    })));
    return builder.toString();
  }

  private List<ChapterContent> splitIntoChapters(String rawText) {
    List<ChapterContent> chapters = new ArrayList<>();
    if (rawText == null || rawText.isBlank()) {
      return chapters;
    }

    Matcher matcher = CHAPTER_HEADING_PATTERN.matcher(rawText);
    List<Integer> chapterStartPositions = new ArrayList<>();
    List<String> chapterTitles = new ArrayList<>();
    List<String> rawHeadings = new ArrayList<>();

    while (matcher.find()) {
      chapterStartPositions.add(matcher.start());
      String heading = matcher.group();
      rawHeadings.add(heading);
      chapterTitles.add(sanitizeHeading(heading));
    }

    if (chapterStartPositions.isEmpty()) {
      // No explicit chapter headings detected, treat entire text as one chapter.
      chapters.add(new ChapterContent("Toàn bộ sách", rawText.trim(), 0, null));
      return chapters;
    }

    chapterStartPositions.add(rawText.length());

    for (int i = 0; i < chapterTitles.size(); i++) {
      int start = chapterStartPositions.get(i);
      int end = chapterStartPositions.get(i + 1);
      String body = rawText.substring(start, end).trim();
      Integer sequence = extractChapterSequence(rawHeadings.get(i));
      chapters.add(new ChapterContent(chapterTitles.get(i), body, start, sequence));
    }

    return deduplicateAndOrderChapters(chapters);
  }

  private String sanitizeHeading(String heading) {
    String clean = heading.replaceAll("[\r\n]", " ").trim();
    clean = clean.replaceAll("\\s+", " ");
    if (clean.length() > 80) {
      clean = clean.substring(0, 80) + "...";
    }
    return clean;
  }

  private Integer extractChapterSequence(String heading) {
    if (heading == null) {
      return null;
    }
    String normalized = heading.trim();
    if (normalized.isEmpty()) {
      return null;
    }

    Pattern labeledArabic = Pattern.compile(
        "(?i)(?:chapter|chương|chapitre|ch\\.?|section|unit|lesson|bài)\\s*(?:[:\\-\\.\\u2013]\\s*)*(\\d{1,4})");
    Matcher arabicMatcher = labeledArabic.matcher(normalized);
    if (arabicMatcher.find()) {
      return parseSafeInt(arabicMatcher.group(1));
    }

    Pattern labeledRoman = Pattern.compile(
        "(?i)(?:chapter|chương|chapitre|ch\\.?|section|unit|lesson|bài)\\s*(?:[:\\-\\.\\u2013]\\s*)*([ivxlcdm]{1,10})");
    Matcher romanMatcher = labeledRoman.matcher(normalized);
    if (romanMatcher.find()) {
      Integer value = romanToInt(romanMatcher.group(1));
      if (value != null) {
        return value;
      }
    }

    Pattern leadingDigits = Pattern.compile("^(\\d{1,4})");
    Matcher leadingMatcher = leadingDigits.matcher(normalized);
    if (leadingMatcher.find()) {
      return parseSafeInt(leadingMatcher.group(1));
    }

    Pattern anyDigits = Pattern.compile("(\\d{1,4})");
    Matcher anyDigitMatcher = anyDigits.matcher(normalized);
    if (anyDigitMatcher.find()) {
      return parseSafeInt(anyDigitMatcher.group(1));
    }

    Pattern romanAnywhere = Pattern.compile("(?i)\\b([ivxlcdm]{1,10})\\b");
    Matcher romanAnywhereMatcher = romanAnywhere.matcher(normalized);
    if (romanAnywhereMatcher.find()) {
      return romanToInt(romanAnywhereMatcher.group(1));
    }

    return null;
  }

  private Integer parseSafeInt(String digits) {
    try {
      return Integer.parseInt(digits);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private Integer romanToInt(String roman) {
    if (roman == null || roman.isBlank()) {
      return null;
    }
    String upper = roman.toUpperCase(Locale.ROOT);
    int total = 0;
    int previous = 0;
    for (int i = upper.length() - 1; i >= 0; i--) {
      int value = romanCharValue(upper.charAt(i));
      if (value == 0) {
        return null;
      }
      if (value < previous) {
        total -= value;
      } else {
        total += value;
        previous = value;
      }
    }
    return total > 0 ? total : null;
  }

  private int romanCharValue(char c) {
    switch (c) {
      case 'I':
        return 1;
      case 'V':
        return 5;
      case 'X':
        return 10;
      case 'L':
        return 50;
      case 'C':
        return 100;
      case 'D':
        return 500;
      case 'M':
        return 1000;
      default:
        return 0;
    }
  }

  private List<ChapterContent> deduplicateAndOrderChapters(List<ChapterContent> input) {
    if (input.size() < 2) {
      return input;
    }

    Set<Integer> seenSequences = new HashSet<>();
    List<ChapterContent> filtered = new ArrayList<>(input.size());

    for (int i = input.size() - 1; i >= 0; i--) {
      ChapterContent chapter = input.get(i);
      Integer sequence = chapter.getSequence();
      if (sequence != null) {
        if (!seenSequences.add(sequence)) {
          continue;
        }
      }
      filtered.add(chapter);
    }

    filtered.sort((a, b) -> Integer.compare(a.getStartOffset(), b.getStartOffset()));
    return filtered;
  }

  private DocumentLanguage detectDocumentLanguage(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalStateException("Không thể xác định ngôn ngữ tài liệu.");
    }
    String sample = text.length() > 8000 ? text.substring(0, 8000) : text;
    String normalized = Normalizer.normalize(sample, Normalizer.Form.NFD);

    boolean hasVietnameseMarkers = normalized.indexOf(COMBINING_BREVE) >= 0
        || normalized.indexOf(COMBINING_HORN) >= 0
        || normalized.indexOf('đ') >= 0
        || normalized.indexOf('Đ') >= 0;
    if (hasVietnameseMarkers) {
      return DocumentLanguage.VIETNAMESE;
    }

    int asciiLetters = 0;
    int nonAsciiLetters = 0;
    for (int i = 0; i < normalized.length(); i++) {
      char ch = normalized.charAt(i);
      if (Character.isLetter(ch)) {
        if (ch <= 127) {
          asciiLetters++;
        } else {
          nonAsciiLetters++;
        }
      }
    }

    if (nonAsciiLetters > 0) {
      throw new IllegalStateException("Tài liệu hiện chỉ hỗ trợ tiếng Việt hoặc tiếng Anh.");
    }
    if (asciiLetters == 0) {
      throw new IllegalStateException("Không tìm thấy đủ văn bản để xác định ngôn ngữ.");
    }
    return DocumentLanguage.ENGLISH;
  }

  @SuppressWarnings("unused")
  private String summarizeTextWithGemini(String chapterTitle, String chapterText, DocumentLanguage language) {
    ensureGeminiConfigured();

    String trimmedText = chapterText.length() > MAX_SUMMARY_SOURCE_CHARACTERS
        ? chapterText.substring(0, MAX_SUMMARY_SOURCE_CHARACTERS)
        : chapterText;

    String prompt = buildGeminiPrompt(language, chapterTitle, trimmedText);

    GenerateContentResponse response = null;
    RuntimeException lastModelException = null;
    String[] candidates = geminiModelCandidates(language);
    if (candidates.length == 0) {
      throw new IllegalStateException("Không tìm thấy cấu hình model Gemini phù hợp cho ngôn ngữ.");
    }
    for (String modelName : candidates) {
      try {
        response = getGeminiClient().models.generateContent(modelName, prompt, null);
        lastModelException = null;
        break;
      } catch (RuntimeException ex) {
        if (isModelNotFound(ex)) {
          lastModelException = ex;
          continue;
        }
        throw new IllegalStateException("Không thể gọi Gemini API: " + ex.getMessage(), ex);
      }
    }

    if (response == null) {
      if (lastModelException != null) {
        throw new IllegalStateException(
            "Không thể gọi Gemini API với các model khả dụng. Hãy cập nhật tên model trong cấu hình.",
            lastModelException);
      }
      throw new IllegalStateException("Gemini API không trả về nội dung tóm tắt.");
    }

    String summary = response == null ? null : response.text();
    if (summary == null || summary.isBlank()) {
      throw new IllegalStateException("Gemini API không trả về nội dung tóm tắt.");
    }
    return summary.trim();
  }

  private String summarizeTextWithOllama(String chapterTitle, String chapterText, DocumentLanguage language) {
    // Build a prompt similar to the Gemini prompt but tailored for Ollama-powered
    // chat models.
    String system = "You are an instructor helping learners study independently. Create a detailed summary for the chapter below.";
    String userPrompt = buildGeminiPrompt(language, chapterTitle, chapterText);
    try {
      String response = callOllamaChat(system, userPrompt, Duration.ofSeconds(75));
      if (response == null || response.isBlank()) {
        throw new IllegalStateException("Ollama API không trả về phần tóm tắt hợp lệ.");
      }
      return response.trim();
    } catch (OllamaRateLimitException rateLimit) {
      // Fallback to Gemini when Ollama cloud hits rate limits or local fallback
      // fails.
      return summarizeTextWithGemini(chapterTitle, chapterText, language);
    }
  }

  private String callOllamaChat(String systemPrompt, String userPrompt, Duration timeout)
      throws IllegalStateException {
    String system = stripUnsupportedControlChars(systemPrompt);
    String user = stripUnsupportedControlChars(userPrompt);

    String apiKey = resolveOllamaApiKey();
    String host = resolveOllamaHost();
    String model = resolveOllamaModel();

    try {
      return executeOllamaChatRequest(system, user, timeout, host, model, apiKey);
    } catch (OllamaRateLimitException rateLimit) {
      if (isLikelyCloudHost(host, apiKey)) {
        String localHost = resolveOllamaLocalHost();
        if (!hostsEqual(localHost, host)) {
          String localModel = resolveOllamaLocalModel(model);
          try {
            return executeOllamaChatRequest(system, user, timeout, localHost, localModel, null);
          } catch (OllamaRateLimitException secondary) {
            throw secondary;
          } catch (IllegalStateException fallbackFailure) {
            throw new IllegalStateException(
                "Ollama cloud bị giới hạn và fallback local thất bại: " + fallbackFailure.getMessage(),
                fallbackFailure);
          }
        }
      }
      throw rateLimit;
    }
  }

  private String executeOllamaChatRequest(String system, String user, Duration timeout, String host, String model,
      String apiKey) {
    if (host == null || host.isBlank()) {
      throw new IllegalStateException("Chưa cấu hình Ollama host.");
    }
    if (model == null || model.isBlank()) {
      throw new IllegalStateException("Chưa cấu hình Ollama model.");
    }

    JsonObject requestBody = new JsonObject();
    requestBody.addProperty("model", model);
    JsonArray messages = new JsonArray();

    JsonObject systemMessage = new JsonObject();
    systemMessage.addProperty("role", "system");
    systemMessage.addProperty("content", system);
    messages.add(systemMessage);

    JsonObject userMessage = new JsonObject();
    userMessage.addProperty("role", "user");
    userMessage.addProperty("content", user);
    messages.add(userMessage);

    requestBody.add("messages", messages);
    requestBody.addProperty("stream", false);

    String requestJson = GSON.toJson(requestBody);
    Duration effectiveTimeout = (timeout == null || timeout.isNegative() || timeout.isZero())
        ? Duration.ofSeconds(60)
        : timeout;

    String endpoint = normalizeOllamaEndpoint(host);

    java.net.http.HttpRequest.Builder reqBuilder = java.net.http.HttpRequest.newBuilder()
        .uri(URI.create(endpoint))
        .timeout(effectiveTimeout)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8));

    if (apiKey != null && !apiKey.isBlank()) {
      reqBuilder.header("Authorization", "Bearer " + apiKey.trim());
    }

    java.net.http.HttpRequest req = reqBuilder.build();

    try {
      java.net.http.HttpResponse<String> resp = httpClient.send(req,
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      int status = resp.statusCode();
      if (status >= 200 && status < 300) {
        String body = resp.body();
        try {
          JsonObject root = JsonParser.parseString(body).getAsJsonObject();
          String extracted = extractOllamaResponse(root);
          if (extracted != null && !extracted.isBlank()) {
            return extracted.trim();
          }
        } catch (Exception ex) {
          if (body != null && !body.isBlank()) {
            return body.trim();
          }
        }
        throw new IllegalStateException("Ollama API không trả về nội dung văn bản.");
      }
      if (status == 429) {
        throw new OllamaRateLimitException(endpoint, resp.body());
      }
      if (status == 401 || status == 403) {
        throw new IllegalStateException("Không thể xác thực với Ollama host (HTTP " + status + ")");
      }
      throw new IllegalStateException("Ollama API trả về HTTP " + status + ": " + resp.body());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Yêu cầu Ollama bị gián đoạn: " + ex.getMessage(), ex);
    } catch (IOException ex) {
      throw new IllegalStateException("Không thể gọi Ollama API: " + ex.getMessage(), ex);
    }
  }

  private DocumentContext prepareDocumentContext(String uploadedFileName, DocumentContents document,
      List<ChapterContent> chapters) throws InterruptedException {
    EbookMetadata metadata = document.getMetadata();
    DocumentLanguage language = document.getLanguage();
    // Tài liệu có cấu trúc nếu có nhiều hơn 1 chương, hoặc có 1 chương nhưng không
    // phải "Toàn bộ sách"
    boolean isStructured = chapters.size() > 1
        || (chapters.size() == 1 && !chapters.get(0).getTitle().equals("Toàn bộ sách"));

    List<ChapterSummary> chapterSummaries = new ArrayList<>();

    if (!isStructured) {
      // Nếu không có chương rõ ràng, phân tích nội dung tài liệu
      ChapterContent fullContent = chapters.get(0);
      String analysis;
      try {
        // Phân tích nội dung tài liệu
        String prompt = buildDocumentAnalysisPrompt(language, fullContent.getBody());
        String systemPrompt = language == DocumentLanguage.VIETNAMESE
            ? "Bạn là chuyên gia phân tích tài liệu. Hãy phân tích và tóm tắt nội dung tài liệu một cách chi tiết."
            : "You are a document analysis expert. Analyze and summarize the document content in detail.";

        analysis = callOllamaChat(systemPrompt, prompt, Duration.ofSeconds(90));
        if (analysis == null || analysis.isBlank()) {
          // Fallback to Gemini
          ensureGeminiConfigured();
          GenerateContentResponse response = getGeminiClient().models.generateContent(
              geminiModelCandidates(language)[0], prompt, null);
          analysis = response != null ? response.text() : null;
          if (analysis == null || analysis.isBlank()) {
            // Final fallback: use first part of content
            String body = fullContent.getBody();
            if (body.length() > 1000) {
              analysis = body.substring(0, 1000) + "...";
            } else {
              analysis = body;
            }
          }
        }
      } catch (Exception ex) {
        // Nếu không phân tích được, sử dụng một phần nội dung
        String body = fullContent.getBody();
        if (body.length() > 1000) {
          analysis = body.substring(0, 1000) + "...";
        } else {
          analysis = body;
        }
      }
      chapterSummaries.add(new ChapterSummary("Phân tích tài liệu", analysis, fullContent.getBody()));
    } else {
      // Có nhiều chương, tóm tắt từng chương
      for (ChapterContent chapter : chapters) {
        String summary;
        try {
          // Tóm tắt chương
          summary = summarizeTextWithOllama(chapter.getTitle(), chapter.getBody(), language);
          if (summary == null || summary.isBlank()) {
            summary = summarizeTextWithGemini(chapter.getTitle(), chapter.getBody(), language);
          }
        } catch (Exception ex) {
          // Nếu không tóm tắt được, sử dụng một phần nội dung
          String body = chapter.getBody();
          if (body.length() > 500) {
            summary = body.substring(0, 500) + "...";
          } else {
            summary = body;
          }
        }
        chapterSummaries.add(new ChapterSummary(chapter.getTitle(), summary, chapter.getBody()));
      }
    }

    return new DocumentContext(uploadedFileName, metadata, chapterSummaries, language, isStructured);
  }

  private String buildDocumentAnalysisPrompt(DocumentLanguage language, String documentText) {
    String trimmedText = documentText.length() > MAX_SUMMARY_SOURCE_CHARACTERS
        ? documentText.substring(0, MAX_SUMMARY_SOURCE_CHARACTERS)
        : documentText;

    if (language == DocumentLanguage.VIETNAMESE) {
      return String.format(Locale.forLanguageTag("vi"), String.join("%n",
          "Hãy phân tích và tóm tắt nội dung tài liệu sau đây bằng tiếng Việt:",
          "- Xác định chủ đề chính và các chủ đề phụ",
          "- Liệt kê các khái niệm, công thức, hoặc điểm quan trọng",
          "- Tóm tắt nội dung một cách có cấu trúc và dễ hiểu",
          "- Nếu có ví dụ hoặc số liệu quan trọng, hãy đề cập đến",
          "Nội dung tài liệu (đã cắt ngắn nếu quá dài):",
          "%s"), trimmedText);
    }

    return String.format(Locale.ENGLISH, String.join("%n",
        "Please analyze and summarize the following document content in English:",
        "- Identify main and sub-topics",
        "- List key concepts, formulas, or important points",
        "- Summarize the content in a structured and understandable way",
        "- Mention any important examples or figures if present",
        "Document content (truncated if necessary):",
        "%s"), trimmedText);
  }

  private void handleGenerateAudioRequest(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException, InterruptedException {
    HttpSession session = request.getSession(false);
    if (session == null) {
      forwardWithError(request, response, "Phiên làm việc đã hết hạn. Vui lòng tải lên tài liệu lại.");
      return;
    }

    DocumentContext context = (DocumentContext) session.getAttribute(SESSION_ATTR_DOCUMENT_CONTEXT);
    if (context == null) {
      forwardWithError(request, response, "Không tìm thấy dữ liệu tài liệu. Vui lòng tải lên tài liệu lại.");
      return;
    }

    // Lấy chỉ số chương cần tạo audio
    String chapterIndexStr = request.getParameter("chapterIndex");
    if (chapterIndexStr == null || chapterIndexStr.isBlank()) {
      forwardWithError(request, response, "Không tìm thấy thông tin chương cần tạo audio.");
      return;
    }

    int chapterIndex;
    try {
      chapterIndex = Integer.parseInt(chapterIndexStr);
    } catch (NumberFormatException ex) {
      forwardWithError(request, response, "Chỉ số chương không hợp lệ.");
      return;
    }

    List<ChapterSummary> chapters = context.getChapters();
    if (chapterIndex < 0 || chapterIndex >= chapters.size()) {
      forwardWithError(request, response, "Chỉ số chương không hợp lệ.");
      return;
    }

    ChapterSummary chapter = chapters.get(chapterIndex);

    // Kiểm tra xem đã có audio chưa
    if (chapter.getAudioFileName() != null && !chapter.getAudioFileName().isEmpty()) {
      // Đã có audio, quay lại trang danh sách chương
      request.setAttribute("metadata", context.getMetadata());
      request.setAttribute("chapters", context.getChapters());
      request.setAttribute("uploadedFileName", context.getUploadedFileName());
      request.setAttribute("isStructuredDocument", context.isStructured());
      request.getRequestDispatcher("/chapters.jsp").forward(request, response);
      return;
    }

    Path audioDirectory = resolveAudioOutputDirectory();
    getServletContext().setAttribute("audioOutputDirectory", audioDirectory);

    // Tạo audio cho chương được chọn
    try {
      Path audioFile = generateAudioForChapter(chapter.getTitle(), chapter.getSummary(), audioDirectory,
          context.getLanguage());
      String audioFileName = audioFile.getFileName().toString();
      chapter.setAudioFileName(audioFileName);
    } catch (Exception ex) {
      forwardWithError(request, response, "Lỗi khi tạo audio cho chương: " + ex.getMessage());
      return;
    }

    // Cập nhật context trong session
    session.setAttribute(SESSION_ATTR_DOCUMENT_CONTEXT, context);
    synchronizeAudioDownloadItems(context, session);

    // Quay lại trang danh sách chương để hiển thị audio đã tạo
    request.setAttribute("metadata", context.getMetadata());
    request.setAttribute("chapters", context.getChapters());
    request.setAttribute("uploadedFileName", context.getUploadedFileName());
    request.setAttribute("isStructuredDocument", context.isStructured());

    request.getRequestDispatcher("/chapters.jsp").forward(request, response);
  }

  private Path generateAudioForChapter(String chapterTitle, String summaryText, Path audioDirectory,
      DocumentLanguage language) throws IOException, InterruptedException {
    return generateAudioWithIflytek(chapterTitle, summaryText, audioDirectory);
  }

  private void synchronizeAudioDownloadItems(DocumentContext context, HttpSession session) {
    // Method này có thể được implement sau nếu cần
    // Hiện tại chỉ để giữ tương thích với code cũ
  }

  private void handleQuestionBankResponse(HttpServletRequest request, HttpServletResponse response,
      DocumentContents document, List<ChapterContent> chapters, String uploadedFileName) throws IOException {
    DocumentLanguage language = document.getLanguage();
    String systemPrompt = language == DocumentLanguage.VIETNAMESE
        ? "Bạn là chuyên gia đo lường giáo dục. Hãy sinh ngân hàng câu hỏi trắc nghiệm thích ứng chất lượng cao."
        : "You are an educational measurement expert who produces high-quality adaptive multiple-choice banks.";

    String userPrompt = buildQuestionBankPrompt(language, document.getMetadata(), chapters);
    String rawResponse;
    try {
      rawResponse = callOllamaChat(systemPrompt, userPrompt, Duration.ofSeconds(180));
    } catch (OllamaRateLimitException rateLimit) {
      rawResponse = fallbackQuestionBankWithGemini(language, systemPrompt, userPrompt);
    }
    if (rawResponse == null || rawResponse.isBlank()) {
      throw new IllegalStateException("Hệ thống không trả về nội dung ngân hàng câu hỏi.");
    }

    List<QuestionBankItem> items = parseQuestionItems(rawResponse, language);
    if (items.size() < QUESTION_BANK_TARGET_COUNT) {
      throw new IllegalStateException(String.format(Locale.ROOT,
          "Mô hình chỉ trả về %d câu hỏi, cần tối thiểu %d câu hỏi.", items.size(), QUESTION_BANK_TARGET_COUNT));
    }
    if (items.size() > QUESTION_BANK_TARGET_COUNT) {
      items = new ArrayList<>(items.subList(0, QUESTION_BANK_TARGET_COUNT));
    }

    normalizeQuestionBankItems(items);
    assignIrtParameters(items);
    validateQuestionBankItems(items);

    JsonArray output = new JsonArray();
    int sequence = 1;
    for (QuestionBankItem item : items) {
      item.id = sequence++;
      output.add(serializeQuestionBankItem(item));
    }

    String resolvedTitle = resolveDownloadBookTitle(document.getMetadata(), uploadedFileName);
    String fileName = buildQuestionBankFilename(resolvedTitle);
    byte[] payload = GSON.toJson(output).getBytes(StandardCharsets.UTF_8);

    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setContentType("application/json; charset=UTF-8");
    response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
    response.setContentLength(payload.length);
    response.getOutputStream().write(payload);
    response.flushBuffer();
  }

  private String buildQuestionBankPrompt(DocumentLanguage language, EbookMetadata metadata,
      List<ChapterContent> chapters) {
    StringBuilder context = new StringBuilder();
    if (metadata != null) {
      context.append(language == DocumentLanguage.VIETNAMESE ? "Tiêu đề: " : "Title: ")
          .append(metadata.getTitle()).append('\n');
      context.append(language == DocumentLanguage.VIETNAMESE ? "Tác giả: " : "Author: ")
          .append(metadata.getAuthor()).append('\n');
      context.append(language == DocumentLanguage.VIETNAMESE ? "Năm xuất bản: " : "Publication year: ")
          .append(metadata.getPublicationYear()).append('\n');
      if (metadata.getSubject() != null && !metadata.getSubject().isBlank()) {
        context.append(language == DocumentLanguage.VIETNAMESE ? "Chủ đề: " : "Subject: ")
            .append(metadata.getSubject()).append('\n');
      }
      context.append('\n');
    }

    int remaining = QUESTION_BANK_MAX_CONTEXT_CHARS;
    for (ChapterContent chapter : chapters) {
      if (remaining <= 0) {
        break;
      }
      String title = chapter.getTitle() == null ? "Chương" : chapter.getTitle();
      String body = chapter.getBody() == null ? "" : chapter.getBody().trim();
      if (body.isEmpty()) {
        continue;
      }
      String truncated = body.length() > remaining ? body.substring(0, remaining) : body;
      context.append("### ").append(title).append('\n');
      context.append(truncated).append("\n\n");
      remaining -= truncated.length();
    }

    String instructions;
    if (language == DocumentLanguage.VIETNAMESE) {
      instructions = String.join("\n",
          "Hãy tạo ra CHÍNH XÁC 150 câu hỏi trắc nghiệm bốn lựa chọn dựa trên nội dung giáo trình bên dưới.",
          "Yêu cầu bắt buộc:",
          "1. Mỗi câu hỏi tập trung vào một khái niệm/h kỹ-năng quan trọng, không hỏi chung chung.",
          "2. Trả về DUY NHẤT một mảng JSON, không kèm giải thích hay văn bản khác.",
          "3. Mỗi phần tử phải có cấu trúc: {\"question\", \"options\", \"answer\", \"difficulty\"}.",
          "   - \"options\" là mảng 4 lựa chọn rõ ràng, không trùng nhau.",
          "   - \"answer\" khớp chính xác với một lựa chọn trong \"options\" (không chỉ dùng chữ cái).",
          "   - \"difficulty\" chỉ nhận một trong ba giá trị: Easy, Medium, Hard.",
          "4. Phân bố độ khó cân bằng (xấp xỉ 1/3 mỗi mức) và hỗ trợ kiểm tra thích ứng (IRT 3 tham số).",
          "5. Chỉ sử dụng thông tin tồn tại trong giáo trình. Không tự bịa dữ liệu.",
          "6. Không nhúng JSON trong mã Markdown. Không dùng chú thích thêm.",
          "Nội dung giáo trình:\n" + context.toString());
    } else {
      instructions = String.join("\n",
          "Generate EXACTLY 150 four-option multiple-choice questions from the course content below.",
          "Constraints:",
          "1. Focus each question on a precise concept or skill from the material.",
          "2. Return a single JSON array only, no additional commentary.",
          "3. Each item must include keys: {\"question\", \"options\", \"answer\", \"difficulty\"}.",
          "   - \"options\": array of four distinct, fully written choices.",
          "   - \"answer\": match exactly one option string (not just a letter).",
          "   - \"difficulty\": one of Easy, Medium, Hard.",
          "4. Keep difficulty roughly balanced (≈1/3 each) to support adaptive testing.",
          "5. Use only facts stated in the source material. No fabricated details.",
          "6. Do NOT wrap the JSON in Markdown code fences.",
          "Course corpus:\n" + context.toString());
    }
    return instructions;
  }

  private String fallbackQuestionBankWithGemini(DocumentLanguage language, String systemPrompt, String userPrompt) {
    String combinedPrompt = systemPrompt + System.lineSeparator() + System.lineSeparator() + userPrompt;
    try {
      return callGeminiForJson(combinedPrompt, language);
    } catch (IllegalStateException ex) {
      throw new IllegalStateException(
          "Ollama bị giới hạn và fallback Gemini thất bại: " + ex.getMessage(), ex);
    }
  }

  private String callGeminiForJson(String prompt, DocumentLanguage language) {
    ensureGeminiConfigured();

    GenerateContentResponse response = null;
    RuntimeException lastModelException = null;
    String[] candidates = geminiModelCandidates(language);
    if (candidates.length == 0) {
      throw new IllegalStateException("Không tìm thấy cấu hình model Gemini phù hợp cho ngôn ngữ.");
    }
    for (String modelName : candidates) {
      try {
        response = getGeminiClient().models.generateContent(modelName, prompt, null);
        lastModelException = null;
        break;
      } catch (RuntimeException ex) {
        if (isModelNotFound(ex)) {
          lastModelException = ex;
          continue;
        }
        throw new IllegalStateException("Không thể gọi Gemini API: " + ex.getMessage(), ex);
      }
    }

    if (response == null) {
      if (lastModelException != null) {
        throw new IllegalStateException(
            "Không thể gọi Gemini API với các model khả dụng. Hãy cập nhật tên model trong cấu hình.",
            lastModelException);
      }
      throw new IllegalStateException("Gemini API không trả về nội dung văn bản.");
    }

    String text = response.text();
    if (text == null || text.isBlank()) {
      throw new IllegalStateException("Gemini API không trả về nội dung văn bản.");
    }
    return text.trim();
  }

  private List<QuestionBankItem> parseQuestionItems(String rawResponse, DocumentLanguage language) {
    String payload = extractJsonPayload(rawResponse);
    if (payload == null || payload.isBlank()) {
      throw new IllegalStateException("Không thể tách JSON từ phản hồi của mô hình.");
    }

    JsonElement root;
    try {
      root = JsonParser.parseString(payload);
    } catch (RuntimeException ex) {
      throw new IllegalStateException("JSON ngân hàng câu hỏi không hợp lệ: " + ex.getMessage(), ex);
    }

    JsonArray itemsArray = resolveQuestionArray(root);
    if (itemsArray == null || itemsArray.isEmpty()) {
      throw new IllegalStateException("Không tìm thấy mảng câu hỏi trong phản hồi.");
    }

    List<QuestionBankItem> items = new ArrayList<>(itemsArray.size());
    for (JsonElement element : itemsArray) {
      if (element == null || element.isJsonNull() || !element.isJsonObject()) {
        continue;
      }
      JsonObject obj = element.getAsJsonObject();
      QuestionBankItem item = new QuestionBankItem();
      item.id = items.size() + 1;
      item.question = safeTrim(firstNonNull(
          getString(obj, "question"),
          getString(obj, "prompt"),
          getString(obj, "stem")));
      item.options = extractOptions(obj);
      item.answer = resolveAnswer(obj, item.options);
      item.difficulty = formatDifficultyLabel(firstNonNull(
          getString(obj, "difficulty"),
          getString(obj, "level"),
          getString(obj, "difficulty_level")));
      items.add(item);
    }
    return items;
  }

  private JsonArray resolveQuestionArray(JsonElement root) {
    if (root == null || root.isJsonNull()) {
      return null;
    }
    if (root.isJsonArray()) {
      return root.getAsJsonArray();
    }
    if (root.isJsonObject()) {
      JsonObject obj = root.getAsJsonObject();
      if (obj.has("questions") && obj.get("questions").isJsonArray()) {
        return obj.getAsJsonArray("questions");
      }
      if (obj.has("items") && obj.get("items").isJsonArray()) {
        return obj.getAsJsonArray("items");
      }
      for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
        if (entry.getValue() != null && entry.getValue().isJsonArray()) {
          return entry.getValue().getAsJsonArray();
        }
      }
    }
    return null;
  }

  private List<String> extractOptions(JsonObject obj) {
    List<String> options = new ArrayList<>();
    if (obj == null) {
      return options;
    }
    if (obj.has("options")) {
      JsonElement element = obj.get("options");
      if (element.isJsonArray()) {
        collectOptionsFromArray(element.getAsJsonArray(), options);
      } else if (element.isJsonObject()) {
        collectOptionsFromObject(element.getAsJsonObject(), options);
      }
    }
    if (options.size() < QUESTION_BANK_MIN_OPTIONS && obj.has("choices")) {
      JsonElement element = obj.get("choices");
      if (element.isJsonArray()) {
        collectOptionsFromArray(element.getAsJsonArray(), options);
      } else if (element.isJsonObject()) {
        collectOptionsFromObject(element.getAsJsonObject(), options);
      }
    }
    if (options.size() < QUESTION_BANK_MIN_OPTIONS && obj.has("answers") && obj.get("answers").isJsonArray()) {
      collectOptionsFromArray(obj.getAsJsonArray("answers"), options);
    }
    return options;
  }

  private void collectOptionsFromArray(JsonArray array, List<String> options) {
    for (JsonElement elem : array) {
      if (elem == null || elem.isJsonNull()) {
        continue;
      }
      if (elem.isJsonPrimitive()) {
        addOptionIfValid(options, elem.getAsString());
      } else if (elem.isJsonObject()) {
        JsonObject obj = elem.getAsJsonObject();
        if (obj.has("text")) {
          addOptionIfValid(options, obj.get("text").getAsString());
        } else if (obj.has("value")) {
          addOptionIfValid(options, obj.get("value").getAsString());
        }
      }
    }
  }

  private void collectOptionsFromObject(JsonObject object, List<String> options) {
    char[] labels = { 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H' };
    for (char label : labels) {
      String key = String.valueOf(label);
      if (object.has(key) && object.get(key).isJsonPrimitive()) {
        addOptionIfValid(options, object.get(key).getAsString());
      }
    }
    for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
      if (options.size() >= QUESTION_BANK_MIN_OPTIONS) {
        break;
      }
      JsonElement value = entry.getValue();
      if (value != null && value.isJsonPrimitive()) {
        addOptionIfValid(options, value.getAsString());
      }
    }
  }

  private void addOptionIfValid(List<String> options, String candidate) {
    String normalized = normalizeOptionText(candidate);
    if (normalized == null) {
      return;
    }
    if (!options.contains(normalized)) {
      options.add(normalized);
    }
  }

  private String resolveAnswer(JsonObject obj, List<String> options) {
    String answer = firstNonNull(
        getString(obj, "answer"),
        getString(obj, "correct_answer"),
        getString(obj, "correctOption"),
        getString(obj, "correct_option"),
        getString(obj, "solution"),
        getString(obj, "key"));

    if ((answer == null || answer.isBlank()) && obj.has("correct_option_index")
        && obj.get("correct_option_index").isJsonPrimitive()) {
      int idx = obj.get("correct_option_index").getAsInt();
      if (idx >= 0 && options != null && idx < options.size()) {
        answer = options.get(idx);
      }
    }

    if ((answer == null || answer.isBlank()) && obj.has("correct_index")
        && obj.get("correct_index").isJsonPrimitive()) {
      int idx = obj.get("correct_index").getAsInt();
      if (idx >= 0 && options != null && idx < options.size()) {
        answer = options.get(idx);
      }
    }

    if ((answer == null || answer.isBlank()) && obj.has("correct_letter")
        && obj.get("correct_letter").isJsonPrimitive()) {
      answer = obj.get("correct_letter").getAsString();
    }

    if ((answer == null || answer.isBlank()) && obj.has("correct_option_letter")
        && obj.get("correct_option_letter").isJsonPrimitive()) {
      answer = obj.get("correct_option_letter").getAsString();
    }

    return normalizeAnswer(answer, options);
  }

  private void normalizeQuestionBankItems(List<QuestionBankItem> items) {
    for (QuestionBankItem item : items) {
      if (item.question != null) {
        item.question = item.question.trim();
      }
      if (item.options == null) {
        item.options = new ArrayList<>();
      } else {
        List<String> normalized = new ArrayList<>(item.options.size());
        for (String option : item.options) {
          addOptionIfValid(normalized, option);
        }
        item.options = normalized;
      }
      item.answer = normalizeAnswer(item.answer, item.options);
      item.difficulty = formatDifficultyLabel(item.difficulty);
    }
  }

  private void validateQuestionBankItems(List<QuestionBankItem> items) {
    for (int i = 0; i < items.size(); i++) {
      QuestionBankItem item = items.get(i);
      if (item.question == null || item.question.isBlank()) {
        throw new IllegalStateException("Câu hỏi thứ " + (i + 1) + " thiếu nội dung.");
      }
      if (item.options == null || item.options.size() < QUESTION_BANK_MIN_OPTIONS) {
        throw new IllegalStateException(
            "Câu hỏi thứ " + (i + 1) + " không có đủ lựa chọn (cần tối thiểu " + QUESTION_BANK_MIN_OPTIONS + ").");
      }
      if (item.answer == null || item.answer.isBlank()) {
        throw new IllegalStateException("Câu hỏi thứ " + (i + 1) + " thiếu đáp án khớp lựa chọn.");
      }
      if (!item.options.contains(item.answer)) {
        throw new IllegalStateException(
            "Đáp án của câu hỏi thứ " + (i + 1) + " không khớp bất kỳ lựa chọn nào.");
      }
    }
  }

  private JsonObject serializeQuestionBankItem(QuestionBankItem item) {
    JsonObject obj = new JsonObject();
    obj.addProperty("id", item.id);
    obj.addProperty("question", item.question);
    JsonArray optionsArray = new JsonArray();
    for (String option : item.options) {
      optionsArray.add(option);
    }
    obj.add("options", optionsArray);
    obj.addProperty("answer", item.answer);
    obj.addProperty("difficulty", item.difficulty);
    obj.addProperty("param_a", roundToThreeDecimals(item.param_a));
    obj.addProperty("param_b", roundToThreeDecimals(item.param_b));
    obj.addProperty("param_c", roundToThreeDecimals(item.param_c));
    return obj;
  }

  private void assignIrtParameters(List<QuestionBankItem> items) {
    if (items.isEmpty()) {
      return;
    }
    int total = items.size();
    int easyTarget = Math.max(0, Math.min(total, (int) Math.round(total * QUESTION_BANK_EASY_RATIO)));
    int hardTarget = Math.max(0, Math.min(total, (int) Math.round(total * QUESTION_BANK_HARD_RATIO)));
    int mediumTarget = Math.max(0, total - easyTarget - hardTarget);

    int easyCount = 0;
    int mediumCount = 0;
    int hardCount = 0;

    for (QuestionBankItem item : items) {
      String difficultyLabel = formatDifficultyLabel(item.difficulty);
      DifficultyRange preferred = DifficultyRange.forDifficulty(difficultyLabel);
      DifficultyRange assigned = rebalanceRange(preferred, easyCount, easyTarget, mediumCount, mediumTarget, hardCount,
          hardTarget);
      switch (assigned) {
        case EASY:
          easyCount++;
          item.difficulty = "Easy";
          break;
        case HARD:
          hardCount++;
          item.difficulty = "Hard";
          break;
        case MEDIUM:
        default:
          mediumCount++;
          item.difficulty = "Medium";
          break;
      }

      ThreadLocalRandom rng = ThreadLocalRandom.current();
      item.param_a = rng.nextDouble(assigned.aMin, assigned.aMax);
      item.param_b = rng.nextDouble(assigned.bMin, assigned.bMax);
      item.param_c = rng.nextDouble(assigned.cMin, assigned.cMax);
    }
  }

  private DifficultyRange rebalanceRange(DifficultyRange preferred, int easyCount, int easyTarget, int mediumCount,
      int mediumTarget, int hardCount, int hardTarget) {
    if (preferred == DifficultyRange.EASY && easyCount < easyTarget) {
      return preferred;
    }
    if (preferred == DifficultyRange.MEDIUM && mediumCount < mediumTarget) {
      return preferred;
    }
    if (preferred == DifficultyRange.HARD && hardCount < hardTarget) {
      return preferred;
    }

    if (mediumCount < mediumTarget) {
      return DifficultyRange.MEDIUM;
    }
    if (easyCount < easyTarget) {
      return DifficultyRange.EASY;
    }
    if (hardCount < hardTarget) {
      return DifficultyRange.HARD;
    }
    return preferred;
  }

  private double roundToThreeDecimals(double value) {
    return Math.round(value * 1000d) / 1000d;
  }

  private String buildQuestionBankFilename(String baseTitle) {
    String sanitized = sanitizeForFilename(baseTitle == null ? "question-bank" : baseTitle);
    if (sanitized.isBlank()) {
      sanitized = "question-bank";
    }
    String timestamp = ZonedDateTime.now(ZoneOffset.UTC).format(QUESTION_BANK_FILENAME_FORMATTER);
    return sanitized + "-question-bank-" + timestamp + ".json";
  }

  private String extractJsonPayload(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.startsWith("```")) {
      int firstBreak = trimmed.indexOf('\n');
      if (firstBreak >= 0) {
        trimmed = trimmed.substring(firstBreak + 1);
      }
      int fence = trimmed.lastIndexOf("```");
      if (fence >= 0) {
        trimmed = trimmed.substring(0, fence);
      }
      trimmed = trimmed.trim();
    }
    try {
      JsonElement element = JsonParser.parseString(trimmed);
      if (element.isJsonArray() || element.isJsonObject()) {
        return trimmed;
      }
    } catch (RuntimeException ignore) {
      // Fall back to substring detection below.
    }
    int arrayStart = trimmed.indexOf('[');
    int arrayEnd = trimmed.lastIndexOf(']');
    if (arrayStart >= 0 && arrayEnd > arrayStart) {
      return trimmed.substring(arrayStart, arrayEnd + 1);
    }
    int objectStart = trimmed.indexOf('{');
    int objectEnd = trimmed.lastIndexOf('}');
    if (objectStart >= 0 && objectEnd > objectStart) {
      return trimmed.substring(objectStart, objectEnd + 1);
    }
    return null;
  }

  private String normalizeOptionText(String value) {
    if (value == null) {
      return null;
    }
    String stripped = value.trim();
    stripped = stripped.replaceFirst("^(?i)[A-H]\\s*[:\\).\\-]+\\s*", "");
    stripped = stripped.replaceFirst("^\\u2022\\s*", "");
    stripped = stripped.replaceFirst("^[-\\u2022]\\s*", "");
    stripped = stripped.replaceFirst("^\\d+\\s*[\\).\\-]+\\s*", "");
    stripped = stripped.trim();
    return stripped.isEmpty() ? null : stripped;
  }

  private String normalizeAnswer(String rawAnswer, List<String> options) {
    if (rawAnswer == null) {
      return null;
    }
    String trimmed = rawAnswer.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (options != null && !options.isEmpty()) {
      char first = Character.toUpperCase(trimmed.charAt(0));
      if (first >= 'A' && first <= 'H') {
        int idx = first - 'A';
        if (idx < options.size()) {
          return options.get(idx);
        }
      }
      for (String option : options) {
        if (trimmed.equalsIgnoreCase(option)) {
          return option;
        }
      }
    }
    return trimmed;
  }

  private String formatDifficultyLabel(String source) {
    if (source == null || source.isBlank()) {
      return "Medium";
    }
    String normalized = source.trim().toLowerCase(Locale.ROOT);
    switch (normalized) {
      case "easy":
      case "dễ":
      case "de":
      case "beginner":
        return "Easy";
      case "hard":
      case "khó":
      case "kho":
      case "advanced":
      case "difficult":
        return "Hard";
      default:
        return "Medium";
    }
  }

  private String firstNonNull(String... candidates) {
    if (candidates == null) {
      return null;
    }
    for (String candidate : candidates) {
      if (candidate != null && !candidate.isBlank()) {
        return candidate;
      }
    }
    return null;
  }

  private String getString(JsonObject obj, String property) {
    if (obj == null || property == null || !obj.has(property)) {
      return null;
    }
    JsonElement element = obj.get(property);
    if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
      return null;
    }
    return element.getAsString();
  }

  private String extractOllamaResponse(JsonObject root) {
    if (root == null) {
      return null;
    }

    if (root.has("output") && root.get("output").isJsonArray()) {
      for (var elem : root.getAsJsonArray("output")) {
        if (!elem.isJsonObject()) {
          continue;
        }
        JsonObject obj = elem.getAsJsonObject();
        String viaContent = extractContentParts(obj.get("content"));
        if (viaContent != null && !viaContent.isBlank()) {
          return viaContent;
        }
        String viaMessage = extractContentParts(obj.get("message"));
        if (viaMessage != null && !viaMessage.isBlank()) {
          return viaMessage;
        }
      }
    }

    if (root.has("choices") && root.get("choices").isJsonArray()) {
      for (var choice : root.getAsJsonArray("choices")) {
        if (!choice.isJsonObject()) {
          continue;
        }
        JsonObject co = choice.getAsJsonObject();
        String viaMessage = extractContentParts(co.get("message"));
        if (viaMessage != null && !viaMessage.isBlank()) {
          return viaMessage;
        }
      }
    }

    if (root.has("message")) {
      String viaMessage = extractContentParts(root.get("message"));
      if (viaMessage != null && !viaMessage.isBlank()) {
        return viaMessage;
      }
    }

    if (root.has("response") && root.get("response").isJsonPrimitive()) {
      return root.get("response").getAsString();
    }

    if (root.has("output_text") && root.get("output_text").isJsonPrimitive()) {
      return root.get("output_text").getAsString();
    }

    if (root.has("content") && root.get("content").isJsonPrimitive()) {
      return root.get("content").getAsString();
    }

    return null;
  }

  private String extractContentParts(com.google.gson.JsonElement element) {
    if (element == null || element.isJsonNull()) {
      return null;
    }

    if (element.isJsonPrimitive()) {
      return element.getAsString();
    }

    if (!element.isJsonObject()) {
      return null;
    }

    JsonObject obj = element.getAsJsonObject();
    if (obj.has("content") && obj.get("content").isJsonPrimitive()) {
      return obj.get("content").getAsString();
    }

    if (obj.has("parts") && obj.get("parts").isJsonArray()) {
      StringBuilder sb = new StringBuilder();
      obj.getAsJsonArray("parts").forEach(p -> sb.append(p.getAsString()));
      return sb.toString();
    }

    if (obj.has("message")) {
      return extractContentParts(obj.get("message"));
    }

    if (obj.has("content") && obj.get("content").isJsonObject()) {
      return extractContentParts(obj.get("content"));
    }

    return null;
  }

  private String stripUnsupportedControlChars(String input) {
    if (input == null || input.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder(input.length());
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      if (c >= 0x20 || c == '\n' || c == '\r' || c == '\t') {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  // Ollama config resolvers - read from environment variables or system
  // properties.
  private String resolveOllamaApiKey() {
    String v = System.getenv("OLLAMA_API_KEY");
    if (v == null || v.isBlank()) {
      v = System.getProperty("ollama.api.key");
    }
    if (v == null || v.isBlank()) {
      return null;
    }
    return v.trim();
  }

  private String resolveOllamaHost() {
    String v = System.getenv("OLLAMA_HOST");
    if (v == null || v.isBlank()) {
      v = System.getProperty("ollama.host");
    }
    String apiKey = resolveOllamaApiKey();
    if (v == null || v.isBlank()) {
      return (apiKey != null && !apiKey.isBlank()) ? OLLAMA_DEFAULT_CLOUD_HOST : OLLAMA_DEFAULT_LOCAL_HOST;
    }
    return v.trim();
  }

  private String resolveOllamaModel() {
    String v = System.getenv("OLLAMA_MODEL");
    if (v == null || v.isBlank()) {
      v = System.getProperty("ollama.model");
    }
    String apiKey = resolveOllamaApiKey();
    if (v == null || v.isBlank()) {
      return (apiKey != null && !apiKey.isBlank()) ? OLLAMA_DEFAULT_CLOUD_MODEL : OLLAMA_DEFAULT_LOCAL_MODEL;
    }
    return v.trim();
  }

  private String resolveOllamaLocalHost() {
    String v = System.getenv("OLLAMA_LOCAL_HOST");
    if (v == null || v.isBlank()) {
      v = System.getProperty("ollama.local.host");
    }
    if (v == null || v.isBlank()) {
      return OLLAMA_DEFAULT_LOCAL_HOST;
    }
    return v.trim();
  }

  private String resolveOllamaLocalModel(String primaryModel) {
    String v = System.getenv("OLLAMA_LOCAL_MODEL");
    if (v == null || v.isBlank()) {
      v = System.getProperty("ollama.local.model");
    }
    if (v == null || v.isBlank()) {
      if (primaryModel != null && !primaryModel.isBlank()) {
        return primaryModel.trim();
      }
      return OLLAMA_DEFAULT_LOCAL_MODEL;
    }
    return v.trim();
  }

  private boolean isLikelyCloudHost(String host, String apiKey) {
    if (apiKey != null && !apiKey.isBlank()) {
      return true;
    }
    if (host == null || host.isBlank()) {
      return false;
    }
    String normalized = host.trim().toLowerCase(Locale.ROOT);
    return normalized.contains("ollama.com") || normalized.contains("cloud");
  }

  private String normalizeOllamaEndpoint(String host) {
    String base = normalizeHostBase(host);
    if (base.endsWith("/api/chat")) {
      return base;
    }
    if (base.endsWith("/api/chat/")) {
      return base.substring(0, base.length() - 1);
    }
    if (base.endsWith("/")) {
      return base + "api/chat";
    }
    return base + "/api/chat";
  }

  private boolean hostsEqual(String a, String b) {
    return normalizeHostBase(a).equals(normalizeHostBase(b));
  }

  private String normalizeHostBase(String host) {
    if (host == null) {
      return "";
    }
    String trimmed = host.trim();
    if (trimmed.endsWith("/")) {
      return trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed;
  }

  private String resolveDownloadBookTitle(EbookMetadata metadata, String uploadedFileName) {
    String candidate = metadata != null ? safeTrim(metadata.getTitle()) : null;
    if (candidate != null && candidate.equalsIgnoreCase("không rõ")) {
      candidate = null;
    }
    if (candidate == null || candidate.isBlank()) {
      candidate = humanizeFilenameStem(extractBaseName(uploadedFileName));
    }
    if (candidate == null || candidate.isBlank()) {
      candidate = "ebook";
    }
    return candidate;
  }

  private void configureZipSecureFileLimits() {
    if (docxZipSecureConfigured) {
      return;
    }
    synchronized (EbookProcessorServlet.class) {
      if (docxZipSecureConfigured) {
        return;
      }
      int maxFileCount = resolvePoiMaxFileCount();
      double minInflateRatio = resolvePoiMinInflateRatio();
      ZipSecureFile.setMaxFileCount(maxFileCount);
      ZipSecureFile.setMinInflateRatio(minInflateRatio);
      docxZipSecureConfigured = true;
    }
  }

  private static int resolvePoiMaxFileCount() {
    return resolveIntConfig("POI_MAX_FILE_COUNT", "poi.max.file.count", DEFAULT_POI_MAX_FILE_COUNT,
        MIN_POI_MAX_FILE_COUNT, MAX_POI_MAX_FILE_COUNT);
  }

  private static double resolvePoiMinInflateRatio() {
    return resolveDoubleConfig("POI_MIN_INFLATE_RATIO", "poi.min.inflate.ratio", DEFAULT_POI_MIN_INFLATE_RATIO,
        MIN_POI_MIN_INFLATE_RATIO, MAX_POI_MIN_INFLATE_RATIO);
  }

  private static int resolveIntConfig(String envName, String propertyName, int defaultValue, int minValue,
      int maxValue) {
    String value = System.getenv(envName);
    if (value == null || value.isBlank()) {
      value = System.getProperty(propertyName);
    }
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      int parsed = Integer.parseInt(value.trim());
      if (parsed < minValue || parsed > maxValue) {
        return defaultValue;
      }
      return parsed;
    } catch (NumberFormatException ex) {
      return defaultValue;
    }
  }

  private static double resolveDoubleConfig(String envName, String propertyName, double defaultValue, double minValue,
      double maxValue) {
    String value = System.getenv(envName);
    if (value == null || value.isBlank()) {
      value = System.getProperty(propertyName);
    }
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      double parsed = Double.parseDouble(value.trim());
      if (Double.isNaN(parsed) || parsed < minValue || parsed > maxValue) {
        return defaultValue;
      }
      return parsed;
    } catch (NumberFormatException ex) {
      return defaultValue;
    }
  }

  private String[] geminiModelCandidates(DocumentLanguage language) {
    switch (language) {
      case VIETNAMESE:
        return GEMINI_MODEL_VI;
      case ENGLISH:
      default:
        return GEMINI_MODEL_EN;
    }
  }

  private String buildGeminiPrompt(DocumentLanguage language, String chapterTitle, String trimmedText) {
    if (language == DocumentLanguage.VIETNAMESE) {
      return String.format(Locale.forLanguageTag("vi"), String.join("%n",
          "Bạn là giảng viên đang hướng dẫn học viên tự học. Hãy tạo bản tóm tắt chi tiết cho chương dưới đây bằng tiếng Việt.",
          "Yêu cầu:",
          "- Viết phần mở đầu 2-3 câu mô tả mục tiêu trọng tâm của chương.",
          "- Liệt kê 4-6 gạch đầu dòng về khái niệm, công thức, bước thực hành quan trọng (giữ nguyên thuật ngữ chuyên ngành).",
          "- Kết thúc bằng 1-2 câu nêu ứng dụng hoặc lưu ý khi học.",
          "- Không bỏ qua ví dụ, số liệu nổi bật nếu có trong nội dung.",
          "- Thêm 3-4 câu hỏi ôn tập ngắn gọn ở cuối (đánh số).",
          "Tiêu đề chương: %s",
          "Nội dung nguồn (đã cắt ngắn nếu quá dài):",
          "%s"), chapterTitle, trimmedText);
    }

    return String.format(Locale.ENGLISH, String.join("%n",
        "You are an instructor helping learners study independently. Create a detailed summary for the chapter below in English.",
        "Instructions:",
        "- Start with a 2-3 sentence introduction highlighting the chapter's main goals.",
        "- Provide 4-6 bullet points covering key concepts, formulas, and practice steps (keep technical terminology intact).",
        "- Conclude with 1-2 sentences describing practical applications or study tips.",
        "- Retain any notable examples or figures present in the source text.",
        "- Add 3-4 short revision questions at the end (numbered).",
        "Chapter title: %s",
        "Source content (truncated if necessary):",
        "%s"), chapterTitle, trimmedText);
  }

  private Client getGeminiClient() {
    Client existing = geminiClient;
    if (existing == null) {
      synchronized (this) {
        if (geminiClient == null) {
          String apiKey = resolveGeminiApiKey();
          if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "Không thể khởi tạo Gemini client vì thiếu API key.");
          }
          apiKey = apiKey.trim();

          geminiClient = Client.builder()
              .apiKey(apiKey)
              .build();
        }
        existing = geminiClient;
      }
    }
    return existing;
  }

  private String resolveGeminiApiKey() {
    String key = System.getenv("GOOGLE_API_KEY");
    if (key == null || key.isBlank()) {
      key = System.getenv("GEMINI_API_KEY");
    }
    if (key == null || key.isBlank()) {
      key = System.getProperty("google.api.key");
    }
    if (key == null || key.isBlank()) {
      key = System.getProperty("gemini.api.key");
    }
    if ((key == null || key.isBlank()) && GEMINI_API_KEY != null && !GEMINI_API_KEY.trim().isEmpty()
        && !GEMINI_API_KEY.contains("PASTE")) {
      key = GEMINI_API_KEY.trim();
    }
    return key;
  }

  private String resolveIflytekAppId() {
    String value = System.getenv("IFLYTEK_APP_ID");
    if (value == null || value.isBlank()) {
      value = System.getProperty("iflytek.app.id");
    }
    if (value == null || value.isBlank()) {
      value = IFLYTEK_APP_ID;
    }
    if (value != null) {
      value = value.trim();
    }
    if (value == null || value.isEmpty() || value.contains("REPLACE")) {
      return null;
    }
    return value;
  }

  private String resolveIflytekApiKey() {
    String value = System.getenv("IFLYTEK_API_KEY");
    if (value == null || value.isBlank()) {
      value = System.getProperty("iflytek.api.key");
    }
    if (value == null || value.isBlank()) {
      value = IFLYTEK_API_KEY;
    }
    if (value != null) {
      value = value.trim();
    }
    if (value == null || value.isEmpty() || value.contains("REPLACE")) {
      return null;
    }
    return value;
  }

  private String resolveIflytekApiSecret() {
    String value = System.getenv("IFLYTEK_API_SECRET");
    if (value == null || value.isBlank()) {
      value = System.getProperty("iflytek.api.secret");
    }
    if (value == null || value.isBlank()) {
      value = IFLYTEK_API_SECRET;
    }
    if (value != null) {
      value = value.trim();
    }
    if (value == null || value.isEmpty() || value.contains("REPLACE")) {
      return null;
    }
    return value;
  }

  private String resolveIflytekEndpoint() {
    String endpoint = System.getenv("IFLYTEK_TTS_ENDPOINT");
    if (endpoint == null || endpoint.isBlank()) {
      endpoint = System.getProperty("iflytek.tts.endpoint");
    }
    if (endpoint == null || endpoint.isBlank()) {
      endpoint = IFLYTEK_TTS_ENDPOINT;
    }
    if (endpoint != null) {
      endpoint = endpoint.trim();
    }
    if (endpoint == null || endpoint.isEmpty()) {
      throw new IllegalStateException(
          "Chưa cấu hình endpoint iFLYTEK TTS hợp lệ. Hãy đặt biến môi trường IFLYTEK_TTS_ENDPOINT hoặc cập nhật hằng mặc định.");
    }
    return endpoint;
  }

  private String resolveIflytekVoice() {
    String voice = System.getenv("IFLYTEK_TTS_VOICE");
    if (voice == null || voice.isBlank()) {
      voice = System.getProperty("iflytek.tts.voice");
    }
    if (voice == null || voice.isBlank()) {
      voice = IFLYTEK_DEFAULT_VOICE;
    }
    if (voice != null) {
      voice = voice.trim();
    }
    return voice;
  }

  private String resolveIflytekAue() {
    String aue = System.getenv("IFLYTEK_TTS_AUE");
    if (aue == null || aue.isBlank()) {
      aue = System.getProperty("iflytek.tts.aue");
    }
    if (aue == null || aue.isBlank()) {
      aue = IFLYTEK_DEFAULT_AUE;
    }
    if (aue != null) {
      aue = aue.trim();
    }
    return aue;
  }

  private String resolveIflytekAuf() {
    String auf = System.getenv("IFLYTEK_TTS_AUF");
    if (auf == null || auf.isBlank()) {
      auf = System.getProperty("iflytek.tts.auf");
    }
    if (auf == null || auf.isBlank()) {
      auf = IFLYTEK_DEFAULT_AUF;
    }
    if (auf != null) {
      auf = auf.trim();
    }
    return auf;
  }

  private String resolveIflytekTte() {
    String tte = System.getenv("IFLYTEK_TTS_TTE");
    if (tte == null || tte.isBlank()) {
      tte = System.getProperty("iflytek.tts.tte");
    }
    if (tte == null || tte.isBlank()) {
      tte = IFLYTEK_DEFAULT_TTE;
    }
    if (tte != null) {
      tte = tte.trim();
    }
    return tte;
  }

  private Integer resolveIflytekSpeed() {
    return resolveIflytekIntSetting("IFLYTEK_TTS_SPEED", "iflytek.tts.speed", 0, 100);
  }

  private Integer resolveIflytekVolume() {
    return resolveIflytekIntSetting("IFLYTEK_TTS_VOLUME", "iflytek.tts.volume", 0, 100);
  }

  private Integer resolveIflytekPitch() {
    return resolveIflytekIntSetting("IFLYTEK_TTS_PITCH", "iflytek.tts.pitch", 0, 100);
  }

  private Integer resolveIflytekBgs() {
    return resolveIflytekIntSetting("IFLYTEK_TTS_BGS", "iflytek.tts.bgs", 0, 1);
  }

  private Integer resolveIflytekReg() {
    return resolveIflytekIntSetting("IFLYTEK_TTS_REG", "iflytek.tts.reg", 0, 2);
  }

  private Integer resolveIflytekRdn() {
    return resolveIflytekIntSetting("IFLYTEK_TTS_RDN", "iflytek.tts.rdn", 0, 3);
  }

  private Integer resolveIflytekIntSetting(String envName, String propertyName, int min, int max) {
    String value = System.getenv(envName);
    if (value == null || value.isBlank()) {
      value = System.getProperty(propertyName);
    }
    if (value == null || value.isBlank()) {
      return null;
    }
    String trimmed = value.trim();
    try {
      int parsed = Integer.parseInt(trimmed);
      if (parsed < min || parsed > max) {
        throw new IllegalStateException(String.format(Locale.ROOT,
            "Giá trị %s cho tham số %s nằm ngoài khoảng [%d, %d]", trimmed, envName, min, max));
      }
      return parsed;
    } catch (NumberFormatException ex) {
      throw new IllegalStateException(String.format(Locale.ROOT,
          "Không thể phân tích giá trị '%s' cho tham số %s", trimmed, envName), ex);
    }
  }

  private Path generateAudioWithIflytek(String chapterTitle, String summaryText, Path audioDirectory)
      throws IOException, InterruptedException {
    if (shouldUseLocalPreviewMode()) {
      return generateLocalPreviewAudio(chapterTitle, summaryText, audioDirectory);
    }

    ensureIflytekConfigured();

    String endpoint = resolveIflytekEndpoint();
    String apiKey = resolveIflytekApiKey();
    String apiSecret = resolveIflytekApiSecret();
    String appId = resolveIflytekAppId();
    String voice = resolveIflytekVoice();
    String aue = resolveIflytekAue();
    String auf = resolveIflytekAuf();
    String tte = resolveIflytekTte();
    Integer speed = resolveIflytekSpeed();
    Integer volume = resolveIflytekVolume();
    Integer pitch = resolveIflytekPitch();
    Integer bgs = resolveIflytekBgs();
    Integer reg = resolveIflytekReg();
    Integer rdn = resolveIflytekRdn();

    String preparedText = prepareTextForIflytek(summaryText);
    String encodedText = encodeIflytekText(preparedText, tte);

    JsonObject common = new JsonObject();
    common.addProperty("app_id", appId);

    JsonObject business = new JsonObject();
    business.addProperty("vcn", voice);
    business.addProperty("aue", aue);
    business.addProperty("tte", tte);
    if (auf != null && !auf.isBlank()) {
      business.addProperty("auf", auf);
    }
    if (speed != null) {
      business.addProperty("speed", speed);
    }
    if (volume != null) {
      business.addProperty("volume", volume);
    }
    if (pitch != null) {
      business.addProperty("pitch", pitch);
    }
    if (bgs != null) {
      business.addProperty("bgs", bgs);
    }
    if (reg != null) {
      business.addProperty("reg", reg);
    }
    if (rdn != null) {
      business.addProperty("rdn", rdn);
    }
    if ("lame".equalsIgnoreCase(aue)) {
      business.addProperty("sfl", 1);
    }

    JsonObject data = new JsonObject();
    data.addProperty("status", 2);
    data.addProperty("text", encodedText);
    if (tte != null && !tte.isBlank()) {
      data.addProperty("encoding", tte.toLowerCase(Locale.ROOT));
    }

    JsonObject payload = new JsonObject();
    payload.add("common", common);
    payload.add("business", business);
    payload.add("data", data);

    IflytekAuthContext authContext = buildIflytekAuthContext(endpoint, apiKey, apiSecret);

    IflytekTtsListener listener = new IflytekTtsListener(payload.toString());
    WebSocket webSocket;
    try {
      webSocket = httpClient.newWebSocketBuilder()
          .header("Date", authContext.getDateHeader())
          .header("X-Date", authContext.getDateHeader())
          .connectTimeout(IFLYTEK_WS_TIMEOUT)
          .buildAsync(authContext.getUri(), listener)
          .join();
    } catch (RuntimeException ex) {
      String diagnostic = extractIflytekHandshakeDiagnostic(ex);
      throw new IllegalStateException("Không thể kết nối tới iFLYTEK TTS WebSocket: " + diagnostic, ex);
    }

    try {
      listener.awaitCompletion(IFLYTEK_WS_TIMEOUT);
    } finally {
      if (!listener.isClosed()) {
        try {
          webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "completed").join();
        } catch (Exception ignore) {
          webSocket.abort();
        }
      }
    }

    byte[] audioBytes = listener.getAudioBytes();
    if (audioBytes.length == 0) {
      throw new IllegalStateException("iFLYTEK TTS không trả về dữ liệu audio hợp lệ.");
    }

    String sanitized = sanitizeForFilename(chapterTitle);
    if (sanitized.isBlank()) {
      sanitized = "chapter" + System.nanoTime();
    }

    String extension = determineIflytekExtension(aue);
    Path audioFile = ensureUniqueFile(audioDirectory.resolve(sanitized + extension));
    Files.write(audioFile, audioBytes);
    return audioFile;
  }

  private String prepareTextForIflytek(String text) {
    if (text == null) {
      return "";
    }

    String normalized = text.trim();
    if (normalized.length() <= IFLYTEK_TEXT_CHAR_LIMIT) {
      return normalized;
    }

    int available = IFLYTEK_TEXT_CHAR_LIMIT;
    String notice = IFLYTEK_TRUNCATION_NOTICE;
    boolean canAppendNotice = notice != null && !notice.isBlank() && notice.length() < available;
    int allowedLength = canAppendNotice ? available - notice.length() : available;

    int cutoff = findPreferredTruncationPoint(normalized, allowedLength);
    String truncated = normalized.substring(0, cutoff).trim();

    if (canAppendNotice) {
      String candidate = truncated + notice;
      if (candidate.length() <= IFLYTEK_TEXT_CHAR_LIMIT) {
        return candidate;
      }
    }

    if (truncated.length() > IFLYTEK_TEXT_CHAR_LIMIT) {
      truncated = truncated.substring(0, IFLYTEK_TEXT_CHAR_LIMIT);
    }
    return truncated;
  }

  private int findPreferredTruncationPoint(String text, int maxLength) {
    if (text.length() <= maxLength) {
      return text.length();
    }

    int cutoff = Math.max(text.lastIndexOf('\n', maxLength), text.lastIndexOf('.', maxLength));
    cutoff = Math.max(cutoff, text.lastIndexOf('!', maxLength));
    cutoff = Math.max(cutoff, text.lastIndexOf('?', maxLength));
    cutoff = Math.max(cutoff, text.lastIndexOf(';', maxLength));
    cutoff = Math.max(cutoff, text.lastIndexOf(':', maxLength));
    cutoff = Math.max(cutoff, text.lastIndexOf('-', maxLength));
    cutoff = Math.max(cutoff, text.lastIndexOf(' ', maxLength));

    if (cutoff < maxLength / 2) {
      cutoff = maxLength;
    }

    cutoff = Math.max(1, Math.min(cutoff, text.length()));
    return cutoff;
  }

  private String encodeIflytekText(String text, String tte) {
    Charset charset = charsetForTte(tte);
    byte[] bytes = text.getBytes(charset);
    return Base64.getEncoder().encodeToString(bytes);
  }

  private Charset charsetForTte(String tte) {
    String normalized = tte == null ? "" : tte.trim().toUpperCase(Locale.ROOT);
    switch (normalized) {
      case "GB2312":
        return Charset.forName("GB2312");
      case "GBK":
        return Charset.forName("GBK");
      case "BIG5":
        return Charset.forName("Big5");
      case "GB18030":
        return Charset.forName("GB18030");
      case "UNICODE":
        return Charset.forName("UTF-16LE");
      case "UTF8":
      case "UTF-8":
      default:
        return StandardCharsets.UTF_8;
    }
  }

  private String determineIflytekExtension(String aue) {
    if (aue == null || aue.isBlank()) {
      return ".mp3";
    }
    String normalized = aue.trim().toLowerCase(Locale.ROOT);
    switch (normalized) {
      case "raw":
      case "pcm":
      case "linear16":
        return ".pcm";
      case "wav":
        return ".wav";
      case "speex":
      case "speex-wb":
      case "speex-org-wb":
      case "speex-org-nb":
        return ".speex";
      case "lame":
      default:
        return ".mp3";
    }
  }

  private IflytekAuthContext buildIflytekAuthContext(String endpoint, String apiKey, String apiSecret) {
    URI baseUri = URI.create(endpoint);
    String host = baseUri.getHost();
    if (host == null || host.isEmpty()) {
      throw new IllegalStateException("Endpoint iFLYTEK không hợp lệ (thiếu host).");
    }
    String path = baseUri.getPath();
    if (path == null || path.isEmpty()) {
      path = "/";
    }

    String date = IFLYTEK_RFC1123_FORMATTER.format(ZonedDateTime.now(ZoneOffset.UTC));
    String requestLine = "GET " + path + " HTTP/1.1";
    String signatureOrigin = String.join("\n", "host: " + host, "date: " + date, requestLine);
    String signature = hmacSha256Base64(signatureOrigin, apiSecret);

    String authorizationOrigin = String.format(Locale.ROOT,
        "api_key=\"%s\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"%s\"",
        apiKey, signature);
    String authorization = Base64.getEncoder().encodeToString(authorizationOrigin.getBytes(StandardCharsets.UTF_8));

    Map<String, String> queryParams = new LinkedHashMap<>();
    queryParams.put("authorization", authorization);
    queryParams.put("date", date);
    queryParams.put("host", host);

    StringBuilder uriBuilder = new StringBuilder(endpoint);
    if (!queryParams.isEmpty()) {
      uriBuilder.append(endpoint.contains("?") ? (endpoint.endsWith("?") || endpoint.endsWith("&") ? "" : "&")
          : "?");
      boolean first = true;
      for (Map.Entry<String, String> entry : queryParams.entrySet()) {
        if (!first) {
          uriBuilder.append('&');
        }
        uriBuilder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
        uriBuilder.append('=');
        uriBuilder.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        first = false;
      }
    }
    return new IflytekAuthContext(URI.create(uriBuilder.toString()), date, host);
  }

  private String hmacSha256Base64(String data, String secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
      mac.init(keySpec);
      byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(raw);
    } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
      throw new IllegalStateException("Không thể tạo chữ ký HMAC-SHA256 cho iFLYTEK.", ex);
    }
  }

  private static final class IflytekAuthContext {
    private final URI uri;
    private final String dateHeader;
    @SuppressWarnings("unused")
    private final String host;

    private IflytekAuthContext(URI uri, String dateHeader, String host) {
      this.uri = uri;
      this.dateHeader = dateHeader;
      this.host = host;
    }

    private URI getUri() {
      return uri;
    }

    private String getDateHeader() {
      return dateHeader;
    }
  }

  private String extractIflytekHandshakeDiagnostic(Throwable error) {
    Throwable cause = error;
    while (cause != null) {
      if (cause instanceof WebSocketHandshakeException) {
        WebSocketHandshakeException handshakeException = (WebSocketHandshakeException) cause;
        HttpResponse<?> response = handshakeException.getResponse();
        if (response != null) {
          StringBuilder detail = new StringBuilder();
          detail.append("HTTP ").append(response.statusCode());
          Object body = response.body();
          if (body != null) {
            String bodyText = body.toString();
            if (bodyText.length() > 512) {
              bodyText = bodyText.substring(0, 512) + "...";
            }
            if (!bodyText.isBlank()) {
              detail.append(" - ").append(bodyText);
            }
          }
          return detail.toString();
        }
        String message = handshakeException.getMessage();
        if (message != null && !message.isBlank()) {
          return message;
        }
      }
      cause = cause.getCause();
    }
    String fallback = error.getMessage();
    if (fallback == null || fallback.isBlank()) {
      fallback = error.toString();
    }
    return fallback;
  }

  private static final class IflytekTtsListener implements WebSocket.Listener {
    private final String requestPayload;
    private final ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private final StringBuilder textBuffer = new StringBuilder();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private IflytekTtsListener(String requestPayload) {
      this.requestPayload = requestPayload;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.sendText(requestPayload, true);
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      textBuffer.append(data);
      if (last) {
        String message = textBuffer.toString();
        textBuffer.setLength(0);
        try {
          handleJsonMessage(message);
        } catch (RuntimeException ex) {
          completion.completeExceptionally(ex);
          webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "client error");
          return CompletableFuture.completedFuture(null);
        }
      }
      webSocket.request(1);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, java.nio.ByteBuffer data, boolean last) {
      webSocket.request(1);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      closed.set(true);
      if (!completion.isDone()) {
        completion.complete(null);
      }
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      completion.completeExceptionally(error);
    }

    private void handleJsonMessage(String message) {
      JsonObject root = JsonParser.parseString(message).getAsJsonObject();
      int code = root.has("code") && !root.get("code").isJsonNull() ? root.get("code").getAsInt() : -1;
      if (code != 0) {
        String msg = root.has("message") && !root.get("message").isJsonNull()
            ? root.get("message").getAsString()
            : "Không rõ";
        throw new IllegalStateException(
            String.format(Locale.ROOT, "iFLYTEK TTS trả về lỗi (code=%d): %s", code, msg));
      }

      JsonObject data = root.has("data") && root.get("data").isJsonObject() ? root.getAsJsonObject("data") : null;
      if (data == null) {
        return;
      }

      if (data.has("audio") && !data.get("audio").isJsonNull()) {
        String audioBase64 = data.get("audio").getAsString();
        if (!audioBase64.isEmpty()) {
          byte[] chunk = Base64.getDecoder().decode(audioBase64);
          audioBuffer.write(chunk, 0, chunk.length);
        }
      }

      int status = data.has("status") && !data.get("status").isJsonNull() ? data.get("status").getAsInt() : -1;
      if (status == 2 && !completion.isDone()) {
        completion.complete(null);
      }
    }

    void awaitCompletion(Duration timeout) throws InterruptedException {
      try {
        if (timeout == null || timeout.isNegative()) {
          completion.get();
        } else {
          completion.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        }
      } catch (java.util.concurrent.TimeoutException ex) {
        throw new IllegalStateException("Kết nối iFLYTEK TTS bị quá hạn trả về dữ liệu.", ex);
      } catch (java.util.concurrent.ExecutionException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof RuntimeException) {
          throw (RuntimeException) cause;
        }
        if (cause instanceof Error) {
          throw (Error) cause;
        }
        throw new IllegalStateException("iFLYTEK TTS gặp lỗi khi xử lý dữ liệu.", cause);
      }
    }

    byte[] getAudioBytes() {
      return audioBuffer.toByteArray();
    }

    boolean isClosed() {
      return closed.get();
    }
  }

  private Path resolveAudioOutputDirectory() throws IOException {
    Path projectRoot = Paths.get(System.getProperty("user.dir"));
    Path directory = projectRoot.resolve(LOCAL_AUDIO_OUTPUT_DIR_NAME);
    Files.createDirectories(directory);
    return directory;
  }

  private Path ensureUniqueFile(Path candidate) throws IOException {
    if (!Files.exists(candidate)) {
      return candidate;
    }
    String fileName = candidate.getFileName().toString();
    int dotIndex = fileName.lastIndexOf('.');
    String baseName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    String extension = dotIndex > 0 ? fileName.substring(dotIndex) : "";
    for (int counter = 1; counter < 10_000; counter++) {
      Path nextCandidate = candidate.getParent().resolve(baseName + "-" + counter + extension);
      if (!Files.exists(nextCandidate)) {
        return nextCandidate;
      }
    }
    throw new IOException("Không thể tạo tên file duy nhất cho chương: " + fileName);
  }

  private void forwardWithError(HttpServletRequest request, HttpServletResponse response, String message)
      throws ServletException, IOException {
    request.setAttribute("errorMessage", message);
    request.getRequestDispatcher("/error.jsp").forward(request, response);
  }

  private String safeTrim(String value) {
    return value == null ? null : value.trim();
  }

  private String sanitizeForFilename(String input) {
    String sanitized = input.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\-_]+", "-");
    sanitized = sanitized.replaceAll("-+", "-");
    sanitized = sanitized.replaceAll("^-|-$", "");
    if (sanitized.isEmpty()) {
      sanitized = "chapter" + System.nanoTime();
    }
    return sanitized;
  }

  private void ensureGeminiConfigured() {
    String key = resolveGeminiApiKey();
    if (key == null || key.isBlank()) {
      throw new IllegalStateException(
          "Chưa cấu hình Gemini API key. Hãy đặt biến môi trường GOOGLE_API_KEY (hoặc GEMINI_API_KEY) hoặc cung cấp một khóa hợp lệ trong cấu hình ứng dụng.");
    }
  }

  private void ensureIflytekConfigured() {
    if (shouldUseLocalPreviewMode()) {
      return;
    }
    String appId = resolveIflytekAppId();
    if (appId == null || appId.isBlank()) {
      throw new IllegalStateException(
          "Chưa cấu hình iFLYTEK app_id. Hãy đặt biến môi trường IFLYTEK_APP_ID hoặc cung cấp trong cấu hình ứng dụng.");
    }
    String apiKey = resolveIflytekApiKey();
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException(
          "Chưa cấu hình iFLYTEK API key. Hãy đặt biến môi trường IFLYTEK_API_KEY hoặc cung cấp thông số hợp lệ trong cấu hình ứng dụng.");
    }
    String apiSecret = resolveIflytekApiSecret();
    if (apiSecret == null || apiSecret.isBlank()) {
      throw new IllegalStateException(
          "Chưa cấu hình iFLYTEK API secret. Hãy đặt biến môi trường IFLYTEK_API_SECRET hoặc cung cấp trong cấu hình ứng dụng.");
    }
    String endpoint = resolveIflytekEndpoint();
    if (endpoint == null || endpoint.isBlank()) {
      throw new IllegalStateException(
          "Chưa cấu hình endpoint iFLYTEK TTS hợp lệ. Hãy đặt biến môi trường IFLYTEK_TTS_ENDPOINT hoặc cập nhật cấu hình.");
    }
    String voice = resolveIflytekVoice();
    if (voice == null || voice.isBlank()) {
      throw new IllegalStateException(
          "Chưa cấu hình giọng đọc iFLYTEK (vcn). Hãy đặt biến môi trường IFLYTEK_TTS_VOICE hoặc cấp quyền sử dụng giọng tại console.");
    }
  }

  private boolean shouldUseLocalPreviewMode() {
    if (localPreviewOverride) {
      return true;
    }
    String envFlag = System.getenv("TTS_PREVIEW_ONLY");
    if (envFlag != null && envFlag.equalsIgnoreCase("true")) {
      localPreviewOverride = true;
      return true;
    }
    String systemFlag = System.getProperty("tts.preview.only");
    if (systemFlag != null && systemFlag.equalsIgnoreCase("true")) {
      localPreviewOverride = true;
      return true;
    }
    return false;
  }

  private Path generateLocalPreviewAudio(String chapterTitle, String summaryText, Path audioDirectory)
      throws IOException {
    String effectiveTitle = (chapterTitle == null || chapterTitle.isBlank()) ? "chapter" + System.nanoTime()
        : chapterTitle;
    String sanitizedTitle = sanitizeForFilename(effectiveTitle);
    if (sanitizedTitle.isBlank()) {
      sanitizedTitle = "chapter" + System.nanoTime();
    }

    String effectiveSummary = (summaryText == null || summaryText.isBlank())
        ? "Không có nội dung để đọc."
        : summaryText.trim();
    if (effectiveSummary.length() > 600) {
      effectiveSummary = effectiveSummary.substring(0, 600);
    }

    byte[] audioData = synthesizePreviewWaveform(effectiveSummary);
    if (audioData.length == 0) {
      audioData = synthesizePreviewWaveform("Âm thanh mô phỏng.");
    }

    Path audioFile = ensureUniqueFile(audioDirectory.resolve(sanitizedTitle + ".wav"));
    AudioFormat format = new AudioFormat(16_000f, 16, 1, true, false);
    try (ByteArrayInputStream inputStream = new ByteArrayInputStream(audioData);
        AudioInputStream audioStream = new AudioInputStream(inputStream, format, audioData.length / 2)) {
      AudioSystem.write(audioStream, AudioFileFormat.Type.WAVE, audioFile.toFile());
    }
    return audioFile;
  }

  private byte[] synthesizePreviewWaveform(String text) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    float sampleRate = 16_000f;
    int samplesPerChar = Math.max(1, (int) (sampleRate * 0.12f));
    double amplitude = 0.55d;

    char[] characters = text.toCharArray();
    for (char c : characters) {
      double frequency = frequencyForCharacter(c);
      for (int i = 0; i < samplesPerChar; i++) {
        double sampleValue;
        if (frequency <= 0) {
          sampleValue = 0d;
        } else {
          double envelope = Math.min(1d, 1.5d * Math.min(i, samplesPerChar - i) / samplesPerChar);
          sampleValue = amplitude * envelope * Math.sin(2d * Math.PI * frequency * i / sampleRate);
        }
        short sample = (short) Math.max(Math.min(sampleValue * Short.MAX_VALUE, Short.MAX_VALUE), Short.MIN_VALUE);
        buffer.write(sample & 0xFF);
        buffer.write((sample >> 8) & 0xFF);
      }

      if (Character.isWhitespace(c)) {
        int pauseSamples = Math.max(1, samplesPerChar / 2);
        for (int i = 0; i < pauseSamples; i++) {
          buffer.write(0);
          buffer.write(0);
        }
      }
    }
    return buffer.toByteArray();
  }

  private double frequencyForCharacter(char c) {
    if (Character.isWhitespace(c)) {
      return 0d;
    }
    char lower = Character.toLowerCase(c);
    if (lower >= 'a' && lower <= 'z') {
      return 180d + (lower - 'a') * 8d;
    }
    if (lower >= '0' && lower <= '9') {
      return 220d + (lower - '0') * 12d;
    }
    switch (lower) {
      case '.':
      case ',':
        return 260d;
      case '?':
      case '!':
        return 320d;
      case ':':
      case ';':
        return 280d;
      default:
        return 240d;
    }
  }

  private boolean isModelNotFound(RuntimeException ex) {
    String message = ex.getMessage();
    if (message == null) {
      return false;
    }
    String normalized = message.toLowerCase(Locale.ROOT);
    return normalized.contains("404") || normalized.contains("not found")
        || normalized.contains("unsupported") || normalized.contains("does not exist");
  }

  private static final class OllamaRateLimitException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private OllamaRateLimitException(String endpoint, String responseBody) {
      super(buildMessage(endpoint, responseBody));
    }

    private static String buildMessage(String endpoint, String responseBody) {
      String trimmedBody = responseBody == null ? "" : responseBody.trim();
      if (trimmedBody.length() > 240) {
        trimmedBody = trimmedBody.substring(0, 240) + "...";
      }
      String location = endpoint == null ? "Ollama" : endpoint;
      if (trimmedBody.isEmpty()) {
        return "Ollama API trả về HTTP 429 từ " + location;
      }
      return "Ollama API trả về HTTP 429 từ " + location + ": " + trimmedBody;
    }
  }

  private static final class QuestionBankItem {
    private int id;
    private String question;
    private List<String> options;
    private String answer;
    private String difficulty;
    private double param_a;
    private double param_b;
    private double param_c;
  }

  private enum DifficultyRange {
    EASY(0.6d, 1.0d, -2.0d, -0.5d, 0.22d, 0.30d),
    MEDIUM(1.0d, 1.5d, -0.5d, 0.8d, 0.10d, 0.18d),
    HARD(1.5d, 2.5d, 0.8d, 2.5d, 0.05d, 0.12d);

    private final double aMin;
    private final double aMax;
    private final double bMin;
    private final double bMax;
    private final double cMin;
    private final double cMax;

    DifficultyRange(double aMin, double aMax, double bMin, double bMax, double cMin, double cMax) {
      this.aMin = aMin;
      this.aMax = aMax;
      this.bMin = bMin;
      this.bMax = bMax;
      this.cMin = cMin;
      this.cMax = cMax;
    }

    private static DifficultyRange forDifficulty(String difficulty) {
      if (difficulty == null) {
        return EASY;
      }
      switch (difficulty.toLowerCase(Locale.ROOT)) {
        case "easy":
          return EASY;
        case "hard":
          return HARD;
        case "medium":
        default:
          return MEDIUM;
      }
    }
  }

  private static final class DocumentContents {
    private final String fullText;
    private final EbookMetadata metadata;
    private final DocumentLanguage language;

    private DocumentContents(String fullText, EbookMetadata metadata, DocumentLanguage language) {
      this.fullText = fullText;
      this.metadata = metadata;
      this.language = language;
    }

    private String getFullText() {
      return fullText;
    }

    private EbookMetadata getMetadata() {
      return metadata;
    }

    private DocumentLanguage getLanguage() {
      return language;
    }
  }

  private enum DocumentLanguage {
    VIETNAMESE,
    ENGLISH
  }

  @SuppressWarnings("unused")
  public static final class EbookMetadata {
    private final String title;
    private final String author;
    private final String publicationYear;
    private final String subject;
    private final String keywords;
    private final String producer;

    private EbookMetadata(String title, String author, String publicationYear, String subject, String keywords,
        String producer) {
      this.title = title;
      this.author = author;
      this.publicationYear = publicationYear;
      this.subject = subject;
      this.keywords = keywords;
      this.producer = producer;
    }

    public String getTitle() {
      return title;
    }

    public String getAuthor() {
      return author;
    }

    public String getPublicationYear() {
      return publicationYear;
    }

    public String getSubject() {
      return subject;
    }

    public String getKeywords() {
      return keywords;
    }

    public String getProducer() {
      return producer;
    }
  }

  private static final class ChapterContent {
    private final String title;
    private final String body;
    private final int startOffset;
    private final Integer sequence;

    private ChapterContent(String title, String body, int startOffset, Integer sequence) {
      this.title = title;
      this.body = body;
      this.startOffset = startOffset;
      this.sequence = sequence;
    }

    public String getTitle() {
      return title;
    }

    public String getBody() {
      return body;
    }

    public int getStartOffset() {
      return startOffset;
    }

    public Integer getSequence() {
      return sequence;
    }
  }

  public static final class ChapterResult {
    private final String title;
    private final String summary;
    private final String audioFileName;

    private ChapterResult(String title, String summary, String audioFileName) {
      this.title = title;
      this.summary = summary;
      this.audioFileName = audioFileName;
    }

    public String getTitle() {
      return title;
    }

    public String getSummary() {
      return summary;
    }

    public String getAudioFileName() {
      return audioFileName;
    }
  }

  private static final class DocumentContext {
    private final String uploadedFileName;
    private final EbookMetadata metadata;
    private final List<ChapterSummary> chapters;
    private final DocumentLanguage language;
    private final boolean isStructured;

    private DocumentContext(String uploadedFileName, EbookMetadata metadata, List<ChapterSummary> chapters,
        DocumentLanguage language, boolean isStructured) {
      this.uploadedFileName = uploadedFileName;
      this.metadata = metadata;
      this.chapters = chapters;
      this.language = language;
      this.isStructured = isStructured;
    }

    public String getUploadedFileName() {
      return uploadedFileName;
    }

    public EbookMetadata getMetadata() {
      return metadata;
    }

    public List<ChapterSummary> getChapters() {
      return chapters;
    }

    public DocumentLanguage getLanguage() {
      return language;
    }

    public boolean isStructured() {
      return isStructured;
    }

    public boolean hasAnyAudio() {
      return chapters.stream().anyMatch(ch -> ch.getAudioFileName() != null);
    }

    public List<ChapterResult> toChapterResults() {
      List<ChapterResult> results = new ArrayList<>();
      for (ChapterSummary chapter : chapters) {
        results.add(new ChapterResult(chapter.getTitle(), chapter.getSummary(), chapter.getAudioFileName()));
      }
      return results;
    }
  }

  public static final class ChapterSummary {
    private final String title;
    private final String summary;
    private final String body;
    private String audioFileName;

    public ChapterSummary(String title, String summary, String body) {
      this.title = title;
      this.summary = summary;
      this.body = body;
      this.audioFileName = null;
    }

    public String getTitle() {
      return title;
    }

    public String getSummary() {
      return summary;
    }

    public String getBody() {
      return body;
    }

    public String getAudioFileName() {
      return audioFileName;
    }

    public void setAudioFileName(String audioFileName) {
      this.audioFileName = audioFileName;
    }
  }
}
