package com.yourcompany.ebook;

import java.io.Serializable;

/**
 * Describes an audio file generated for a chapter so it can later be packaged
 * for download.
 */
public final class AudioDownloadItem implements Serializable {
  private static final long serialVersionUID = 1L;

  private final String storedFileName;
  private final String chapterTitle;

  public AudioDownloadItem(String storedFileName, String chapterTitle) {
    this.storedFileName = storedFileName;
    this.chapterTitle = chapterTitle;
  }

  public String getStoredFileName() {
    return storedFileName;
  }

  public String getChapterTitle() {
    return chapterTitle;
  }
}
