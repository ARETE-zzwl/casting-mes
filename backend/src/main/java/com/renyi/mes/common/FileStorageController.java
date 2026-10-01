package com.renyi.mes.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@RestController
@RequestMapping("/api/files")
class FileStorageController {

	private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;
	private static final Map<String, Set<String>> ALLOWED_TYPES = Map.of(
		"ORDER_DRAWING", Set.of("application/pdf", "image/jpeg", "image/png", "image/webp"),
		"ORDER_CONTRACT", Set.of("application/pdf", "image/jpeg", "image/png", "image/webp"),
		"PRODUCT_MODEL", Set.of("image/jpeg", "image/png", "image/webp"),
		"MOLD_IMAGE", Set.of("image/jpeg", "image/png", "image/webp"),
		"PROCESS_CARD_IMAGE", Set.of("image/jpeg", "image/png", "image/webp"),
		"EXECUTION_PHOTO", Set.of("image/jpeg", "image/png", "image/webp")
	);
	private final Path root;
	private final Environment environment;
	private final AttachmentAccess attachments;

	FileStorageController(@Value("${mes.storage.upload-dir:./data/uploads}") String uploadDir,
			Environment environment, AttachmentAccess attachments) {
		this.root = Path.of(uploadDir).toAbsolutePath().normalize();
		this.environment = environment;
		this.attachments = attachments;
	}

	@PostMapping("/upload")
	@ResponseStatus(HttpStatus.CREATED)
	StoredFileView upload(@RequestParam String category, @RequestParam MultipartFile file) {
		String normalizedCategory = category.trim().toUpperCase(Locale.ROOT);
		Set<String> allowed = ALLOWED_TYPES.get(normalizedCategory);
		if (allowed == null) throw DomainException.badRequest("FILE_CATEGORY_INVALID", "不支持的附件分类");
		attachments.requireUpload(normalizedCategory);
		if (file.isEmpty()) throw DomainException.badRequest("FILE_EMPTY", "请选择需要上传的文件");
		if (file.getSize() > MAX_FILE_SIZE) throw DomainException.badRequest("FILE_TOO_LARGE", "单个文件不得超过 20MB");
		String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
		if (!allowed.contains(contentType)) throw DomainException.badRequest("FILE_TYPE_INVALID", "该附件分类不支持此文件类型");
		validateFileSignature(file, contentType);
		String extension = extensionFor(contentType);
		Path directory = root.resolve(normalizedCategory.toLowerCase(Locale.ROOT)).normalize();
		if (!directory.startsWith(root)) throw DomainException.badRequest("FILE_PATH_INVALID", "附件路径不合法");
		String storedName = UUID.randomUUID() + extension;
		try (InputStream input = file.getInputStream()) {
			Files.createDirectories(directory);
			Files.copy(input, directory.resolve(storedName), StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException exception) {
			throw DomainException.conflict("FILE_STORAGE_UNAVAILABLE", "附件暂时无法保存，请稍后重试");
		}
		String url = "/uploads/" + normalizedCategory.toLowerCase(Locale.ROOT) + "/" + storedName;
		if (environment.acceptsProfiles(Profiles.of("prod | secure"))) {
			try { attachments.register(url, contentType); }
			catch (RuntimeException exception) {
				try { Files.deleteIfExists(directory.resolve(storedName)); }
				catch (IOException cleanupFailure) { exception.addSuppressed(cleanupFailure); }
				throw exception;
			}
		}
		return new StoredFileView(url,
			file.getOriginalFilename(), file.getSize(), contentType);
	}

	private static void validateFileSignature(MultipartFile file, String contentType) {
		try (InputStream input = file.getInputStream()) {
			byte[] header = input.readNBytes(12);
			boolean valid = switch (contentType) {
				case "application/pdf" -> startsWith(header, 0x25, 0x50, 0x44, 0x46, 0x2D);
				case "image/png" -> startsWith(header, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
				case "image/jpeg" -> startsWith(header, 0xFF, 0xD8, 0xFF);
				case "image/webp" -> startsWith(header, 0x52, 0x49, 0x46, 0x46)
					&& startsWithAt(header, 8, 0x57, 0x45, 0x42, 0x50);
				default -> false;
			};
			if (!valid) throw DomainException.badRequest("FILE_SIGNATURE_INVALID", "文件内容与声明类型不一致");
		}
		catch (IOException exception) {
			throw DomainException.conflict("FILE_STORAGE_UNAVAILABLE", "附件暂时无法读取，请稍后重试");
		}
	}

	private static boolean startsWith(byte[] data, int... expected) {
		return startsWithAt(data, 0, expected);
	}

	private static boolean startsWithAt(byte[] data, int offset, int... expected) {
		if (data.length < offset + expected.length) return false;
		for (int index = 0; index < expected.length; index++) {
			if (Byte.toUnsignedInt(data[offset + index]) != expected[index]) return false;
		}
		return true;
	}

	private static String extensionFor(String contentType) {
		return switch (contentType) {
			case "application/pdf" -> ".pdf";
			case "image/jpeg" -> ".jpg";
			case "image/png" -> ".png";
			case "image/webp" -> ".webp";
			default -> throw DomainException.badRequest("FILE_TYPE_INVALID", "文件类型不受支持");
		};
	}

	record StoredFileView(String url, String originalName, long size, String contentType) { }
}

@Configuration
@Profile("!prod & !secure")
class FileStorageWebConfiguration implements WebMvcConfigurer {
	private final String uploadDir;

	FileStorageWebConfiguration(@Value("${mes.storage.upload-dir:./data/uploads}") String uploadDir) {
		String uri = Path.of(uploadDir).toAbsolutePath().normalize().toUri().toString();
		this.uploadDir = uri.endsWith("/") ? uri : uri + "/";
	}

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler("/uploads/**").addResourceLocations(uploadDir);
	}
}
