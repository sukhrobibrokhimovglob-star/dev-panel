package io.github.devpanel.files;

import io.github.devpanel.support.DevPanelException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

/**
 * Read-only access to the docs and logs folders. Every path given by a caller is resolved against
 * the relevant base folder and rejected unless it stays inside it, so a {@code ..} segment (or an
 * absolute path) can never reach a file elsewhere on disk.
 */
public class DevFileBrowserService {

  private final Path docsDir;
  private final Path logsDir;

  public DevFileBrowserService(String docsPath, String logsPath) {
    this.docsDir = Path.of(docsPath).toAbsolutePath().normalize();
    this.logsDir = Path.of(logsPath).toAbsolutePath().normalize();
  }

  public Path logsDir() {
    return logsDir;
  }

  public List<FileEntry> list(Folder folder) {
    Path baseDir = baseDir(folder);
    if (!Files.isDirectory(baseDir)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(baseDir)) {
      return walk.filter(Files::isRegularFile)
          .sorted(Comparator.naturalOrder())
          .map(file -> toEntry(baseDir, file))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Could not list " + baseDir, e);
    }
  }

  public Resource download(Folder folder, String relativePath) {
    Path resolved = resolveWithinBase(baseDir(folder), relativePath);
    if (!Files.isRegularFile(resolved)) {
      throw DevPanelException.notFound("File not found: " + relativePath);
    }
    return new FileSystemResource(resolved);
  }

  private Path baseDir(Folder folder) {
    return folder == Folder.DOCS ? docsDir : logsDir;
  }

  private static FileEntry toEntry(Path baseDir, Path file) {
    try {
      return new FileEntry(baseDir.relativize(file).toString().replace('\\', '/'),
          Files.size(file), Files.getLastModifiedTime(file).toInstant());
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + file, e);
    }
  }

  private static Path resolveWithinBase(Path baseDir, String relativePath) {
    if (relativePath == null || relativePath.isBlank()) {
      throw DevPanelException.badRequest("path is required");
    }
    Path resolved = baseDir.resolve(relativePath).normalize();
    if (!resolved.startsWith(baseDir)) {
      throw DevPanelException.badRequest("Invalid path");
    }
    return resolved;
  }

  public enum Folder {
    DOCS,
    LOGS
  }

  public record FileEntry(String path, long sizeBytes, Instant lastModified) {
  }
}
