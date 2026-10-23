package io.github.devpanel.insight.health;

/**
 * One line of the health page: an area, its colour and a sentence saying what is wrong.
 * {@code key} identifies the condition for alerts (it fires once, and once more when resolved);
 * {@code tab} is where the details are.
 */
public record Check(String key, String area, Status status, String title, String detail,
                    String tab) {

  public enum Status {
    OK,
    WARNING,
    CRITICAL,
    UNKNOWN;

    public boolean worseThan(Status other) {
      return rank() > other.rank();
    }

    private int rank() {
      return switch (this) {
        case OK -> 1;
        case UNKNOWN -> 0;
        case WARNING -> 2;
        case CRITICAL -> 3;
      };
    }
  }

  public static Check ok(String key, String area, String title, String tab) {
    return new Check(key, area, Status.OK, title, null, tab);
  }
}
