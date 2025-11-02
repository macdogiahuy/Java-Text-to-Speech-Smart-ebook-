package com.yourcompany.ebook;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet(name = "AudioStreamServlet", urlPatterns = "/audio")
public class AudioStreamServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;
  private static final String AUDIO_DIRECTORY_ATTR = "audioOutputDirectory";
  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
    String fileNameParam = req.getParameter("file");
    if (fileNameParam == null || fileNameParam.isBlank()) {
      resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Thiếu tên tệp âm thanh.");
      return;
    }

    String decodedFileName = URLDecoder.decode(fileNameParam, StandardCharsets.UTF_8);
    Path audioDirectory = (Path) getServletContext().getAttribute(AUDIO_DIRECTORY_ATTR);
    if (audioDirectory == null) {
      // Attempt to fall back to the exploded webapp directory if available.
      String realPath = getServletContext().getRealPath("/generated-audio");
      if (realPath != null) {
        audioDirectory = Paths.get(realPath);
      } else {
        resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Không tìm thấy thư mục lưu trữ âm thanh.");
        return;
      }
    }

    Path requestedFile = audioDirectory.resolve(decodedFileName).normalize();
    if (!requestedFile.startsWith(audioDirectory) || !Files.exists(requestedFile)
        || !Files.isRegularFile(requestedFile)) {
      resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Tệp âm thanh không tồn tại.");
      return;
    }

    String fileName = requestedFile.getFileName().toString();
    String lowerName = fileName.toLowerCase(Locale.ROOT);
    String contentType = lowerName.endsWith(".wav") ? "audio/wav" : "audio/mpeg";

    resp.setContentType(contentType);
    resp.setHeader("Content-Disposition", "inline; filename=\"" + fileName + "\"");
    resp.setContentLengthLong(Files.size(requestedFile));

    try (OutputStream outputStream = resp.getOutputStream()) {
      Files.copy(requestedFile, outputStream);
    }
  }
}
