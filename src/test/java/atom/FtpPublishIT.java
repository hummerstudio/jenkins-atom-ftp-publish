package atom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ftp-publish atom, started the way the runner starts it.
 *
 * <p>What {@code tools/smoke.sh} checked: a real FTP conversation against {@code tools/fake_ftp.py} — passive mode,
 * remote directory creation, byte-for-byte content for nested, spaced and UTF-8 names, the pattern and exclude
 * narrowing, the runner's own scratch never being uploaded, and each failure (wrong password, a server that refuses
 * writes, bad input) surfacing the FTP reply.
 */
class FtpPublishIT {

    private static final String PASSWORD = "s3cret pw";
    private static final String GOOD_SECRETS = "{\"password\": \"" + PASSWORD + "\"}";

    private Atom atom;
    private Path module;
    private Path root;
    private Process server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
        module = Path.of(System.getProperty("atom.module", System.getProperty("user.dir")));
        root = Files.createTempDirectory("ftp-root");
    }

    @AfterEach
    void tearDown() throws IOException {
        stop();
        delete(root);
    }

    private static void delete(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    /** The fake FTP server from tools/, on a free port, waited for until it accepts a connection. */
    private void start(String... flags) throws Exception {
        stop();
        port = freePort();
        List<String> command = new ArrayList<>(List.of("python3", "tools/fake_ftp.py",
                String.valueOf(port), root.toString(), "ci", PASSWORD));
        command.addAll(List.of(flags));
        server = new ProcessBuilder(command).directory(module.toFile())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException notYet) {
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("the fake FTP server at " + port + " did not come up");
    }

    private void stop() {
        if (server != null && server.isAlive()) {
            server.destroy();
            server = null;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** The workspace with the shapes that usually break a hand-rolled FTP client. */
    private Atom workspace() {
        return atom.file("dist/app.jar", "jar-bytes\n")
                .file("dist/nested/deep.txt", "deep\n")
                .file("dist/a file with spaces.txt", "spaced\n")
                .file("dist/报告.txt", "zh\n")
                .file("private/secret.txt", "nope\n");
    }

    private Atom ftp() {
        return workspace()
                .input("host", "127.0.0.1")
                .input("port", String.valueOf(port))
                .input("username", "ci")
                .input("remoteDir", "site")
                .input("sourceFiles", "dist/**/*");
    }

    private String remote(String path) throws IOException {
        return new String(Files.readAllBytes(root.resolve(path)), StandardCharsets.UTF_8);
    }

    private boolean uploaded(String path) {
        return Files.exists(root.resolve(path));
    }

    @Test
    @DisplayName("the upload: nested, spaced and UTF-8 names arrive byte-for-byte, and nothing outside the pattern")
    void uploadsKeepingTheTree() throws Exception {
        start();
        AtomResult run = ftp().file(".secrets.json", GOOD_SECRETS).run();

        assertEquals("success", run.status(), run.console());
        assertEquals("4", run.field("filesUploaded"));

        assertArrayEquals("jar-bytes\n".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(root.resolve("site/dist/app.jar")));
        assertEquals("deep\n", remote("site/dist/nested/deep.txt"), "the nested path was flattened");
        assertEquals("spaced\n", remote("site/dist/a file with spaces.txt"), "a name with spaces broke the upload");
        assertEquals("zh\n", remote("site/dist/报告.txt"), "a UTF-8 name broke the upload");
        assertFalse(uploaded("site/private/secret.txt"), "a file outside the pattern must not be sent");
    }

    @Test
    @DisplayName("the runner's scratch, including .secrets.json, never reaches the server")
    void neverUploadsTheScratchDirectory() throws Exception {
        start();
        AtomResult run = ftp().file(".secrets.json", GOOD_SECRETS)
                .file(".atoms/run9/.secrets.json", "{\"token\": \"super-secret\"}")
                .input("sourceFiles", "**/*")
                .run();

        run.assertOk();
        assertFalse(uploaded("site/.atoms/run9/.secrets.json"), "the runner's secrets were uploaded over FTP");
        assertTrue(uploaded("site/dist/app.jar"), "a real file was skipped along with the scratch");
    }

    @Test
    @DisplayName("the password is not in the command line or in the log")
    void keepsThePasswordOutOfTheLog() throws Exception {
        start();
        AtomResult run = ftp().file(".secrets.json", GOOD_SECRETS).run();

        run.assertOk();
        assertFalse(run.console().contains("--user"), "the command line must not carry --user:\n" + run.console());
        assertFalse(run.console().contains("s3cret"), "the password ended up in the log:\n" + run.console());
    }

    @Test
    @DisplayName("a narrower pattern uploads one file, and exclude takes the text files back out")
    void narrowsWithPatternAndExclude() throws Exception {
        start();
        AtomResult narrowed = ftp().file(".secrets.json", GOOD_SECRETS)
                .input("sourceFiles", "dist/*.jar")
                .run();
        narrowed.assertOk();
        assertEquals("1", narrowed.field("filesUploaded"), narrowed.console());

        AtomResult excluded = ftp().file(".secrets.json", GOOD_SECRETS)
                .input("exclude", "**/*.txt")
                .run();
        excluded.assertOk();
        assertEquals("1", excluded.field("filesUploaded"), excluded.console());
    }

    @Test
    @DisplayName("a different target directory is created for us")
    void createsAFreshRemoteDirectory() throws Exception {
        start();
        AtomResult run = ftp().file(".secrets.json", GOOD_SECRETS)
                .input("remoteDir", "new/place")
                .run();

        assertEquals("success", run.status(), run.console());
        assertTrue(uploaded("new/place/dist/app.jar"), "the directory was not created remotely");
    }

    @Test
    @DisplayName("a wrong password is a USER error that quotes the FTP reply")
    void wrongPasswordIsAUserError() throws Exception {
        start();
        AtomResult run = ftp().file(".secrets.json", "{\"password\": \"wrong\"}").run();

        assertEquals("error", run.status(), run.console());
        String output = run.file("output.json");
        assertTrue(output.contains("530"), "the FTP reply should surface:\n" + output);
        assertTrue(output.contains("\"errorType\":1"), "a login failure is USER(1):\n" + output);
    }

    @Test
    @DisplayName("a server that refuses writes fails the upload and surfaces its reply")
    void readOnlyServerFails() throws Exception {
        start("--read-only");
        AtomResult run = ftp().file(".secrets.json", GOOD_SECRETS).run();

        assertEquals("error", run.status(), run.console());
        String output = run.file("output.json");
        assertTrue(output.contains("550"), "the refusal should surface:\n" + output);
    }

    @Test
    @DisplayName("bad input is refused: no password, a root remoteDir, and a pattern that matches nothing")
    void refusesBadInput() throws Exception {
        start();

        AtomResult noPassword = ftp().file(".secrets.json", "{}").run();
        assertEquals("error", noPassword.status(), noPassword.console());
        assertTrue(noPassword.file("output.json").contains("password is required"),
                noPassword.file("output.json"));

        AtomResult rootDir = ftp().file(".secrets.json", GOOD_SECRETS).input("remoteDir", "/").run();
        assertEquals("error", rootDir.status(), rootDir.console());

        AtomResult nothing = ftp().file(".secrets.json", GOOD_SECRETS)
                .input("sourceFiles", "nothing/**/*.jar")
                .run();
        assertEquals("error", nothing.status(), nothing.console());
        assertTrue(nothing.file("output.json").contains("no file matched"), nothing.file("output.json"));
    }
}
