package com.ecommerce.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * One S3Proxy per test JVM with in-memory storage, run from the jar Gradle resolved (ADR-015). It runs in its own
 * process so its dependencies never meet the application's, and it stops with the JVM.
 */
public final class S3ProxySupport {

    public static final String BUCKET = "ecommerce-media-test";
    public static final String REGION = "ap-south-1";
    public static final String ACCESS_KEY = "local-access-key";
    public static final String SECRET_KEY = "local-secret-key";

    private static final Duration STARTUP = Duration.ofSeconds(60);

    private static URI endpoint;

    private S3ProxySupport() {
    }

    public static synchronized String endpoint() {
        if (endpoint == null) {
            endpoint = start();
        }
        return endpoint.toString();
    }

    public static String publicBaseUrl() {
        return endpoint() + "/" + BUCKET;
    }

    /** A client with full access, for arranging and inspecting storage behind the application's back. */
    public static S3Client client() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint()))
                .region(Region.of(REGION))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY,
                        SECRET_KEY)))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    private static URI start() {
        String jar = System.getProperty("s3proxy.jar");
        if (jar == null) {
            throw new IllegalStateException("System property s3proxy.jar is missing; run the tests through Gradle.");
        }
        try {
            int port = freePort();
            Path properties = Files.createTempFile("s3proxy", ".properties");
            Files.write(properties, List.of(
                    "s3proxy.endpoint=http://127.0.0.1:" + port,
                    "s3proxy.authorization=aws-v2-or-v4",
                    "s3proxy.identity=" + ACCESS_KEY,
                    "s3proxy.credential=" + SECRET_KEY,
                    "jclouds.provider=transient"));
            Path log = Files.createTempFile("s3proxy", ".log");
            String java = ProcessHandle.current().info().command().orElse("java");
            Process process = new ProcessBuilder(java, "-Xmx256m", "-jar", jar, "--properties", properties.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));
            awaitPort(port, process, log);
            URI uri = URI.create("http://127.0.0.1:" + port);
            endpoint = uri;
            try (S3Client s3 = client()) {
                s3.createBucket(request -> request.bucket(BUCKET));
            }
            return uri;
        } catch (IOException e) {
            throw new UncheckedIOException("S3Proxy did not start", e);
        }
    }

    private static void awaitPort(int port, Process process, Path log) throws IOException {
        Instant deadline = Instant.now().plus(STARTUP);
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                throw new IllegalStateException("S3Proxy exited:\n" + Files.readString(log));
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException notYet) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        }
        process.destroy();
        throw new IllegalStateException("S3Proxy did not listen within " + STARTUP + ":\n" + Files.readString(log));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
