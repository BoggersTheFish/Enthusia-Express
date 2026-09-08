package io.enthusia.express.mail;

public record MailSummary(int packages, int letters, int announcements) {
  public int total() {
    return Math.addExact(packages, Math.addExact(letters, announcements));
  }
}
