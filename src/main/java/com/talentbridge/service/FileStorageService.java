package com.talentbridge.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.UUID;

@Service
@Slf4j
public class FileStorageService {
    @Value("${storage.local-path:./uploads}") private String localPath;
    @Value("${supabase.url:}") private String supabaseUrl;
    @Value("${supabase.service-role-key:}") private String supabaseServiceRoleKey;
    @Value("${supabase.storage-bucket:talentbridge-files}") private String supabaseBucket;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @PostConstruct
    public void init() {
        if (usesSupabase()) {
            log.info("Supabase file storage ready with bucket: {}", supabaseBucket);
            return;
        }
        try {
            Files.createDirectories(Paths.get(localPath));
            log.info("File storage ready at: {}", Paths.get(localPath).toAbsolutePath());
        } catch (IOException e) { log.warn("Could not create storage dir: {}", e.getMessage()); }
    }

    public String upload(MultipartFile file, String prefix) {
        try {
            String original = file.getOriginalFilename() != null
                ? file.getOriginalFilename().replaceAll("[^a-zA-Z0-9._-]", "_") : "file";
            String filename = UUID.randomUUID() + "_" + original;
            String key = prefix + "/" + filename;
            if (usesSupabase()) return uploadToSupabase(file, key);
            Path dest = Paths.get(localPath, key);
            Files.createDirectories(dest.getParent());
            Files.write(dest, file.getBytes());
            return "/files/" + key;
        } catch (IOException e) { throw new RuntimeException("File upload failed: " + e.getMessage(), e); }
    }

    private boolean usesSupabase() {
        return supabaseUrl != null && !supabaseUrl.isBlank()
            && supabaseServiceRoleKey != null && !supabaseServiceRoleKey.isBlank();
    }

    private String uploadToSupabase(MultipartFile file, String key) throws IOException {
        String baseUrl = supabaseUrl.replaceAll("/+$", "");
        String objectPath = "/storage/v1/object/" + supabaseBucket + "/" + key;
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + objectPath))
            .header("apikey", supabaseServiceRoleKey)
            .header("Authorization", "Bearer " + supabaseServiceRoleKey)
            .header("Content-Type", file.getContentType() != null ? file.getContentType() : "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(file.getBytes()))
            .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Supabase Storage returned HTTP " + response.statusCode());
            }
            return baseUrl + objectPath.replace("/storage/v1/object/", "/storage/v1/object/public/");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Supabase Storage upload interrupted", e);
        }
    }
}
