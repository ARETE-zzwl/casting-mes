package com.renyi.mes.resource.internal;

import java.util.Locale;

import com.renyi.mes.common.DomainException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class MoldStorageLocations {

	private final JdbcTemplate jdbc;

	public MoldStorageLocations(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public String allocate(String requested) {
		if (requested != null && !requested.isBlank()) return requireAvailable(requested);
		var candidates = jdbc.queryForList("""
			select l.location_code from mold_storage_location l
			where l.active = true and (select count(*) from resource_asset a
				where a.asset_type = 'MOLD' and a.location_code = l.location_code) < l.capacity
			order by l.location_code
			""", String.class);
		// Lock in code order and re-read occupancy after waiting for another receipt to commit.
		for (String code : candidates) {
			Location location = lock(code);
			if (location.active() && occupancy(code) < location.capacity()) return code;
		}
		throw DomainException.conflict("MOLD_LOCATION_FULL", "模具库暂无空库位，请由仓管释放或新增库位后再入库");
	}

	public String requireAvailable(String requested) {
		String code = requested.trim().toUpperCase(Locale.ROOT);
		Location location = lock(code);
		if (!location.active()) throw DomainException.conflict("MOLD_LOCATION_INACTIVE", "所选模具库位已停用");
		if (occupancy(code) >= location.capacity()) {
			throw DomainException.conflict("MOLD_LOCATION_OCCUPIED", "所选模具库位已被占用，请选择其他空库位");
		}
		return code;
	}

	public Location lock(String code) {
		return jdbc.query("select capacity, active from mold_storage_location where location_code = ? for update",
			(rs, row) -> new Location(rs.getInt("capacity"), rs.getBoolean("active")), code).stream().findFirst()
			.orElseThrow(() -> DomainException.notFound("MOLD_LOCATION_NOT_FOUND", "模具库位不存在"));
	}

	public int occupancy(String code) {
		return jdbc.queryForObject("select count(*) from resource_asset where asset_type = 'MOLD' and location_code = ?",
			Integer.class, code);
	}

	public record Location(int capacity, boolean active) { }
}
