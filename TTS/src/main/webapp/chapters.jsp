<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" language="java" %>
<%@ page import="java.util.List" %>
<%@ page import="com.yourcompany.ebook.EbookProcessorServlet.EbookMetadata" %>
<%@ page import="com.yourcompany.ebook.EbookProcessorServlet.ChapterSummary" %>
<%!
    private String escapeHtml(String input) {
        if (input == null) {
            return "";
        }
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
%>
<!DOCTYPE html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <title>Danh sách chương</title>
    <style>
        body { font-family: "Segoe UI", Tahoma, sans-serif; background-color: #f1f5f9; margin: 0; padding: 40px 0; }
        .container { max-width: 1000px; margin: 0 auto; background: #ffffff; padding: 40px 50px; border-radius: 16px; box-shadow: 0 16px 50px rgba(15, 23, 42, 0.1); }
        h1 { color: #1a202c; margin-top: 0; }
        .meta-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 18px; margin-bottom: 40px; }
        .meta-card { background: #f8fafc; padding: 18px; border-radius: 12px; border: 1px solid #e2e8f0; }
        .meta-card span.label { display: block; font-size: 13px; letter-spacing: 0.04em; text-transform: uppercase; color: #64748b; margin-bottom: 6px; }
        .meta-card strong { color: #1f2937; }
        .chapters-form { margin-top: 30px; }
        .chapter-item { border: 1px solid #e2e8f0; border-radius: 14px; padding: 24px 28px; margin-bottom: 20px; background: #ffffff; box-shadow: 0 4px 12px rgba(15, 23, 42, 0.06); }
        .chapter-header { display: flex; align-items: center; gap: 16px; margin-bottom: 16px; }
        .chapter-title { font-size: 18px; font-weight: 600; color: #2563eb; margin: 0 0 16px 0; }
        .chapter-summary { white-space: pre-wrap; line-height: 1.6; color: #2d3748; margin-top: 12px; }
        .chapter-actions { margin-top: 16px; }
        .generate-audio-btn { padding: 10px 20px; background: #1d4ed8; color: #fff; border: none; border-radius: 8px; font-size: 14px; cursor: pointer; transition: background 0.2s ease-in-out; }
        .generate-audio-btn:hover { background: #1e3a8a; }
        .audio-player { margin-top: 12px; }
        .actions { margin-top: 36px; display: flex; gap: 16px; justify-content: center; }
        .actions a.secondary { text-decoration: none; display: inline-flex; align-items: center; padding: 12px 20px; background: transparent; color: #1f2937; border: 1px solid #cbd5f5; border-radius: 8px; transition: background 0.2s ease-in-out; }
        .actions a.secondary:hover { background: #f8fafc; }
    </style>
</head>
<body>
<%
    EbookMetadata metadata = (EbookMetadata) request.getAttribute("metadata");
    List<ChapterSummary> chapters = (List<ChapterSummary>) request.getAttribute("chapters");
    String uploadedFileName = (String) request.getAttribute("uploadedFileName");
    Boolean isStructured = (Boolean) request.getAttribute("isStructuredDocument");
    
    if (metadata == null || chapters == null) {
        response.sendRedirect(request.getContextPath() + "/index.jsp");
        return;
    }
%>
<div class="container">
    <h1><%= isStructured != null && isStructured ? "Danh sách chương" : "Tóm tắt tài liệu" %></h1>
    <p><strong>Tệp nguồn:</strong> <%= escapeHtml(uploadedFileName) %></p>

    <div class="meta-grid">
        <div class="meta-card">
            <span class="label">Tác giả</span>
            <strong><%= escapeHtml(metadata.getAuthor()) %></strong>
        </div>
        <div class="meta-card">
            <span class="label">Năm xuất bản</span>
            <strong><%= escapeHtml(metadata.getPublicationYear()) %></strong>
        </div>
        <div class="meta-card">
            <span class="label">Chủ đề</span>
            <strong><%= metadata.getSubject() == null ? "Không rõ" : escapeHtml(metadata.getSubject()) %></strong>
        </div>
    </div>

    <%
        if (isStructured != null && isStructured) {
            // Có chương rõ ràng - hiển thị với nút tạo audio cho từng chương
    %>
    <div class="chapters-list">
        <%
            for (int i = 0; i < chapters.size(); i++) {
                ChapterSummary chapter = chapters.get(i);
                boolean hasAudio = chapter.getAudioFileName() != null && !chapter.getAudioFileName().isEmpty();
        %>
        <div class="chapter-item">
            <div class="chapter-header">
                <h2 class="chapter-title">
                    <%= escapeHtml(chapter.getTitle()) %>
                </h2>
            </div>
            <div class="chapter-summary">
                <%= escapeHtml(chapter.getSummary()).replace("\n", "<br/>") %>
            </div>
            <div class="chapter-actions">
                <%
                    if (hasAudio) {
                        String audioUrl = response.encodeURL(request.getContextPath() + "/audio?file=" + 
                            java.net.URLEncoder.encode(chapter.getAudioFileName(), java.nio.charset.StandardCharsets.UTF_8));
                        String contentType = chapter.getAudioFileName().toLowerCase(java.util.Locale.ROOT).endsWith(".wav") ? "audio/wav" : "audio/mpeg";
                %>
                <div class="audio-player">
                    <audio controls preload="none" style="width: 100%;">
                        <source src="<%= audioUrl %>" type="<%= contentType %>">
                        Trình duyệt của bạn không hỗ trợ phát âm thanh.
                    </audio>
                </div>
                <%
                    } else {
                %>
                <form method="post" action="${pageContext.request.contextPath}/process-ebook" style="display: inline;">
                    <input type="hidden" name="action" value="generate-audio">
                    <input type="hidden" name="chapterIndex" value="<%= i %>">
                    <button type="submit" class="generate-audio-btn">Tạo audio cho chương này</button>
                </form>
                <%
                    }
                %>
            </div>
        </div>
        <%
            }
        %>
    </div>
    <%
        } else {
            // Không có chương rõ ràng - chỉ hiển thị tóm tắt
            if (!chapters.isEmpty()) {
                ChapterSummary summary = chapters.get(0);
    %>
    <div class="summary-only">
        <div class="chapter-item">
            <h2 class="chapter-title"><%= escapeHtml(summary.getTitle()) %></h2>
            <div class="chapter-summary">
                <%= escapeHtml(summary.getSummary()).replace("\n", "<br/>") %>
            </div>
        </div>
    </div>
    <%
            }
        }
    %>

    <div class="actions">
        <a href="${pageContext.request.contextPath}/index.jsp" class="secondary" style="text-decoration: none; display: inline-flex; align-items: center; padding: 12px 20px; background: transparent; color: #1f2937; border: 1px solid #cbd5f5; border-radius: 8px; transition: background 0.2s ease-in-out;">
            Tải lên tài liệu khác
        </a>
    </div>
</div>

</body>
</html>

