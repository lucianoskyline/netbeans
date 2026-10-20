/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.java.starters;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Creates the project directory for each kind of starter. */
final class Generator {

    // checked against Maven Central, October 2026
    static final String COMPILER_PLUGIN = "3.16.0"; // NOI18N
    static final String SUREFIRE_PLUGIN = "3.6.0"; // NOI18N
    static final String WAR_PLUGIN = "3.5.1"; // NOI18N
    static final String JUNIT = "6.1.3"; // NOI18N
    static final String JETTY = "12.1.14"; // NOI18N

    enum Kind { MAVEN, WEB, SPRING, QUARKUS }

    /** What the user entered in the wizard. */
    static final class Settings {
        String name;
        File location;
        String groupId;
        String artifactId;
        String packageName;
        String javaVersion;
        /** Spring Boot version, Quarkus stream key or Jakarta EE version ("11"/"10"). */
        String platform;
        List<String> dependencies = new ArrayList<>();

        File projectDir() {
            return new File(location, name);
        }
    }

    private Generator() {
    }

    static File generate(Kind kind, Settings s) throws IOException, InterruptedException {
        File dir = s.projectDir();
        Files.createDirectories(dir.toPath());
        switch (kind) {
            case MAVEN -> mavenApp(s, dir);
            case WEB -> webApp(s, dir);
            case SPRING -> unzip(download(springUrl(s)), dir.toPath());
            case QUARKUS -> unzip(download(quarkusUrl(s)), dir.toPath());
        }
        String actions = nbActions(kind);
        if (actions != null) {
            write(dir, "nbactions.xml", actions); // NOI18N
        }
        return dir;
    }

    static String springUrl(Settings s) {
        return "https://start.spring.io/starter.zip?type=maven-project&language=java" // NOI18N
                + "&bootVersion=" + Catalog.enc(s.platform) // NOI18N
                + "&groupId=" + Catalog.enc(s.groupId) // NOI18N
                + "&artifactId=" + Catalog.enc(s.artifactId) // NOI18N
                + "&name=" + Catalog.enc(s.name) // NOI18N
                + "&packageName=" + Catalog.enc(s.packageName) // NOI18N
                + "&packaging=jar&javaVersion=" + Catalog.enc(s.javaVersion) // NOI18N
                + "&baseDir=" + Catalog.enc(s.artifactId) // NOI18N
                + (s.dependencies.isEmpty() ? "" : "&dependencies=" + Catalog.enc(String.join(",", s.dependencies))); // NOI18N
    }

    static String quarkusUrl(Settings s) {
        StringBuilder url = new StringBuilder("https://code.quarkus.io/api/download?b=MAVEN") // NOI18N
                .append("&g=").append(Catalog.enc(s.groupId)) // NOI18N
                .append("&a=").append(Catalog.enc(s.artifactId)) // NOI18N
                .append("&j=").append(Catalog.enc(s.javaVersion)) // NOI18N
                .append("&S=").append(Catalog.enc(s.platform)) // NOI18N
                .append("&nc=false"); // NOI18N  include example code
        for (String e : s.dependencies) {
            url.append("&e=").append(Catalog.enc(e)); // NOI18N
        }
        return url.toString();
    }

