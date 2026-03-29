package com.talentbridge.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

@Service
@Slf4j
public class FileStorageService {
    @Value("${storage.local-path:./uploads}") private String localPath;

    @PostConstruct
    public void init() {
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
            Path dest = Paths.get(localPath, key);
            Files.createDirectories(dest.getParent());
            Files.write(dest, file.getBytes());
            return "/files/" + key;
        } catch (IOException e) { throw new RuntimeException("File upload failed: " + e.getMessage(), e); }
    }
}
