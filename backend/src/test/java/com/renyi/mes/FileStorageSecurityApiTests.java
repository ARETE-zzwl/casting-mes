package com.renyi.mes;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Base64;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class FileStorageSecurityApiTests {
	@TempDir
	static Path storage;

	@DynamicPropertySource
	static void storageDirectory(DynamicPropertyRegistry registry) {
		registry.add("mes.storage.upload-dir", () -> storage.resolve("not-created-yet").toString());
	}

	private static final byte[] VALID_PNG = Base64.getDecoder().decode(
		"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLlnAAAAABJRU5ErkJggg=="
	);

	@Autowired
	private MockMvc mvc;

	@Test
	void rejectsImageThatOnlyPretendsToBePng() throws Exception {
		var file = new MockMultipartFile("file", "pretend.png", "image/png", "not a PNG".getBytes());

		mvc.perform(multipart("/api/files/upload")
				.file(file)
				.param("category", "MOLD_IMAGE"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("FILE_SIGNATURE_INVALID"));
	}

	@Test
	void servesUploadedFilesWithoutBrowserCaching() throws Exception {
		var file = new MockMultipartFile("file", "mold.png", "image/png", VALID_PNG);
		var upload = mvc.perform(multipart("/api/files/upload")
				.file(file)
				.param("category", "MOLD_IMAGE"))
			.andExpect(status().isCreated())
			.andReturn();
		String url = JsonPath.read(upload.getResponse().getContentAsString(), "$.url");

		mvc.perform(get(url))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
	}

	@Test
	void allowsBearerHeaderForConfiguredFrontendOrigin() throws Exception {
		mvc.perform(options("/api/products")
				.header(HttpHeaders.ORIGIN, "http://localhost:5173")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,x-operator-code"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsStringIgnoringCase("Authorization")));
	}
}