    static byte[] download(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", "NetBeans-Java-Starters") // NOI18N
                .GET().build();
        HttpResponse<byte[]> r = Catalog.HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() / 100 != 2) {
            String body = new String(r.body(), StandardCharsets.UTF_8);
            throw new IOException("HTTP " + r.statusCode() + ": " + (body.length() > 400 ? body.substring(0, 400) : body)); // NOI18N
        }
        return r.body();
    }

    /** Extracts the archive into {@code target}, dropping its single top-level folder. */
    static void unzip(byte[] zip, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                String name = e.getName();
                int slash = name.indexOf('/');
                String relative = slash >= 0 ? name.substring(slash + 1) : name;
                if (relative.isEmpty()) {
                    continue;
                }
                Path out = root.resolve(relative).normalize();
                if (!out.startsWith(root)) {
                    throw new IOException("Unsafe entry in archive: " + name); // NOI18N
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    if (out.getFileName().toString().equals("mvnw")) { // NOI18N
                        out.toFile().setExecutable(true);
                    }
                }
            }
        }
    }

    static void write(File dir, String path, String content) throws IOException {
        Path p = dir.toPath().resolve(path);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    private static String pkgDir(Settings s) {
        return s.packageName.replace('.', '/');
    }

    private static String header(Settings s, String packaging) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n"
                + "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                + "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
                + "    <modelVersion>4.0.0</modelVersion>\n\n"
                + "    <groupId>" + s.groupId + "</groupId>\n"
                + "    <artifactId>" + s.artifactId + "</artifactId>\n"
                + "    <version>1.0-SNAPSHOT</version>\n"
                + "    <packaging>" + packaging + "</packaging>\n"
                + "    <name>" + s.name + "</name>\n\n";
    }

    private static void mavenApp(Settings s, File dir) throws IOException {
        String main = s.packageName + ".Main"; // NOI18N
        write(dir, "pom.xml", header(s, "jar") // NOI18N
                + "    <properties>\n"
                + "        <maven.compiler.release>" + s.javaVersion + "</maven.compiler.release>\n"
                + "        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n"
                + "        <exec.mainClass>" + main + "</exec.mainClass>\n"
                + "    </properties>\n\n"
                + "    <dependencies>\n"
                + "        <dependency>\n"
                + "            <groupId>org.junit.jupiter</groupId>\n"
                + "            <artifactId>junit-jupiter</artifactId>\n"
                + "            <version>" + JUNIT + "</version>\n"
                + "            <scope>test</scope>\n"
                + "        </dependency>\n"
                + "    </dependencies>\n\n"
                + "    <build>\n"
                + "        <plugins>\n"
                + plugin("org.apache.maven.plugins", "maven-compiler-plugin", COMPILER_PLUGIN, "") // NOI18N
                + plugin("org.apache.maven.plugins", "maven-surefire-plugin", SUREFIRE_PLUGIN, "") // NOI18N
                + "        </plugins>\n"
                + "    </build>\n"
                + "</project>\n");
        write(dir, "src/main/java/" + pkgDir(s) + "/Main.java", "package " + s.packageName + ";\n\n" // NOI18N
                + "public class Main {\n\n"
                + "    static String greeting(String name) {\n"
                + "        return \"Hello, \" + name + \"!\";\n"
                + "    }\n\n"
                + "    public static void main(String[] args) {\n"
                + "        System.out.println(greeting(\"" + s.name + "\"));\n"
                + "    }\n"
                + "}\n");
        write(dir, "src/test/java/" + pkgDir(s) + "/MainTest.java", "package " + s.packageName + ";\n\n" // NOI18N
                + "import static org.junit.jupiter.api.Assertions.assertEquals;\n\n"
                + "import org.junit.jupiter.api.Test;\n\n"
                + "class MainTest {\n\n"
                + "    @Test\n"
                + "    void greets() {\n"
                + "        assertEquals(\"Hello, World!\", Main.greeting(\"World\"));\n"
                + "    }\n"
                + "}\n");
    }

    private static void webApp(Settings s, File dir) throws IOException {
        boolean ee11 = !"10".equals(s.platform); // NOI18N
        String ee = ee11 ? "ee11" : "ee10"; // NOI18N
        write(dir, "pom.xml", header(s, "war") // NOI18N
                + "    <properties>\n"
                + "        <maven.compiler.release>" + s.javaVersion + "</maven.compiler.release>\n"
                + "        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n"
                + "    </properties>\n\n"
                + "    <dependencies>\n"
                + "        <dependency>\n"
                + "            <groupId>jakarta.platform</groupId>\n"
                + "            <artifactId>jakarta.jakartaee-web-api</artifactId>\n"
                + "            <version>" + (ee11 ? "11.0.0" : "10.0.0") + "</version>\n" // NOI18N
                + "            <scope>provided</scope>\n"
                + "        </dependency>\n"
                + "        <dependency>\n"
                + "            <groupId>org.junit.jupiter</groupId>\n"
                + "            <artifactId>junit-jupiter</artifactId>\n"
                + "            <version>" + JUNIT + "</version>\n"
                + "            <scope>test</scope>\n"
                + "        </dependency>\n"
                + "    </dependencies>\n\n"
                + "    <build>\n"
                + "        <finalName>" + s.artifactId + "</finalName>\n"
                + "        <plugins>\n"
                + plugin("org.apache.maven.plugins", "maven-compiler-plugin", COMPILER_PLUGIN, "") // NOI18N
                + plugin("org.apache.maven.plugins", "maven-surefire-plugin", SUREFIRE_PLUGIN, "") // NOI18N
                + plugin("org.apache.maven.plugins", "maven-war-plugin", WAR_PLUGIN, "") // NOI18N
                + "            <!-- mvn jetty:run serves the app on http://localhost:8080/ without an application server -->\n"
                + plugin("org.eclipse.jetty." + ee, "jetty-" + ee + "-maven-plugin", JETTY, // NOI18N
                        "                <configuration>\n"
                        + "                    <scan>2</scan>\n"
                        + "                </configuration>\n")
                + "        </plugins>\n"
                + "    </build>\n"
                + "</project>\n");
        write(dir, "src/main/java/" + pkgDir(s) + "/HelloServlet.java", "package " + s.packageName + ";\n\n" // NOI18N
                + "import jakarta.servlet.annotation.WebServlet;\n"
                + "import jakarta.servlet.http.HttpServlet;\n"
                + "import jakarta.servlet.http.HttpServletRequest;\n"
                + "import jakarta.servlet.http.HttpServletResponse;\n"
                + "import java.io.IOException;\n\n"
                + "@WebServlet(\"/hello\")\n"
                + "public class HelloServlet extends HttpServlet {\n\n"
                + "    @Override\n"
                + "    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {\n"
                + "        String name = req.getParameter(\"name\");\n"
                + "        resp.setContentType(\"text/plain;charset=UTF-8\");\n"
                + "        resp.getWriter().println(\"Hello, \" + (name == null || name.isBlank() ? \"World\" : name) + \"!\");\n"
                + "    }\n"
                + "}\n");
        write(dir, "src/main/webapp/index.html", "<!DOCTYPE html>\n" // NOI18N
                + "<html>\n"
                + "<head>\n"
                + "    <meta charset=\"UTF-8\">\n"
                + "    <title>" + s.name + "</title>\n"
                + "</head>\n"
                + "<body>\n"
                + "    <h1>" + s.name + "</h1>\n"
                + "    <form action=\"hello\">\n"
                + "        <input name=\"name\" placeholder=\"Your name\">\n"
                + "        <button>Say hello</button>\n"
                + "    </form>\n"
                + "</body>\n"
                + "</html>\n");
    }

    private static String plugin(String group, String artifact, String version, String config) {
        return "            <plugin>\n"
                + "                <groupId>" + group + "</groupId>\n"
                + "                <artifactId>" + artifact + "</artifactId>\n"
                + "                <version>" + version + "</version>\n"
                + config
                + "            </plugin>\n";
    }

    /** NetBeans Run/Debug mappings, so the toolbar buttons start the framework the usual way. */
    static String nbActions(Kind kind) {
        return switch (kind) {
            case WEB -> actions("war", "jetty:run", // NOI18N
                    // Jetty runs inside the Maven JVM, so the debugger attaches to Maven itself
                    "            <jpda.listen>maven</jpda.listen>\n");
            case SPRING -> actions("jar", "spring-boot:run", // NOI18N
                    "            <spring-boot.run.jvmArguments>-agentlib:jdwp=transport=dt_socket,server=n,address=${jpda.address}</spring-boot.run.jvmArguments>\n"
                    + "            <jpda.listen>true</jpda.listen>\n");
            case QUARKUS -> actions("jar", "quarkus:dev", // NOI18N
                    "            <debug>false</debug>\n"
                    + "            <jvm.args>-agentlib:jdwp=transport=dt_socket,server=n,address=${jpda.address}</jvm.args>\n"
                    + "            <jpda.listen>true</jpda.listen>\n");
            default -> null;
        };
    }

    private static String actions(String packaging, String goal, String debugProperties) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<actions>\n"
                + "    <action>\n"
                + "        <actionName>run</actionName>\n"
                + "        <packagings>\n"
                + "            <packaging>" + packaging + "</packaging>\n"
                + "        </packagings>\n"
                + "        <goals>\n"
                + "            <goal>" + goal + "</goal>\n"
                + "        </goals>\n"
                + "    </action>\n"
                + "    <action>\n"
                + "        <actionName>debug</actionName>\n"
                + "        <packagings>\n"
                + "            <packaging>" + packaging + "</packaging>\n"
                + "        </packagings>\n"
                + "        <goals>\n"
                + "            <goal>" + goal + "</goal>\n"
                + "        </goals>\n"
                + "        <properties>\n"
                + debugProperties
                + "        </properties>\n"
                + "    </action>\n"
                + "</actions>\n";
    }
}
