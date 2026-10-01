-- Existing batches without any execution fact become pending launch under the explicit-launch workflow.
update planning_task
set status = 'BLOCKED'
where sequence_no = 1
  and status = 'READY'
  and batch_id in (
    select b.id
    from planning_batch b
    where b.status = 'READY'
      and not exists (
        select 1
        from planning_task t
        where t.batch_id = b.id
          and t.status in ('ASSIGNED', 'IN_PROGRESS', 'COMPLETED')
      )
  );

update planning_batch
set status = 'PENDING_LAUNCH'
where status = 'READY'
  and not exists (
    select 1
    from planning_task t
    where t.batch_id = planning_batch.id
      and t.status in ('ASSIGNED', 'IN_PROGRESS', 'COMPLETED')
  );
