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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
  private static final String GEMINI_API_KEY = "Your-Gemini-API-Key"; // Set via secure configbefore
                                                                      // deployment
  private static final String IFLYTEK_TTS_ENDPOINT = "wss://tts-api-sg.xf-yun.com/v2/tts";
  // TODO: Replace with secure configuration before deployment.
  private static final String IFLYTEK_APP_ID = "Your-IFLYTEK-APP-ID";
  private static final String IFLYTEK_API_KEY = "Your-IFLYTEK-API-KEY";
  private static final String IFLYTEK_API_SECRET = "Your-IFLYTEK-API-SECRET";
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
  private static final String OLLAMA_DEFAULT_LOCAL_MODEL = "gpt-oss:20b";
  private static final String OLLAMA_DEFAULT_CLOUD_MODEL = "gpt-oss:120b-cloud";

  private static final Pattern CHAPTER_HEADING_PATTERN = Pattern.compile(
      "(?im)^(chapter\\s+(\\d+|[ivxlcdm]+)\\b.*|chương\\s+\\d+\\b.*|chapitre\\s+\\d+\\b.*|ch\\.?\\s*\\d+\\b.*|\\d+\\.\\s+chapter\\b.*)$");
  private static final Pattern[] AUTHOR_HINT_PATTERNS = {
      Pattern.compile(
          "(?im)^\\s*(?:tác giả|tac gia|author|biên soạn|bien soan|chủ biên|chu bien)\\s*[:：]\\s*([^\\n\\r]+)"),
      Pattern.compile("(?im)^\\s*(?:nhóm biên soạn|group author|soạn giả)\\s*[:：]\\s*([^\\n\\r]+)"),
      Pattern.compile("(?im)\\b(?:by|edited by|compiled by)\\s+([\\p{L}0-9 .,'-]{3,})") };

  private transient volatile Client geminiClient;
  private transient volatile boolean localPreviewOverride;

  // Reuse a single HTTP client for outbound API calls.
  private final HttpClient httpClient = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(30))
      .build();

  private static final Gson GSON = new Gson();

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.sendRedirect(request.getContextPath() + "/index.jsp");
  }

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
    Part ebookPart = null;
    try {
      ebookPart = request.getPart("ebook");
    } catch (IllegalStateException ex) {
      // file too large or multipart error
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

      Path audioDirectory = resolveAudioOutputDirectory();
      getServletContext().setAttribute("audioOutputDirectory", audioDirectory);
      List<ChapterResult> chapterResults = new ArrayList<>();
      List<AudioDownloadItem> downloadItems = new ArrayList<>();

      for (ChapterContent chapter : chapters) {
        String summary = summarizeTextWithOllama(chapter.getTitle(), chapter.getBody(), document.getLanguage());
        Path audioFile = generateAudioWithIflytek(chapter.getTitle(), summary, audioDirectory);
        String storedFileName = audioFile.getFileName().toString();
        chapterResults.add(new ChapterResult(chapter.getTitle(), summary, storedFileName));
        downloadItems.add(new AudioDownloadItem(storedFileName, chapter.getTitle()));
      }

      EbookMetadata metadata = document.getMetadata();
      request.setAttribute("metadata", metadata);
      request.setAttribute("chapters", chapterResults);
      request.setAttribute("uploadedFileName", uploadedFileName);
      HttpSession session = request.getSession(true);
      session.setAttribute("audioDownloadItems", downloadItems);
      session.setAttribute("audioDownloadBookTitle", resolveDownloadBookTitle(metadata, uploadedFileName));

      request.getRequestDispatcher("/results.jsp").forward(request, response);
    } catch (IllegalStateException ex) {
      forwardWithError(request, response, ex.getMessage());
    } catch (Exception ex) {
      ex.printStackTrace();
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

    while (matcher.find()) {
      chapterStartPositions.add(matcher.start());
      String heading = matcher.group().trim();
      chapterTitles.add(sanitizeHeading(heading));
    }

    if (chapterStartPositions.isEmpty()) {
      // No explicit chapter headings detected, treat entire text as one chapter.
      chapters.add(new ChapterContent("Toàn bộ sách", rawText.trim()));
      return chapters;
    }

    chapterStartPositions.add(rawText.length());

    for (int i = 0; i < chapterTitles.size(); i++) {
      int start = chapterStartPositions.get(i);
      int end = chapterStartPositions.get(i + 1);
      String body = rawText.substring(start, end).trim();
      chapters.add(new ChapterContent(chapterTitles.get(i), body));
    }

    return chapters;
  }

  private String sanitizeHeading(String heading) {
    String clean = heading.replaceAll("[\r\n]", " ").trim();
    clean = clean.replaceAll("\\s+", " ");
    if (clean.length() > 80) {
      clean = clean.substring(0, 80) + "...";
    }
    return clean;
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
    system = stripUnsupportedControlChars(system);
    userPrompt = stripUnsupportedControlChars(userPrompt);

    try {
      // Use resolver helpers so configuration can come from env or system properties
      String apiKey = resolveOllamaApiKey();
      String host = resolveOllamaHost();
      String model = resolveOllamaModel();

      JsonObject requestBody = new JsonObject();
      requestBody.addProperty("model", model);
      JsonArray messages = new JsonArray();

      JsonObject systemMessage = new JsonObject();
      systemMessage.addProperty("role", "system");
      systemMessage.addProperty("content", system);
      messages.add(systemMessage);

      JsonObject userMessage = new JsonObject();
      userMessage.addProperty("role", "user");
      userMessage.addProperty("content", userPrompt);
      messages.add(userMessage);

      requestBody.add("messages", messages);
      requestBody.addProperty("stream", false);

      String requestJson = GSON.toJson(requestBody);

      java.net.http.HttpRequest.Builder reqBuilder = java.net.http.HttpRequest.newBuilder()
          .uri(URI.create(host + "/api/chat"))
          .timeout(Duration.ofSeconds(60))
          .header("Content-Type", "application/json")
          .header("Accept", "application/json")
          .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8));

      if (apiKey != null && !apiKey.isBlank()) {
        reqBuilder.header("Authorization", "Bearer " + apiKey.trim());
      }

      java.net.http.HttpRequest req = reqBuilder.build();

      java.net.http.HttpResponse<String> resp = httpClient.send(req,
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
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
      } else if (resp.statusCode() == 401 || resp.statusCode() == 403) {
        throw new IllegalStateException("Không thể xác thực với Ollama host (HTTP " + resp.statusCode() + ")");
      } else {
        throw new IllegalStateException("Ollama API trả về HTTP " + resp.statusCode() + ": " + resp.body());
      }
    } catch (IOException | InterruptedException ex) {
      throw new IllegalStateException("Không thể gọi Ollama API để tóm tắt: " + ex.getMessage(), ex);
    }

    throw new IllegalStateException("Ollama API không trả về phần tóm tắt hợp lệ.");
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

    private ChapterContent(String title, String body) {
      this.title = title;
      this.body = body;
    }

    public String getTitle() {
      return title;
    }

    public String getBody() {
      return body;
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
}
