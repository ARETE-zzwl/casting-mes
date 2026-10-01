package com.renyi.mes.customerorder.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "customer_order_customer")
public class CustomerEntity {

	@Id
	private UUID id;
	private String code;
	private String name;
	private String contactName;
	private String contactPhone;
	private String salesOwner;
	private boolean active;
	private Instant createdAt;

	protected CustomerEntity() {
	}

	public CustomerEntity(
		UUID id,
		String code,
		String name,
		String contactName,
		String contactPhone,
		String salesOwner,
		boolean active,
		Instant createdAt
	) {
		this.id = id;
		this.code = code;
		this.name = name;
		this.contactName = contactName;
		this.contactPhone = contactPhone;
		this.salesOwner = salesOwner;
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

	public String contactName() {
		return contactName;
	}

	public String contactPhone() {
		return contactPhone;
	}

	public String salesOwner() {
		return salesOwner;
	}

	public boolean active() {
		return active;
	}

	public Instant createdAt() {
		return createdAt;
	}
}
