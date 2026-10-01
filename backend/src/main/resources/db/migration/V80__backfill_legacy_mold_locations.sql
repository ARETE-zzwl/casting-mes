-- Historical simulation molds predate slot-capacity management and were all stored under MOLD-01.
-- Spread them across the configured one-set slots so a displayed location always identifies a physical slot.
update resource_asset target
set location_code = (
    select case
        when ranked.slot_no <= 8 then 'MOLD-A-' || lpad(cast(ranked.slot_no as varchar), 2, '0')
        when ranked.slot_no <= 16 then 'MOLD-B-' || lpad(cast(ranked.slot_no - 8 as varchar), 2, '0')
        when ranked.slot_no <= 24 then 'MOLD-C-' || lpad(cast(ranked.slot_no - 16 as varchar), 2, '0')
        else 'MOLD-D-' || lpad(cast(ranked.slot_no - 24 as varchar), 2, '0')
    end
    from (
        select id, row_number() over (order by asset_code) as slot_no
        from resource_asset
        where asset_type = 'MOLD' and location_code = 'MOLD-01' and asset_code like 'SIM-MOLD-%'
    ) ranked
    where ranked.id = target.id
)
where target.asset_type = 'MOLD' and target.location_code = 'MOLD-01' and target.asset_code like 'SIM-MOLD-%';
