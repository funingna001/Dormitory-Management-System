package com.example.dormitory.business;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.*;

import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Semaphore;

import static com.example.dormitory.business.BusinessStore.*;

@Service
public class BusinessImages {
    private final Path directory;
    private final Semaphore permits = new Semaphore(3);

    public BusinessImages(@Value("${app.upload-dir}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    public String save(MultipartFile file) throws java.io.IOException {
        invalid(!file.isEmpty() && file.getSize() <= 5 * 1024 * 1024, "图片应为 1 字节至 5MB");
        if (!permits.tryAcquire())
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "图片处理中，请稍后重试");
        try (var raw = file.getInputStream(); var input = ImageIO.createImageInputStream(raw)) {
            var readers = ImageIO.getImageReaders(input);
            invalid(readers.hasNext(), "仅支持有效 PNG/JPEG 图片");
            var reader = readers.next();
            try {
                invalid(List.of("png", "jpeg", "jpg").contains(reader.getFormatName().toLowerCase(Locale.ROOT)), "仅支持 PNG/JPEG 图片");
                reader.setInput(input);
                invalid((long) reader.getWidth(0) * reader.getHeight(0) <= 8000000L, "图片像素过大");
                var decoded = reader.read(0);
                Files.createDirectories(directory);
                Path path = directory.resolve(UUID.randomUUID() + ".png");
                try (var output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
                    ImageIO.write(decoded, "png", output);
                } catch (java.io.IOException error) {
                    Files.deleteIfExists(path);
                    throw error;
                }
                return "/uploads/repair/" + path.getFileName();
            } finally {
                reader.dispose();
            }
        } catch (javax.imageio.IIOException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片损坏");
        } finally {
            permits.release();
        }
    }

    public void delete(String stored) throws java.io.IOException {
        if (stored != null) Files.deleteIfExists(resolve(stored));
    }

    private Path resolve(String stored) {
        var path = directory.resolve(stored.substring(stored.lastIndexOf('/') + 1)).normalize();
        check(path.startsWith(directory) && !Files.isSymbolicLink(path), "附件路径不合法");
        return path;
    }

    public ResponseEntity<byte[]> read(String stored) throws java.io.IOException {
        if (stored == null || stored.isBlank()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "无附件");
        Path path = resolve(stored);
        if (!Files.isRegularFile(path) || Files.size(path) > 40 * 1024 * 1024L)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不存在");
        byte[] bytes = Files.readAllBytes(path);
        MediaType type;
        if (bytes.length > 8 && bytes[0] == (byte) 137 && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71)
            type = MediaType.IMAGE_PNG;
        else if (bytes.length > 3 && bytes[0] == (byte) 255 && bytes[1] == (byte) 216 && bytes[2] == (byte) 255)
            type = MediaType.IMAGE_JPEG;
        else throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件格式不受支持");
        return ResponseEntity.ok().contentType(type).header("Cache-Control", "no-store").header("Content-Disposition", "inline; filename=image").body(bytes);
    }
}
