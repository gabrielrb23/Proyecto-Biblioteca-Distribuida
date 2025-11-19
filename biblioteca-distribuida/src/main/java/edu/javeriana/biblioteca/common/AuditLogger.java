package edu.javeriana.biblioteca.common;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.lang.management.ManagementFactory;

public class AuditLogger {

    private static final Path LOG_FILE = Paths.get("audit.log");
    private static final long MAX_SIZE = 5 * 1024 * 1024; // 5 MB

    private static String processId() {
        // Obtiene el PID del proceso JVM
        try {
            String jvm = ManagementFactory.getRuntimeMXBean().getName(); // formato pid@host
            return jvm.split("@")[0];
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static synchronized void rotateIfNeeded() {
        // Rota el archivo si supera el tamaño límite
        try {
            if (Files.exists(LOG_FILE) && Files.size(LOG_FILE) > MAX_SIZE) {
                Path target = Paths.get("audit.log.old");
                Files.move(LOG_FILE, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("AuditLogger rotation error: " + e.getMessage());
        }
    }

    public static synchronized void log(String actor, String action, String message, String status) {
        // Registra una línea de auditoría con timestamp, PID y usuario
        rotateIfNeeded();
        String ts = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        String user = System.getenv().getOrDefault("USER", System.getenv().getOrDefault("USERNAME", "unknown"));
        String pid = processId();
        String line = String.format(
                "%s | pid=%s | user=%s | actor=%s | action=%s | status=%s | %s%n",
                ts, pid, user, actor, action, status, message == null ? "" : message);

        // Escribe en el archivo audit.log
        try {
            Files.writeString(LOG_FILE, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("AuditLogger write error: " + e.getMessage());
        }
    }
}
