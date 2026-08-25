package com.emergentterrain.secrets;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * AES-256-GCM seal/unseal for a local secrets file — the crypto behind the
 * {@link EncryptedFileSecretSource} backend.
 *
 * <p>One bootstrap passphrase (held in an env var) protects everything else, so
 * the encrypted blob is safe to commit / sync — it's useless without the key.
 * That follows the one-bootstrap-secret pattern without an external CLI, server, or
 * network.
 *
 * <p><b>Format</b> (binary, then Base64-encoded into a one-line text file):
 * <pre>{@code  magic "ETS1" (4) | version (1) | salt (16) | iv (12) | ciphertext+GCM-tag }</pre>
 * The key is derived per-seal with PBKDF2-HMAC-SHA256 ({@value #PBKDF2_ITERS}
 * iterations, 256-bit) over a fresh random salt; encryption is AES/GCM with a
 * fresh random IV and the {@code magic+version} bound as AAD. A fresh salt per
 * seal means a fresh key per seal, so IV reuse across files is a non-issue.
 *
 * <p>Wrong passphrase or any tampering fails the GCM tag check and throws — the
 * post-processor treats that as fail-open.
 */
public final class EncryptedSecretsFile {

    private static final byte[] MAGIC = {'E', 'T', 'S', '1'};
    private static final byte VERSION = 1;
    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    /** OWASP-recommended PBKDF2-HMAC-SHA256 work factor (2023). */
    private static final int PBKDF2_ITERS = 600_000;
    private static final int HEADER_LEN = MAGIC.length + 1 + SALT_LEN + IV_LEN; // 33

    private static final String DEFAULT_PASSPHRASE_ENV = "ET_SECRETS_KEY";

    private EncryptedSecretsFile() {}

    /** Encrypt {@code plaintext} under {@code passphrase} → a Base64 text blob (header embedded). */
    static String seal(byte[] plaintext, char[] passphrase) throws Exception {
        SecureRandom rng = new SecureRandom();
        byte[] salt = new byte[SALT_LEN];
        rng.nextBytes(salt);
        byte[] iv = new byte[IV_LEN];
        rng.nextBytes(iv);

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), new GCMParameterSpec(TAG_BITS, iv));
        c.updateAAD(aad());
        byte[] ct = c.doFinal(plaintext);

        byte[] header = header(salt, iv);
        byte[] out = new byte[header.length + ct.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(ct, 0, out, header.length, ct.length);
        return Base64.getEncoder().encodeToString(out);
    }

    /** Decrypt a Base64 blob produced by {@link #seal} → plaintext bytes. */
    static byte[] unseal(String blob, char[] passphrase) throws Exception {
        byte[] in;
        try {
            in = Base64.getDecoder().decode(blob.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("secrets file is not valid base64", e);
        }
        if (in.length < HEADER_LEN) {
            throw new IllegalArgumentException("secrets file too short / corrupt");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (in[i] != MAGIC[i]) {
                throw new IllegalArgumentException("bad magic — not an ET secrets file");
            }
        }
        if (in[MAGIC.length] != VERSION) {
            throw new IllegalArgumentException("unsupported secrets file version " + in[MAGIC.length]);
        }
        byte[] salt = Arrays.copyOfRange(in, MAGIC.length + 1, MAGIC.length + 1 + SALT_LEN);
        byte[] iv = Arrays.copyOfRange(in, MAGIC.length + 1 + SALT_LEN, HEADER_LEN);
        byte[] ct = Arrays.copyOfRange(in, HEADER_LEN, in.length);

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), new GCMParameterSpec(TAG_BITS, iv));
        c.updateAAD(aad());
        try {
            return c.doFinal(ct);
        } catch (Exception e) {
            // AEADBadTagException et al. — wrong passphrase, or corrupt/tampered file.
            throw new IllegalStateException("decryption failed — wrong passphrase or corrupt/tampered file");
        }
    }

    private static byte[] header(byte[] salt, byte[] iv) {
        byte[] h = new byte[HEADER_LEN];
        System.arraycopy(MAGIC, 0, h, 0, MAGIC.length);
        h[MAGIC.length] = VERSION;
        System.arraycopy(salt, 0, h, MAGIC.length + 1, SALT_LEN);
        System.arraycopy(iv, 0, h, MAGIC.length + 1 + SALT_LEN, IV_LEN);
        return h;
    }

    /** Authenticate the format header so a version downgrade can't go unnoticed. */
    private static byte[] aad() {
        byte[] a = new byte[MAGIC.length + 1];
        System.arraycopy(MAGIC, 0, a, 0, MAGIC.length);
        a[MAGIC.length] = VERSION;
        return a;
    }

    private static SecretKeySpec deriveKey(char[] passphrase, byte[] salt) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, PBKDF2_ITERS, KEY_BITS);
        try {
            return new SecretKeySpec(f.generateSecret(spec).getEncoded(), "AES");
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * Read a passphrase from a file, stripping a single trailing newline
     * ({@code \n} or {@code \r\n}) so a plain {@code printf > file} or an editor
     * save round-trips. Interior characters (including spaces) are preserved, so
     * seal and unseal must both use the same source for the same passphrase.
     */
    static char[] readPassphraseFile(Path file) throws Exception {
        String s = Files.readString(file, StandardCharsets.UTF_8);
        int n = s.length();
        if (s.endsWith("\r\n")) {
            n -= 2;
        } else if (n > 0 && (s.charAt(n - 1) == '\n' || s.charAt(n - 1) == '\r')) {
            n -= 1;
        }
        return s.substring(0, n).toCharArray();
    }

    /** Expand a leading {@code ~} / {@code ~/} to {@code user.home}; other paths pass through. */
    static String expandHome(String path) {
        if (path != null && (path.equals("~") || path.startsWith("~/"))) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    // ----- tiny JDK-only CLI to create / inspect the file (no Spring) --------
    //
    // Passphrase via a 0600 file (keeps it out of shell history and /proc environ):
    //   ( umask 077; read -rsp 'passphrase: ' PP; printf '%s' "$PP" > pass; unset PP )
    //   java -cp target/classes com.emergentterrain.secrets.EncryptedSecretsFile \
    //     seal ~/.emergentterrain/secrets.enc --passphrase-file pass < secrets.json
    //
    // Or via env (default ET_SECRETS_KEY) when convenience beats history hygiene:
    //   echo '{"GROQ_API_KEY":"gsk_..."}' | ET_SECRETS_KEY='pw' java -cp target/classes \
    //     com.emergentterrain.secrets.EncryptedSecretsFile seal ~/.emergentterrain/secrets.enc
    //
    public static void main(String[] args) throws Exception {
        String passphraseFile = null;
        List<String> pos = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--passphrase-file") || a.equals("-f")) {
                if (i + 1 >= args.length) {
                    System.err.println(a + " needs a path argument");
                    System.exit(2);
                    return;
                }
                passphraseFile = args[++i];
            } else {
                pos.add(a);
            }
        }
        if (pos.size() < 2 || !(pos.get(0).equals("seal") || pos.get(0).equals("unseal"))) {
            System.err.println("usage: EncryptedSecretsFile <seal|unseal> <file> [passphrase-env] [--passphrase-file <path>]");
            System.err.println("  seal:   reads plaintext JSON from stdin, writes encrypted <file> (mode 0600)");
            System.err.println("  unseal: decrypts <file>, prints plaintext JSON to stdout");
            System.err.println("  passphrase: --passphrase-file <path> wins; else env (default " + DEFAULT_PASSPHRASE_ENV + ")");
            System.exit(2);
            return;
        }
        String cmd = pos.get(0);
        Path file = Path.of(expandHome(pos.get(1)));
        String envName = pos.size() >= 3 ? pos.get(2) : DEFAULT_PASSPHRASE_ENV;

        char[] passphrase;
        if (passphraseFile != null) {
            passphrase = readPassphraseFile(Path.of(expandHome(passphraseFile)));
            if (passphrase.length == 0) {
                System.err.println("passphrase file is empty: " + passphraseFile);
                System.exit(1);
                return;
            }
        } else {
            String pass = System.getenv(envName);
            if (pass == null || pass.isBlank()) {
                System.err.println("passphrase env '" + envName + "' is not set (or pass --passphrase-file <path>)");
                System.exit(1);
                return;
            }
            passphrase = pass.toCharArray();
        }

        if (cmd.equals("seal")) {
            byte[] plaintext = System.in.readAllBytes();
            if (plaintext.length == 0) {
                System.err.println("no plaintext on stdin");
                System.exit(1);
                return;
            }
            String head = new String(plaintext, StandardCharsets.UTF_8).strip();
            if (!head.startsWith("{") && !head.startsWith("[")) {
                System.err.println("warning: stdin doesn't look like JSON (expected '{' or '[') — sealing anyway");
            }
            writePrivate(file, seal(plaintext, passphrase));
            System.err.println("sealed " + plaintext.length + " bytes -> " + file + " (mode 0600)");
        } else {
            byte[] plaintext = unseal(Files.readString(file), passphrase);
            System.out.write(plaintext);
            System.out.flush();
        }
    }

    private static void writePrivate(Path file, String content) throws Exception {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, content + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem (e.g. Windows) — best effort.
        }
    }
}
