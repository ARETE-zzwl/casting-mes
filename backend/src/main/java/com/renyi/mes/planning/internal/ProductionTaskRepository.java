package com.renyi.mes.planning.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.renyi.mes.planning.TaskStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductionTaskRepository extends JpaRepository<ProductionTaskEntity, UUID> {

	List<ProductionTaskEntity> findAllByOrderByCreatedAtDescBatchIdAscSequenceNoAsc();

	List<ProductionTaskEntity> findByBatchIdOrderBySequenceNo(UUID batchId);

	Optional<ProductionTaskEntity> findFirstByTaskNoIgnoreCase(String taskNo);

	long countByBatchId(UUID batchId);

	long countByBatchIdAndStatus(UUID batchId, TaskStatus status);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select task from ProductionTaskEntity task where task.id = :taskId")
	Optional<ProductionTaskEntity> findLockedById(@Param("taskId") UUID taskId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select task from ProductionTaskEntity task
		where task.batchId = :batchId and task.sequenceNo = :sequenceNo
		""")
	Optional<ProductionTaskEntity> findLockedByBatchAndSequence(
		@Param("batchId") UUID batchId,
		@Param("sequenceNo") int sequenceNo
	);
}
