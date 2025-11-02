<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"
language="java" %> <% String errorMessage = (String)
request.getAttribute("errorMessage"); if (errorMessage == null ||
errorMessage.isBlank()) { errorMessage = "Không xác định được lỗi."; } String
safeErrorMessage = errorMessage .replace("&", "&amp;") .replace("<", "&lt;")
.replace(">", "&gt;") .replace("\"", "&quot;") .replace("'", "&#39;"); %>
<!DOCTYPE html>
<html lang="vi">
  <head>
    <meta charset="UTF-8" />
    <title>Lỗi xử lý</title>
    <style>
      body {
        font-family: Arial, sans-serif;
        background-color: #fff5f5;
        margin: 0;
        padding: 0;
      }
      .container {
        max-width: 640px;
        margin: 80px auto;
        background: #ffffff;
        padding: 40px 48px;
        border-radius: 12px;
        box-shadow: 0 20px 45px rgba(229, 62, 62, 0.15);
        border: 1px solid #fed7d7;
      }
      h1 {
        color: #c53030;
        margin-top: 0;
      }
      p {
        color: #4a5568;
        line-height: 1.6;
      }
      .actions {
        margin-top: 32px;
      }
      .actions a {
        display: inline-block;
        padding: 12px 20px;
        background: #2b6cb0;
        color: #fff;
        border-radius: 8px;
        text-decoration: none;
      }
      .actions a:hover {
        background: #2c5282;
      }
    </style>
  </head>
  <body>
    <div class="container">
      <h1>Đã xảy ra lỗi</h1>
      <p><%= safeErrorMessage %></p>
      <div class="actions">
        <a href="${pageContext.request.contextPath}/index.jsp"
          >Quay lại trang tải lên</a
        >
      </div>
    </div>
  </body>
</html>
