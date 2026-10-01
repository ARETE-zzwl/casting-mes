package com.renyi.mes.engineering.internal;

import java.time.Instant;
import java.util.UUID;

import com.renyi.mes.engineering.RouteType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "engineering_product")
public class ProductEntity {

	@Id
	private UUID id;
	private String code;
	private String name;
	@Enumerated(EnumType.STRING)
	private RouteType routeType;
	private String routeVersion;
	private String modelImageUrl;
	private String specification;
	private String material;
	private UUID materialVariantOf;
	private boolean active;
	private Instant createdAt;

	protected ProductEntity() {
	}

	public ProductEntity(
		UUID id,
		String code,
		String name,
		RouteType routeType,
		String routeVersion,
		String modelImageUrl,
		String specification,
		String material,
		UUID materialVariantOf,
		boolean active,
		Instant createdAt
	) {
		this.id = id;
		this.code = code;
		this.name = name;
		this.routeType = routeType;
		this.routeVersion = routeVersion;
		this.modelImageUrl = modelImageUrl;
		this.specification = specification;
		this.material = material;
		this.materialVariantOf = materialVariantOf;
		this.active = active;
		this.createdAt = createdAt;
	}

	public UUID id() {
		return id;
	}

	public String code() {
		return code;
	}

	public String name() {
		return name;
	}

	public RouteType routeType() {
		return routeType;
	}

	public String routeVersion() {
		return routeVersion;
	}

	public String modelImageUrl() {
		return modelImageUrl;
	}

	public void setModelImageUrl(String value) { modelImageUrl = value; }

	public void updateDetails(String name, RouteType routeType, String routeVersion, String specification, String material) {
		this.name = name;
		this.routeType = routeType;
		this.routeVersion = routeVersion;
		this.specification = specification;
		this.material = material;
	}

	public String specification() { return specification; }
	public String material() { return material; }
	public UUID materialVariantOf() { return materialVariantOf; }

	public boolean active() {
		return active;
	}

	public Instant createdAt() {
		return createdAt;
	}
}
