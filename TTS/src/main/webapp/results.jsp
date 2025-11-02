<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" language="java" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.net.URLEncoder" %>
<%@ page import="com.yourcompany.ebook.EbookProcessorServlet.EbookMetadata" %>
<%@ page import="com.yourcompany.ebook.EbookProcessorServlet.ChapterResult" %>
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
    <title>Kết quả chuyển đổi</title>
    <style>
        body { font-family: "Segoe UI", Tahoma, sans-serif; background-color: #f1f5f9; margin: 0; padding: 40px 0; }
        .container { max-width: 960px; margin: 0 auto; background: #ffffff; padding: 40px 50px; border-radius: 16px; box-shadow: 0 16px 50px rgba(15, 23, 42, 0.1); }
        h1 { color: #1a202c; margin-top: 0; }
        .meta-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 18px; margin-bottom: 40px; }
        .meta-card { background: #f8fafc; padding: 18px; border-radius: 12px; border: 1px solid #e2e8f0; }
        .meta-card span.label { display: block; font-size: 13px; letter-spacing: 0.04em; text-transform: uppercase; color: #64748b; margin-bottom: 6px; }
        .meta-card strong { color: #1f2937; }
        .chapter { border: 1px solid #e2e8f0; border-radius: 14px; padding: 24px 28px; margin-bottom: 24px; background: #ffffff; box-shadow: 0 12px 30px rgba(15, 23, 42, 0.06); }
        .chapter h2 { margin-top: 0; color: #2563eb; }
        .summary { white-space: pre-wrap; line-height: 1.6; color: #2d3748; }
        .audio-player { margin-top: 18px; }
        .actions { margin-top: 36px; display: flex; gap: 16px; }
        .actions a { display: inline-flex; align-items: center; gap: 8px; padding: 12px 20px; background: #1d4ed8; color: #fff; border-radius: 8px; text-decoration: none; transition: background 0.2s ease-in-out; }
        .actions a:hover { background: #1e3a8a; }
        .actions a.secondary { background: transparent; color: #1f2937; border: 1px solid #cbd5f5; }
    </style>
</head>
<body>
<%
    EbookMetadata metadata = (EbookMetadata) request.getAttribute("metadata");
    List<ChapterResult> chapters = (List<ChapterResult>) request.getAttribute("chapters");
    String uploadedFileName = (String) request.getAttribute("uploadedFileName");
    if (metadata == null || chapters == null) {
        response.sendRedirect(request.getContextPath() + "/index.jsp");
        return;
    }
%>
<div class="container">
    <h1>Kết quả chuyển đổi cho: <%= escapeHtml(metadata.getTitle()) %></h1>
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
        <div class="meta-card">
            <span class="label">Từ khóa</span>
            <strong><%= metadata.getKeywords() == null ? "Không rõ" : escapeHtml(metadata.getKeywords()) %></strong>
        </div>
        <div class="meta-card">
            <span class="label">Nhà sản xuất</span>
            <strong><%= metadata.getProducer() == null ? "Không rõ" : escapeHtml(metadata.getProducer()) %></strong>
        </div>
    </div>

    <h2>Danh sách chương</h2>
    <%
        for (ChapterResult chapter : chapters) {
            String audioUrl = response.encodeURL(request.getContextPath() + "/audio?file=" + URLEncoder.encode(chapter.getAudioFileName(), java.nio.charset.StandardCharsets.UTF_8));
            String contentType = chapter.getAudioFileName().toLowerCase(Locale.ROOT).endsWith(".wav") ? "audio/wav" : "audio/mpeg";
    %>
    <div class="chapter">
    <h2><%= escapeHtml(chapter.getTitle()) %></h2>
    <div class="summary"><%= escapeHtml(chapter.getSummary()).replace("\n", "<br/>") %></div>
        <div class="audio-player">
            <audio controls preload="none" style="width: 100%;">
                <source src="<%= audioUrl %>" type="<%= contentType %>">
                Trình duyệt của bạn không hỗ trợ phát âm thanh.
            </audio>
        </div>
    </div>
    <%
        }
    %>

    <div class="actions">
        <a href="${pageContext.request.contextPath}/index.jsp">Tải lên ebook khác</a>
    </div>
</div>
</body>
</html>
