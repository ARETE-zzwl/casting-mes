package com.renyi.mes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.resource.MoldApplication;
import com.renyi.mes.resource.ResourceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class MoldLocationConcurrencyTests {

	@Autowired MoldApplication molds;
	@Autowired ResourceApplication resources;
	@Autowired JdbcTemplate jdbc;
	@Autowired PlatformTransactionManager transactions;

	@Test
	void manualReceiptWaitsForCommitAndCannotOverfillLocation() throws Exception {
		String location = location(1);
		String outcome = duringUncommittedReceipt(location, () -> outcome(() -> receive(location)));
		assertThat(outcome).isEqualTo("MOLD_LOCATION_OCCUPIED");
		assertThat(occupancy(location)).isEqualTo(1);
	}

	@Test
	void automaticReceiptRechecksOccupancyAfterWaitingAndChoosesAnotherSlot() throws Exception {
		String first = location(1);
		String second = first + "-NEXT";
		molds.createStorageLocation(new MoldApplication.CreateStorageLocationCommand(second, second, 1, "M001"));
		String allocated = duringUncommittedReceipt(first, () -> receive(null).locationCode());
		assertThat(allocated).isEqualTo(second);
		assertThat(occupancy(first)).isEqualTo(1);
		assertThat(occupancy(second)).isEqualTo(1);
	}

	@Test
	void capacityChangesWaitForReceiptsAndCannotShrinkBelowCommittedOccupancy() throws Exception {
		String location = location(2);
		receive(location);
		String outcome = duringUncommittedReceipt(location, () -> outcome(() -> molds.updateStorageLocation(location,
			new MoldApplication.UpdateStorageLocationCommand(location, 1, true, "M001"))));
		assertThat(outcome).isEqualTo("MOLD_LOCATION_CAPACITY_TOO_SMALL");
		assertThat(occupancy(location)).isEqualTo(2);
	}

	@Test
	void disablingLocationWaitsForPendingReceipt() throws Exception {
		String location = location(1);
		String outcome = duringUncommittedReceipt(location, () -> outcome(() -> molds.updateStorageLocation(location,
			new MoldApplication.UpdateStorageLocationCommand(location, 1, false, "M001"))));
		assertThat(outcome).isEqualTo("MOLD_LOCATION_IN_USE");
	}

	@Test
	void genericRegistrationAndReceiptUseTheSameTransactionLock() throws Exception {
		String location = location(1);
		String outcome = duringUncommittedReceipt(location, () -> outcome(() -> register(location)));
		assertThat(outcome).isEqualTo("MOLD_LOCATION_OCCUPIED");
		assertThat(occupancy(location)).isEqualTo(1);
	}

	@Test
	void genericResourceRegistrationCannotBypassLocationChecks() throws Exception {
		String location = location(1);
		receive(location);
		assertThat(outcome(() -> register(location.toLowerCase(Locale.ROOT)))).isEqualTo("MOLD_LOCATION_OCCUPIED");
		String inactive = location(1);
		molds.updateStorageLocation(inactive, new MoldApplication.UpdateStorageLocationCommand(inactive, 1, false, "M001"));
		assertThat(outcome(() -> register(inactive))).isEqualTo("MOLD_LOCATION_INACTIVE");
		assertThat(outcome(() -> register("UNCONFIGURED-" + UUID.randomUUID()))).isEqualTo("MOLD_LOCATION_NOT_FOUND");
	}

	@Test
	void failedReceiptRollsBackSlotAndLabelBindingTogether() {
		String location = location(1);
		assertThatThrownBy(() -> molds.receive(new MoldApplication.ReceiveCommand(
			"MES:ASSET_QR:" + UUID.randomUUID(), null, "Rollback mold", location, null,
			"COMPANY_OWNED", null, "M001", null))).isInstanceOf(DomainException.class);
		assertThat(occupancy(location)).isZero();
		assertThat(receive(location).locationCode()).isEqualTo(location);
	}

	private String duringUncommittedReceipt(String location, Callable<String> secondAction) throws Exception {
		CountDownLatch staged = new CountDownLatch(1);
		CountDownLatch commit = new CountDownLatch(1);
		CountDownLatch secondStarted = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var first = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
				receive(location);
				staged.countDown();
				await(commit);
				return null;
			}));
			try {
				assertThat(staged.await(5, TimeUnit.SECONDS)).isTrue();
				var second = pool.submit(() -> {
					secondStarted.countDown();
					return secondAction.call();
				});
				assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
				try {
					assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
				} finally {
					commit.countDown();
				}
				first.get(5, TimeUnit.SECONDS);
				return second.get(5, TimeUnit.SECONDS);
			} finally {
				commit.countDown();
			}
		}
	}

	private String location(int capacity) {
		String code = "000-LOCK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
		molds.createStorageLocation(new MoldApplication.CreateStorageLocationCommand(code, code, capacity, "M001"));
		return code;
	}

	private ResourceApplication.AssetView receive(String location) {
		return molds.receive(new MoldApplication.ReceiveCommand(null, null, "Concurrent receipt", location, null,
			"COMPANY_OWNED", null, "M001", null));
	}

	private ResourceApplication.AssetView register(String location) {
		return resources.register(new ResourceApplication.RegisterCommand("LOCK-" + UUID.randomUUID(), "Asset entry",
			"MOLD", location, null, "COMPANY_OWNED", null));
	}

	private int occupancy(String location) {
		return jdbc.queryForObject("select count(*) from resource_asset where asset_type = 'MOLD' and location_code = ?", Integer.class, location);
	}

	private static String outcome(Callable<?> action) throws Exception {
		try { action.call(); return "ACCEPTED"; }
		catch (DomainException exception) { return exception.code(); }
	}

	private static void await(CountDownLatch latch) {
		try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting to commit"); }
		catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
	}
}
