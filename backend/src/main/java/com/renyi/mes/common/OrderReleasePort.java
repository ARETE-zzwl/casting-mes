package com.renyi.mes.common;

import java.util.UUID;

/**
 * Creates planning work orders after an order has passed its required reviews.
 */
public interface OrderReleasePort {

	void release(UUID orderId);
}
