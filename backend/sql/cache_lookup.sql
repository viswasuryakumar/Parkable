-- Cache lookup for GET /check and GET /nearby. Bind parser_version to the
-- running extractor version: D5 deliberately rejects cache entries produced
-- by an older parser. PostGIS points are longitude first, then latitude.
SELECT id, rule, source, parser_version, created_at
FROM rules
WHERE parser_version = :parser_version
  AND ST_DWithin(
      location,
      ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
      :radius_meters
  )
ORDER BY ST_Distance(
    location,
    ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography
);
