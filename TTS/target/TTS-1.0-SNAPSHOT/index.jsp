<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"
language="java" %>
<!DOCTYPE html>
<html lang="vi">
  <head>
    <meta charset="UTF-8" />
    <title>Ebook to Audiobook Converter</title>
    <style>
      body {
        font-family: Arial, sans-serif;
        background-color: #f5f7fa;
        margin: 0;
        padding: 0;
      }
      .container {
        max-width: 720px;
        margin: 60px auto;
        background: #ffffff;
        padding: 45px;
        border-radius: 12px;
        box-shadow: 0 20px 45px rgba(15, 23, 42, 0.12);
      }
      h1 {
        text-align: center;
        color: #1d3557;
        margin-bottom: 10px;
      }
      p.subtitle {
        text-align: center;
        color: #4a5568;
        margin-top: 0;
      }
      form {
        margin-top: 40px;
        display: flex;
        flex-direction: column;
        gap: 25px;
      }
      .file-input {
        display: flex;
        flex-direction: column;
        gap: 8px;
      }
      .file-input label {
        font-weight: bold;
        color: #2d3748;
      }
      .file-input input[type='file'] {
        padding: 12px;
        border: 2px dashed #cbd5f5;
        border-radius: 10px;
        background: #f8fafc;
        cursor: pointer;
      }
      .actions {
        text-align: center;
      }
      .actions button {
        background-color: #457b9d;
        color: #fff;
        border: none;
        padding: 14px 28px;
        border-radius: 8px;
        font-size: 16px;
        cursor: pointer;
        transition: background-color 0.2s ease-in-out;
      }
      .actions button:hover {
        background-color: #1d3557;
      }
      .note {
        font-size: 14px;
        color: #718096;
        text-align: center;
      }
    </style>
  </head>
  <body>
    <div class="container">
      <h1>Ebook to Audiobook Converter</h1>
      <p class="subtitle">
        Tải lên ebook PDF hoặc DOCX của bạn để chuyển thành các bản tóm tắt âm
        thanh cho từng chương.
      </p>

      <form
        method="post"
        action="${pageContext.request.contextPath}/process-ebook"
        enctype="multipart/form-data"
      >
        <div class="file-input">
          <label for="ebook">Chọn tệp PDF hoặc DOCX</label>
          <input
            type="file"
            id="ebook"
            name="ebook"
            accept="application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,.pdf,.docx"
            required
          />
        </div>
        <div class="actions">
          <button type="submit">Bắt đầu xử lý</button>
        </div>
        <p class="note">
          Dung lượng tối đa: 100MB. Vui lòng đảm bảo ebook có cấu trúc rõ ràng
          theo chương.
        </p>
      </form>
    </div>
  </body>
</html>
