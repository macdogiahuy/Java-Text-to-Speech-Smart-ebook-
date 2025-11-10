package com.yourcompany.ebook;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet(name = "DownloadAllAudioServlet", urlPatterns = "/download-audio-zip")
public class DownloadAllAudioServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;
  private static final String AUDIO_DIRECTORY_ATTR = "audioOutputDirectory";

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
    Path audioDirectory = (Path) getServletContext().getAttribute(AUDIO_DIRECTORY_ATTR);
    if (audioDirectory == null) {
      String realPath = getServletContext().getRealPath("/generated-audio");
      if (realPath != null) {
        audioDirectory = Paths.get(realPath);
      } else {
        resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Không tìm thấy thư mục lưu trữ âm thanh.");
        return;
      }
    }

    if (!Files.exists(audioDirectory) || !Files.isDirectory(audioDirectory)) {
      resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Không có tệp âm thanh để tải.");
      return;
    }

    List<AudioDownloadItem> downloadItems = resolveAudioDownloadItems(req);
    if (downloadItems.isEmpty()) {
      resp.sendError(HttpServletResponse.SC_NOT_FOUND,
          "Không tìm thấy danh sách chương để tải. Vui lòng tạo tóm tắt trước khi tải.");
      return;
    }

    // Prepare response
    String bookTitle = resolveBookTitle(req);
    String zipFileName = buildZipFileName(bookTitle);
    resp.setContentType("application/zip");
    resp.setHeader("Content-Disposition",
        "attachment; filename=\"" + URLEncoder.encode(zipFileName, StandardCharsets.UTF_8) + "\"");

    try (OutputStream out = resp.getOutputStream(); ZipOutputStream zos = new ZipOutputStream(out)) {
      int sequence = 1;
      for (AudioDownloadItem item : downloadItems) {
        Path source = audioDirectory.resolve(item.getStoredFileName());
        if (!Files.exists(source) || !Files.isRegularFile(source)) {
          continue;
        }

        String entryName = buildEntryName(sequence, item.getChapterTitle(), item.getStoredFileName());
        ZipEntry entry = new ZipEntry(entryName);
        zos.putNextEntry(entry);
        Files.copy(source, zos);
        zos.closeEntry();
        sequence++;
      }
      if (sequence == 1) {
        resp.reset();
        resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Không tìm thấy tệp âm thanh hợp lệ để nén.");
        return;
      }
      zos.finish();
    }
  }

  private List<AudioDownloadItem> resolveAudioDownloadItems(HttpServletRequest req) {
    var session = req.getSession(false);
    if (session == null) {
      return java.util.Collections.emptyList();
    }
    Object raw = session.getAttribute("audioDownloadItems");
    if (!(raw instanceof List<?>)) {
      return java.util.Collections.emptyList();
    }
    List<?> items = (List<?>) raw;
    List<AudioDownloadItem> results = new ArrayList<>(items.size());
    for (Object obj : items) {
      if (obj instanceof AudioDownloadItem) {
        results.add((AudioDownloadItem) obj);
      }
    }
    return results;
  }

  private String buildEntryName(int sequence, String chapterTitle, String storedFileName) {
    String sanitizedTitle = sanitizeForZip(chapterTitle);
    String extension = extractExtension(storedFileName);
    return String.format(Locale.ROOT, "%02d-%s%s", sequence, sanitizedTitle, extension);
  }

  private String resolveBookTitle(HttpServletRequest req) {
    var session = req.getSession(false);
    if (session == null) {
      return "generated-audio";
    }
    Object raw = session.getAttribute("audioDownloadBookTitle");
    if (raw instanceof String && !((String) raw).isBlank()) {
      return (String) raw;
    }
    return "generated-audio";
  }

  private String buildZipFileName(String bookTitle) {
    String sanitized = sanitizeSlug(bookTitle, "generated-audio");
    return sanitized + "-audio.zip";
  }

  private String sanitizeForZip(String title) {
    return sanitizeSlug(title, "chapter");
  }

  private String sanitizeSlug(String text, String fallback) {
    if (text == null || text.isBlank()) {
      return fallback;
    }
    String normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    String cleaned = normalized.replaceAll("[^A-Za-z0-9]+", "-");
    cleaned = cleaned.replaceAll("-+", "-");
    cleaned = cleaned.replaceAll("^-|-$", "");
    if (cleaned.isEmpty()) {
      cleaned = fallback;
    }
    return cleaned.toLowerCase(Locale.ROOT);
  }

  private String extractExtension(String fileName) {
    if (fileName == null) {
      return ".mp3";
    }
    int idx = fileName.lastIndexOf('.');
    if (idx >= 0 && idx < fileName.length() - 1) {
      return fileName.substring(idx);
    }
    return ".mp3";
  }
}
