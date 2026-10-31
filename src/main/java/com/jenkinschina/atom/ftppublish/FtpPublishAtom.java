package com.jenkinschina.atom.ftppublish;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Glob;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Upload files to an FTP/FTPS server, keeping the directory structure.
 *
 * <p>publish-over-ftp and ftppublisher both drive an FTP client from Java; here the transfer is curl, which is on
 * every build agent and already knows passive mode, TLS and directory creation. The password never appears in the
 * process list: it goes into a private netrc file that curl reads and that is deleted afterwards.
 */
public class FtpPublishAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String host = context.getParam("host", "").trim();
        String port = context.getParam("port", "21").trim();
        String username = context.getParam("username", "").trim();
        String password = context.getSecret("password");
        if (password == null || password.isBlank()) {
            password = context.getParam("password", "");
        }
        String remoteDir = context.getParam("remoteDir", "").trim();
        String sourceFiles = context.getParam("sourceFiles", "**/*");
        String exclude = context.getParam("exclude", "");
        String baseDir = context.getParam("baseDir", "").trim();
        boolean secure = context.getBooleanParam("secure", false);
        int timeoutSeconds = Math.max(5, Math.min(context.getIntParam("timeoutSeconds", 120), 3600));

        if (host.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001, "host is required");
        }
        if (username.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40002, "username is required");
        }
        if (password == null || password.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40003, "password is required (put it in a credential)");
        }
        if (!port.matches("\\d+")) {
            return AtomResult.error(ErrorType.USER, 40004, "port must be a number");
        }
        if (remoteDir.isEmpty() || remoteDir.equals("/")) {
            return AtomResult.error(ErrorType.USER, 40005, "remoteDir is required (the directory files go under)");
        }

        Path base = baseDir.isEmpty() ? workspace : workspace.resolve(baseDir).normalize();
        if (!base.startsWith(workspace) || !Files.isDirectory(base)) {
            return AtomResult.error(ErrorType.USER, 40006, "baseDir must be a directory inside the workspace");
        }

        List<Path> files = collect(base, sourceFiles, exclude);
        if (files.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40007, "no file matched '" + sourceFiles + "'");
        }

        Path netrc = null;
        try {
            netrc = Files.createTempFile("atom-ftp-", ".netrc");
            // curl reads the password from here, so it never shows up in ps or in the build log
            String machine = host.replace("'", "");
            Files.writeString(netrc,
                    "machine " + netrcToken(machine) + "\nlogin " + netrcToken(username)
                            + "\npassword " + netrcToken(password) + "\n",
                    StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(netrc,
                        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // a filesystem without POSIX modes: nothing better is available
            }

            String scheme = secure ? "ftps" : "ftp";
            String target = scheme + "://" + host + ":" + port + "/" + encodePath(trimSlashes(remoteDir)) + "/";
            long total = 0;
            int uploaded = 0;
            for (Path file : files) {
                String relative = base.relativize(file).toString().replace('\\', '/');
                List<String> command = new ArrayList<>();
                command.add("curl");
                // --netrc must point at our file; --netrc-optional off keeps a typo from falling back to prompts
                command.add("--netrc-file");
                command.add(netrc.toString());
                command.add("--silent");
                command.add("--show-error");
                command.add("--fail");
                command.add("--ftp-create-dirs");
                if (secure) {
                    command.add("--ssl-reqd");
                }
                command.add("--connect-timeout");
                command.add("15");
                command.add("--max-time");
                command.add(String.valueOf(timeoutSeconds));
                command.add("--upload-file");
                command.add(file.toString());
                command.add(target + encodePath(relative));

                Result result = run(command, timeoutSeconds);
                if (result.exit != 0) {
                    String detail = result.stderr.isBlank() ? result.stdout : result.stderr;
                    ErrorType type = detail.contains("Access denied") || detail.contains("530")
                            || detail.contains("Login denied") || detail.contains("550")
                                    ? ErrorType.USER : ErrorType.ENVIRONMENT;
                    return AtomResult.error(type, 40008,
                            "uploading " + relative + " failed: " + oneLine(detail));
                }
                uploaded++;
                total += Files.size(file);
                context.log("[ftp] " + relative + " -> " + remoteDir);
            }

            return AtomResult.success()
                    .data("filesUploaded", String.valueOf(uploaded))
                    .data("bytes", String.valueOf(total))
                    .data("remoteDir", remoteDir)
                    .data("host", host);
        } finally {
            if (netrc != null) {
                Files.deleteIfExists(netrc);
            }
        }
    }



    /**
     * netrc splits on whitespace, so a password with a space would be cut in half and the login would fail with a
     * 530 that says nothing about the real cause. Quoted tokens fix that; newlines cannot be expressed at all.
     */
    static String netrcToken(String value) {
        String cleaned = value.replace("\r", "").replace("\n", "");
        if (cleaned.isEmpty()) {
            return "\"\"";
        }
        boolean needsQuotes = cleaned.chars().anyMatch(c -> c == ' ' || c == '\t' || c == '"' || c == '\\');
        if (!needsQuotes) {
            return cleaned;
        }
        return "\"" + cleaned.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * Percent-encodes a path for the FTP URL: a space or a CJK file name is legal on the server but breaks the
     * URL curl parses. Slashes stay, everything outside the unreserved set is escaped.
     */
    static String encodePath(String path) {
        StringBuilder encoded = new StringBuilder();
        for (byte value : path.getBytes(StandardCharsets.UTF_8)) {
            int b = value & 0xff;
            char c = (char) b;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~' || c == '/') {
                encoded.append(c);
            } else {
                encoded.append('%').append(String.format("%02X", b));
            }
        }
        return encoded.toString();
    }

    private static String trimSlashes(String value) {
        return value.replaceAll("^/+", "").replaceAll("/+$", "");
    }


    /**
     * The runner keeps {@code input.json}, {@code .secrets.json} and {@code output.json} under
     * {@code .atoms/<runId>/}. An upload pattern of {@code **}{@code /*} would otherwise ship a credential file to a
     * remote host, so the runner's scratch directory is never part of the payload.
     */
    static boolean isRunnerScratch(String relative) {
        return relative.startsWith(".atoms/") || relative.equals(".atoms")
                || relative.endsWith("/.secrets.json") || relative.equals(".secrets.json");
    }

    private static List<Path> collect(Path base, String sourceFiles, String exclude) throws IOException {
        Set<Path> matches = new LinkedHashSet<>();
        for (String pattern : sourceFiles.split("[,\\n]")) {
            String trimmed = pattern.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            for (Path found : Glob.find(base, trimmed)) {
                if (Files.isRegularFile(found)) {
                    matches.add(found.normalize());
                }
            }
            Path literal = base.resolve(trimmed).normalize();
            if (literal.startsWith(base) && Files.isRegularFile(literal)) {
                matches.add(literal);
            }
        }
        List<String> excluded = new ArrayList<>();
        for (String part : exclude.split("[,\\n]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                excluded.add(trimmed);
            }
        }
        List<Path> kept = new ArrayList<>();
        for (Path candidate : matches) {
            String relative = base.relativize(candidate).toString().replace('\\', '/');
            if (isRunnerScratch(relative)) {
                continue;
            }
            boolean skip = false;
            for (String pattern : excluded) {
                String regex = pattern.replace(".", "\\.").replace("**", "\u0000")
                        .replace("*", "[^/]*").replace("?", ".").replace("\u0000", ".*");
                if (relative.matches(regex) || relative.matches(".*/" + regex)) {
                    skip = true;
                    break;
                }
            }
            if (!skip) {
                kept.add(candidate);
            }
        }
        return kept;
    }

    private record Result(int exit, String stdout, String stderr) {}

    private static Result run(List<String> command, int timeoutSeconds) throws InterruptedException, IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        Process process = builder.start();
        byte[] out = process.getInputStream().readAllBytes();
        byte[] err = process.getErrorStream().readAllBytes();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return new Result(124, "", "timed out after " + timeoutSeconds + "s");
        }
        return new Result(process.exitValue(), new String(out, StandardCharsets.UTF_8),
                new String(err, StandardCharsets.UTF_8));
    }

    private static String oneLine(String value) {
        String flat = value.replaceAll("\\s+", " ").trim();
        return flat.length() > 300 ? flat.substring(0, 300) + "…" : flat;
    }
}
