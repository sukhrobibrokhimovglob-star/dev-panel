package io.github.devpanel.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognizes personal and secret data in free text: tokens, passwords, card numbers, e-mail
 * addresses, phone numbers and 14-digit personal identification numbers (PNFL), plus whatever the
 * application adds ({@code devpanel.insights.sensitive-patterns}). Used to mask what the panel
 * stores (request and error bodies) and to notice personal data written to the logs.
 */
public final class Sensitive {

  /** Kind name → pattern; the first group, when there is one, is the part kept visible. */
  private static final Map<String, Pattern> BUILT_IN = new LinkedHashMap<>();

  static {
    BUILT_IN.put("JWT", Pattern.compile("\\beyJ[\\w-]{8,}\\.[\\w-]{8,}\\.[\\w-]{8,}"));
    BUILT_IN.put("BEARER_TOKEN", Pattern.compile("(?i)(bearer\\s+)[\\w.~+/=-]{16,}"));
    BUILT_IN.put("PASSWORD", Pattern.compile(
        "(?i)(\"?(?:password|passwd|pwd|secret|api[_-]?key|access[_-]?token|refresh[_-]?token)"
            + "\"?\\s*[:=]\\s*\"?)[^\\s\",&}]{1,200}"));
    BUILT_IN.put("CARD", Pattern.compile("\\b(?:\\d[ -]?){15}\\d\\b"));
    BUILT_IN.put("PNFL", Pattern.compile("(?<![\\d.])[3-6]\\d{13}(?![\\d.])"));
    BUILT_IN.put("EMAIL", Pattern.compile("\\b[\\w.%+-]{1,64}@[\\w-]{1,63}(?:\\.[\\w-]{1,63})+\\b"));
    BUILT_IN.put("PHONE", Pattern.compile("(?<!\\d)\\+?998[\\s-]?\\d{2}[\\s-]?\\d{3}[\\s-]?\\d{2}"
        + "[\\s-]?\\d{2}(?!\\d)"));
  }

  private final Map<String, Pattern> patterns;

  /** @param extra kind → regular expression, added to the built-in ones */
  public Sensitive(Map<String, String> extra) {
    this.patterns = new LinkedHashMap<>(BUILT_IN);
    if (extra != null) {
      extra.forEach((kind, regex) -> {
        if (kind != null && regex != null && !regex.isBlank()) {
          patterns.put(kind.toUpperCase(), Pattern.compile(regex));
        }
      });
    }
  }

  public static Sensitive defaults() {
    return new Sensitive(Map.of());
  }

  /** The kinds of sensitive data in {@code text}, in the order of the patterns. */
  public Set<String> kinds(String text) {
    Set<String> found = new LinkedHashSet<>();
    if (text == null || text.isEmpty()) {
      return found;
    }
    for (Map.Entry<String, Pattern> entry : patterns.entrySet()) {
      Matcher matcher = entry.getValue().matcher(text);
      while (matcher.find()) {
        if (!"CARD".equals(entry.getKey()) || luhn(matcher.group())) {
          found.add(entry.getKey());
          break;
        }
      }
    }
    return found;
  }

  /** {@code text} with every sensitive value replaced by a short masked form. */
  public String mask(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String result = text;
    for (Map.Entry<String, Pattern> entry : patterns.entrySet()) {
      Matcher matcher = entry.getValue().matcher(result);
      StringBuilder out = new StringBuilder();
      boolean changed = false;
      while (matcher.find()) {
        String value = matcher.group();
        if ("CARD".equals(entry.getKey()) && !luhn(value)) {
          continue;
        }
        String kept = matcher.groupCount() >= 1 && matcher.group(1) != null
            ? matcher.group(1) : "";
        matcher.appendReplacement(out, Matcher.quoteReplacement(kept + masked(value
            .substring(kept.length()))));
        changed = true;
      }
      if (changed) {
        matcher.appendTail(out);
        result = out.toString();
      }
    }
    return result;
  }

  /** Masks only the values of JSON/form fields whose names say they are secret. */
  public String maskSecretsOnly(String text) {
    if (text == null) {
      return null;
    }
    Matcher matcher = BUILT_IN.get("PASSWORD").matcher(text);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(1) + "***"));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  public List<String> kindNames() {
    return new ArrayList<>(patterns.keySet());
  }

  /** Keeps the first two and last two characters of longer values: enough to recognize. */
  static String masked(String value) {
    String compact = value.strip();
    if (compact.length() <= 6) {
      return "***";
    }
    return compact.substring(0, 2) + "***" + compact.substring(compact.length() - 2);
  }

  private static boolean luhn(String number) {
    String digits = number.replaceAll("[^0-9]", "");
    if (digits.length() != 16 || digits.chars().distinct().count() == 1) {
      return false;
    }
    int sum = 0;
    for (int i = 0; i < digits.length(); i++) {
      int digit = digits.charAt(digits.length() - 1 - i) - '0';
      if (i % 2 == 1) {
        digit *= 2;
        if (digit > 9) {
          digit -= 9;
        }
      }
      sum += digit;
    }
    return sum % 10 == 0;
  }
}
