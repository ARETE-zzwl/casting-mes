package com.renyi.mes.engineering.web;

import java.util.List;
import java.util.UUID;

import com.renyi.mes.engineering.ProductApplication;
import com.renyi.mes.engineering.ProductApplication.CreateProductCommand;
import com.renyi.mes.engineering.ProductApplication.ProductView;
import com.renyi.mes.engineering.ProductApplication.UpdateProductCommand;
import com.renyi.mes.engineering.RouteType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
class ProductController {

	private final ProductApplication products;

	ProductController(ProductApplication products) {
		this.products = products;
	}

	@GetMapping
	List<ProductView> list() {
		return products.list();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	ProductView create(@Valid @RequestBody CreateProductRequest request) {
		return products.create(new CreateProductCommand(
			request.code(),
			request.name(),
			request.routeType(),
			request.routeVersion(),
			request.modelImageUrl(),
			request.specification(),
			request.material()
		));
	}

	@PostMapping("/{productId}/model-image")
	ProductView updateModelImage(@PathVariable UUID productId, @Valid @RequestBody ModelImageRequest request) {
		return products.updateModelImage(productId, request.modelImageUrl());
	}

	@PostMapping("/{productId}")
	ProductView update(@PathVariable UUID productId, @Valid @RequestBody UpdateProductRequest request) {
		return products.update(productId, new UpdateProductCommand(
			request.name(), request.routeType(), request.routeVersion(), request.specification(), request.material()
		));
	}

	record CreateProductRequest(
		@Size(max = 64) String code,
		@NotBlank @Size(max = 160) String name,
		@NotNull RouteType routeType,
		@NotBlank @Size(max = 32) String routeVersion,
		@Size(max = 1000) String modelImageUrl,
		@Size(max = 500) String specification,
		@Size(max = 160) String material
	) {
	}

	record ModelImageRequest(@Size(max = 1000) String modelImageUrl) { }

	record UpdateProductRequest(
		@NotBlank @Size(max = 160) String name,
		@NotNull RouteType routeType,
		@NotBlank @Size(max = 32) String routeVersion,
		@Size(max = 500) String specification,
		@Size(max = 160) String material
	) { }
}
