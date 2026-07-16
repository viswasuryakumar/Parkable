# Parkable Parking Rule Schema

**Version**: 1.0  
**Last Updated**: 2026-07-15  
**Status**: Unified schema for multi-city government + camera-scan extraction

This schema normalizes parking regulation data from diverse US government sources (NYC, SF, LA, Chicago, Seattle, etc.) and LLM-extracted camera scans into a single, deterministic representation. It is the contract between data ingestion, the rules engine, and verdict output.

---

## Design Philosophy

1. **Structured over free-text**: Where possible, use categorical fields; preserve original_description for fallback
2. **Unify blockface & zone geometries**: Support both linear (blockface) and polygon (zone) representations
3. **ISO-8601 timestamps & times**: Normalize all times to 24h format; timezone explicit
4. **Permit-as-first-class**: Permit requirements separated from time-based rules
5. **Reproducibility**: Every rule carries source + version + ingestion_date
6. **Multi-source normalization**: Same schema for NYC free-text, SF categorical, LA real-time, Chicago ordinances, camera scans

---

## Top-Level: ExtractionEnvelope

This is what an ingestion pipeline (gov ETL or LLM extraction) produces.

```jsonc
{
  // Extraction Metadata
  "extraction_id": "uuid",                                    // unique identifier for this batch
  "source": "gov_data | camera_scan | api_sync",             // origin of rules
  "city": "San Francisco",                                    // city or region name
  "state": "CA",                                              // US state abbreviation
  "source_dataset_url": "https://data.sfgov.org/...",        // gov data source (if applicable)
  "source_version": "sf_parking_v2.3",                        // source dataset version or git commit
  "parser_version": "claude-vision-extractor-v1",            // parser/extractor version (for reproducibility)
  "ingestion_timestamp": "2026-07-15T18:04:00Z",             // when this extraction ran
  "extraction_method": "llm | structured_parser | manual",    // how extracted

  // Geographic Coverage
  "geographic_scope": {
    "type": "polygon | blockface | address_range",           // coverage unit type
    "bounding_box": {
      "north": 37.8,
      "south": 37.7,
      "east": -122.3,
      "west": -122.5
    },
    "description": "San Francisco Downtown area"               // human-readable scope
  },

  // Quality Metadata
  "confidence": 0.92,                                         // overall extraction confidence (0-1)
  "coverage_completeness": 0.87,                             // % of known rules captured
  "notes": "Data source flagged as incomplete; seasonal rules not available",
  "raw_text": "string",                                        // OCR dump (camera scans only) or original dataset summary

  // The Rules
  "rules": [
    // ... (see below)
  ]
}
```

---

## Rule Object (Per-Regulation Unit)

One rule object = one distinct parking regulation (could be time-based, permit-based, street-cleaning, etc.). A single blockface/zone might have multiple rules.

```jsonc
{
  // Identification
  "rule_id": "unique_within_extraction",                      // UUID or government ID
  "source_rule_id": "SFMTA_12345 | NYC_SIMS_54321",          // original government identifier (if applicable)
  "type": "time_limit | no_parking | permit_required | restricted | street_cleaning | color_curb",

  // Human-Readable Reference
  "description": "2 Hour Parking Mon-Fri 8am-6pm",           // human text as it appears on sign/ordinance
  "original_description": "The exact text from source data",  // preserve for fallback/debugging

  // Restriction Details (Structure Varies by Type)
  "restriction": {
    "duration_minutes": 120,                                  // time limit in minutes (time_limit type)
    "permit_type": "residential | guest | business | other", // permit category (permit_required type)
    "color": "red | blue | green | white | yellow",          // curb color (color_curb type)
    "curb_use": "no_stopping | no_parking | loading | disabled_accessible",  // specific use prohibition
    "extra_details": "Oversized vehicles 2 tons+ prohibited"  // any other restriction notes
  },

  // When it Applies: Time-Based Rules
  "time_windows": [
    {
      "start_time": "08:00",                                  // 24h format (HH:mm)
      "end_time": "18:00",                                    // numerically >= start, or < start if crosses midnight
      "crosses_midnight": false,                              // explicit flag (start=22:00, end=06:00)
      "all_day": false                                        // true = ignore start_time & end_time (ANY TIME)
    }
    // May have multiple windows per rule (rare but possible: e.g., "8am-noon and 2pm-6pm")
  ],

  // When it Applies: Day-Based Rules
  "day_pattern": {
    "type": "specific_days | nth_weekday_of_month | any_day | date_range",
    
    // Type: specific_days
    "days_of_week": ["MON", "TUE", "WED", "THU", "FRI"],     // or "ANY" for all days
    
    // Type: nth_weekday_of_month (for street cleaning, etc.)
    "weekday": "TUE",                                         // target day of week
    "occurrences": [1, 3],                                    // which weeks: 1-4, or [5/-1] for "last"
    
    // Type: date_range (for seasonal rules)
    "effective_date": "2026-01-01",                           // when rule starts (ISO 8601)
    "sunset_date": "2026-12-31",                              // when rule expires
    "year_round": false                                       // true = ignore effective/sunset dates
  },

  // Exceptions & Special Cases
  "exceptions": [
    {
      "exception_type": "holiday_suspension | special_event | snow_emergency | permit_holder",
      "description": "Suspended on all federal holidays",
      "applies_to": ["THANKSGIVING", "CHRISTMAS"],            // which holidays (if holiday_suspension)
      "exception_rule": "rule not active",                    // behavior when exception applies
      "date_override": "2026-07-04"                           // specific date (if applicable)
    }
  ],

  // Directional / Side-of-Street
  "direction": {
    "side_of_street": "left | right | both | not_specified", // which curb side
    "cardinal": "north | south | east | west | not_specified", // or compass direction
    "house_numbers": "odd | even | not_applicable",          // for alternate-side parking (NYC)
    "arrow": "north | south | east | west | not_applicable"  // visual arrow on sign
  },

  // Geographic Reference
  "location": {
    "type": "blockface | zone | point | address",
    
    // Type: blockface (linear)
    "street": "Market Street",
    "from_intersection": "5th Street",
    "to_intersection": "6th Street",
    "blockface_id": "SFMTA_BLOCK_987654",                     // government blockface identifier
    "geometry": {
      "type": "LineString",
      "coordinates": [[-122.4, 37.78], [-122.39, 37.78]]     // GeoJSON line segment
    },
    
    // Type: zone (polygon)
    "zone_name": "Downtown Permit Zone A",
    "zone_id": "CHI_PERMIT_ZONE_A",
    "geometry": {
      "type": "Polygon",
      "coordinates": [[[-122.4, 37.78], [-122.39, 37.78], [-122.39, 37.79], [-122.4, 37.79], [-122.4, 37.78]]]
    },
    
    // Type: point (camera scan or individual meter)
    "latitude": 37.7749,
    "longitude": -122.4194,
    "address": "123 Market Street, SF, CA 94102",
    "meter_id": "LADOT_METER_12345"                           // if meter-specific (LA)
  },

  // Fees & Rates (if applicable)
  "rates": [
    {
      "rate_cents_per_hour": 500,                             // cents (e.g., $5.00/hour = 500)
      "rate_effective_date": "2026-01-01",
      "payment_method": "meter | app | permit",
      "currency": "USD"
    }
  ],

  // Metadata & Provenance
  "metadata": {
    "government_source": "SFMTA | NYC DOT | LADOT | etc.",    // agency that published data
    "ingested_from_dataset": "Map of Parking Regulations v2.3",
    "last_verified": "2026-07-15",                            // when this rule was last confirmed correct
    "confidence_score": 0.95,                                 // 0-1 confidence in this specific rule
    "tag": "gov_data | camera_scan",                          // how it was sourced
    "parser_version_used": "claude-vision-extractor-v1"       // parser version (for reproducibility)
  },

  // Deprecated/Sunset Info
  "status": "active | deprecated | superseded",
  "deprecation_reason": "Ordinance repealed 2026-06-01",
  "replacement_rule_id": "next_rule_id_if_superseded"
}
```

---

## Normalization Rules: Multi-City Mapping

### NYC (440,000+ Free-Text Signs) → Unified Schema

**Challenge**: Regulations stored as free-text sign descriptions.  
**Example NYC Sign**: `"No Parking Mon & Wed 8am-6pm, Except Holidays. Street Cleaning."`

**Normalization Strategy**:
1. Use NLP / regex to parse free-text into structured fields (done offline, cached)
2. Create separate rule object per distinct restriction
3. Store original_description for fallback

**Mapping**:
```
NYC Field                    → Unified Schema
─────────────────────────────────────────────
SIDE_OF_STREET (E/W/N/S)    → direction.cardinal
DAYS_PARKING_IN_EFFECT      → day_pattern.days_of_week
FROM_HOURS_IN_EFFECT        → time_windows[0].start_time
TO_HOURS_IN_EFFECT          → time_windows[0].end_time
SIGN_DESCRIPTION            → description + original_description
GEOMETRY (LineString)       → location.geometry (blockface)
SIGNID                      → source_rule_id
```

**Exception Handling**:
- Holiday mention in text → exceptions[].exception_type = "holiday_suspension"
- No explicit end-date → use full year or assume current ordinance date

---

### San Francisco (Structured Categorical) → Unified Schema

**Advantage**: Already somewhat normalized (SFMTA dataset).  
**Example**: Multiple rows, one per blockface/regulation combo.

**Mapping**:
```
SFMTA Field                 → Unified Schema
─────────────────────────────────────────────
BLOCKFACE_ID                → source_rule_id + location.blockface_id
REGULATION_TYPE             → type (no_parking, time_limit, permit_required)
TIME_LIMIT_CATEGORY         → restriction.duration_minutes
PERMIT_TYPE                 → restriction.permit_type
WEEKEND_RULES               → separate rule object with weekend day_pattern
HOLIDAY_RULES flag          → exceptions[].exception_type = "holiday_suspension"
GEOMETRY (LineString)       → location.geometry
EFFECTIVE_DATE              → day_pattern.effective_date
```

**Challenge**: Dataset noted as "not fully verified"—confidence_score < 1.0.

---

### Los Angeles (Real-Time Meter Data + Separate Datasets) → Unified Schema

**Challenge**: Parking regulations, meter rates, occupancy, and color curbs in separate datasets.  
**Complexity**: 300-600MB monthly transaction archives.

**Mapping Strategy**:
1. Normalize LADOT metered parking inventory (primary)
2. Join with LA City preferential parking (permit zones)
3. Sync with color curb data separately
4. Occupancy data as separate table (not in rules schema, but linked via meter_id)

```
LADOT Field                 → Unified Schema
─────────────────────────────────────────────
METER_ID                    → location.meter_id + source_rule_id
BLOCK_FACE                  → location.blockface_id
TIME_LIMIT (hours)          → restriction.duration_minutes
RATE_HOURLY                 → rates[].rate_cents_per_hour
ENFORCED_PARKING_RESTRICTIONS → type + original_description
METER_OPERATING_HOURS       → time_windows[0].start_time / end_time
LATITUDE / LONGITUDE        → location.geometry (point)
```

**Color Curb Sync**:
- Separate ingestion pipeline
- Joined via spatial intersection (curb location)
- Produces rule objects with type = "color_curb"

---

### Chicago (Zone Polygons + Permit System) → Unified Schema

**Difference**: Regulates zones (polygons), not individual blockfaces.  
**Complexity**: "Buffer" zones (implied by ordinance, no physical signs) vs. "Standard" (posted signs).

**Mapping**:
```
Chicago Field               → Unified Schema
─────────────────────────────────────────────
ZONE_ID                     → location.zone_id + source_rule_id
ZONE_NAME                   → location.zone_name
ZONE_TYPE (Standard/Buffer) → metadata.tag ("gov_data", confidence adjusted for Buffer)
PERMIT_TYPE                 → restriction.permit_type
HOURS_OF_ENFORCEMENT        → time_windows[0]
DAYS_OF_WEEK_ENFORCED       → day_pattern.days_of_week
GEOMETRY (Polygon)          → location.geometry (zone)
```

**Handling Buffer Zones**:
- Zones without posted signs get lower confidence_score
- tag = "gov_data" but confidence < 1.0 (rules exist by ordinance, not sign)

---

### Seattle (Well-Documented Blockface) → Unified Schema

**Strength**: Clear blockface geometry + structured fields.  
**Data**: Blockface segments + real-time paid parking transactions.

**Mapping**:
```
Seattle Field               → Unified Schema
─────────────────────────────────────────────
BLOCK_ID                    → location.blockface_id + source_rule_id
PAID_PARKING_SEGMENT        → location (blockface linestring)
RATE                        → rates[].rate_cents_per_hour
TIME_LIMIT                  → restriction.duration_minutes
PAYMENT_ZONE                → location.zone_name
GEOMETRY                    → location.geometry (blockface)
OCCUPANCY_STATUS            → (separate occupancy table, not in rules schema)
```

**Note**: Occupancy data stored separately; linked via blockface_id in Phase 2 queries.

---

## Camera-Scan LLM Extraction → Unified Schema

When Claude Vision (or other LLM) extracts from a parking sign photo:

**LLM Instruction Prompt**:
> Analyze this parking sign photo. Extract all distinct parking regulations. For each rule:
> - What is the sign type? (no parking, time limit, permit required, street cleaning, etc.)
> - What are the time windows (if any)? Format as HH:mm in 24h.
> - What are the days it applies? (Mon, Tue, Wed, etc.)
> - Are there any exceptions? (holidays, special permits, etc.)
> - What is the exact text on the sign?
> 
> Return ONLY a JSON object conforming to [docs/schema.md ExtractionEnvelope](link).

**Extraction Metadata**:
- `source` = "camera_scan"
- `parser_version` = "claude-vision-extractor-v1" (version-controlled)
- `ingestion_timestamp` = actual extraction time (for reproducibility)
- `confidence` = model's confidence score (0-1)
- `original_description` = OCR dump of sign text

**Example Extracted Sign**:
```jsonc
{
  "extraction_id": "uuid-...",
  "source": "camera_scan",
  "city": "San Francisco",
  "parser_version": "claude-vision-extractor-v1",
  "ingestion_timestamp": "2026-07-15T18:04:00Z",
  "confidence": 0.94,
  "rules": [
    {
      "rule_id": "extracted-12345",
      "type": "no_parking",
      "description": "No Parking Mon-Fri 4-6 PM",
      "time_windows": [
        {"start_time": "16:00", "end_time": "18:00", "crosses_midnight": false}
      ],
      "day_pattern": {
        "type": "specific_days",
        "days_of_week": ["MON", "TUE", "WED", "THU", "FRI"]
      },
      "direction": {"side_of_street": "both"},
      "location": {
        "type": "point",
        "latitude": 37.7749,
        "longitude": -122.4194,
        "address": "Market & 5th, SF"
      },
      "metadata": {
        "tag": "camera_scan",
        "parser_version_used": "claude-vision-extractor-v1",
        "confidence_score": 0.94
      }
    }
  ]
}
```

---

## Validation Rules (What Makes a Valid Rule)

```
1. rule_id: required, non-empty string
2. type: required, one of {time_limit, no_parking, permit_required, restricted, street_cleaning, color_curb}
3. description: required if type != color_curb, non-empty string
4. restriction: required; contents depend on type
   - type=time_limit: duration_minutes must be > 0
   - type=permit_required: permit_type must be non-empty
   - type=color_curb: color must be one of {red, blue, green, white, yellow}
5. time_windows: 
   - If empty, implies "ANY TIME"
   - Each window.start_time < window.end_time UNLESS crosses_midnight=true
   - Format: HH:mm (00:00-23:59)
6. day_pattern: required; must have valid type + corresponding fields
   - type=specific_days: days_of_week must be non-empty
   - type=nth_weekday_of_month: weekday + occurrences required; occurrences in {1,2,3,4,5,-1}
   - type=date_range: effective_date required; sunset_date optional
7. location: required; must have type + corresponding geometry
   - type=blockface: street, from_intersection, to_intersection, geometry.coordinates non-empty
   - type=zone: zone_name, geometry.coordinates (closed polygon) non-empty
   - type=point: latitude, longitude required
8. metadata.tag: required; one of {gov_data, camera_scan}
9. No rule can have BOTH rule-based conflicts:
   - e.g., type=no_parking with time_windows.start_time >= end_time (unless crosses_midnight)
10. effective_date <= sunset_date (if both present)
```

**Validation failure handling**:
- Camera-scan extraction: validation failure → retry once → if still fails, return NeedsReview with honest message
- Gov ETL: validation failure → log issue, set confidence_score to indicate quality concern, ingest anyway (gov data isn't expected to be 100% clean)

---

## Schema Evolution & Versioning

- **Current Version**: 1.0
- **Backward Compatibility**: Any future schema changes must not break existing rule parsing (additive only for Phase 1)
- **Change Log**: Keep `docs/schema_changelog.md` documenting each version's changes

---

## Examples

### Example 1: NYC Free-Text Sign (Normalized)

**Original Sign Text**: `"No Parking Mon & Wed 8am-6pm, Except Holidays. Street Cleaning."`

**Normalized to Two Rule Objects**:

```jsonc
// Rule 1: No Parking Time-Limited
{
  "rule_id": "nyc-sims-123456",
  "type": "no_parking",
  "description": "No Parking Mon & Wed 8am-6pm",
  "original_description": "No Parking Mon & Wed 8am-6pm, Except Holidays. Street Cleaning.",
  "time_windows": [
    {"start_time": "08:00", "end_time": "18:00"}
  ],
  "day_pattern": {
    "type": "specific_days",
    "days_of_week": ["MON", "WED"]
  },
  "exceptions": [
    {"exception_type": "holiday_suspension", "description": "Suspended on federal holidays"}
  ],
  "location": {
    "type": "blockface",
    "street": "Market Street",
    "from_intersection": "5th Street",
    "to_intersection": "6th Street",
    "geometry": {"type": "LineString", "coordinates": [[-122.4, 37.78], [-122.39, 37.78]]}
  },
  "metadata": {
    "government_source": "NYC DOT",
    "tag": "gov_data",
    "confidence_score": 0.88
  }
}

// Rule 2: Street Cleaning
{
  "rule_id": "nyc-sims-123457",
  "type": "street_cleaning",
  "description": "Street Cleaning Mon & Wed",
  "restriction": {
    "duration_minutes": 0  // "no parking" during street cleaning
  },
  "day_pattern": {
    "type": "specific_days",
    "days_of_week": ["MON", "WED"]
  },
  "location": {
    "type": "blockface",
    "street": "Market Street",
    "from_intersection": "5th Street",
    "to_intersection": "6th Street",
    "geometry": {"type": "LineString", "coordinates": [[-122.4, 37.78], [-122.39, 37.78]]}
  },
  "metadata": {
    "government_source": "NYC DOT",
    "tag": "gov_data",
    "confidence_score": 0.85
  }
}
```

---

### Example 2: SF Categorical (Well-Structured)

**Source**: SFMTA Parking Regulations dataset

```jsonc
{
  "rule_id": "sf-sfmta-67890",
  "source_rule_id": "SFMTA_BF_54321",
  "type": "permit_required",
  "description": "Residential Permit Parking 24 hours",
  "restriction": {
    "permit_type": "residential"
  },
  "day_pattern": {
    "type": "any_day"
  },
  "time_windows": [
    {"all_day": true}  // Any time
  ],
  "location": {
    "type": "blockface",
    "street": "Valencia Street",
    "from_intersection": "14th Street",
    "to_intersection": "15th Street",
    "blockface_id": "SFMTA_BF_54321",
    "geometry": {"type": "LineString", "coordinates": [[-122.42, 37.76], [-122.42, 37.765]]}
  },
  "metadata": {
    "government_source": "SFMTA",
    "ingested_from_dataset": "Map of Parking Regulations v2.3",
    "tag": "gov_data",
    "confidence_score": 0.92,
    "last_verified": "2026-07-15"
  }
}
```

---

### Example 3: LA Metered Parking with Rate

```jsonc
{
  "rule_id": "la-ladot-111222",
  "source_rule_id": "LADOT_METER_998877",
  "type": "time_limit",
  "description": "2 Hour Metered Parking",
  "restriction": {
    "duration_minutes": 120
  },
  "time_windows": [
    {"start_time": "08:00", "end_time": "18:00"}
  ],
  "day_pattern": {
    "type": "specific_days",
    "days_of_week": ["MON", "TUE", "WED", "THU", "FRI"]
  },
  "rates": [
    {
      "rate_cents_per_hour": 600,  // $6.00/hour
      "rate_effective_date": "2026-01-01",
      "payment_method": "meter | app"
    }
  ],
  "location": {
    "type": "point",
    "meter_id": "LADOT_METER_998877",
    "latitude": 34.0522,
    "longitude": -118.2437,
    "address": "Downtown LA"
  },
  "metadata": {
    "government_source": "LADOT",
    "ingested_from_dataset": "LADOT Metered Parking Inventory",
    "tag": "gov_data",
    "confidence_score": 1.0
  }
}
```

---

### Example 4: Chicago Zone-Based Permit Parking

```jsonc
{
  "rule_id": "chi-permit-zone-aaa",
  "source_rule_id": "CHI_ZONE_AAAAAA",
  "type": "permit_required",
  "description": "Residential Permit Parking Zone A",
  "restriction": {
    "permit_type": "residential"
  },
  "time_windows": [
    {"start_time": "08:00", "end_time": "18:00"}
  ],
  "day_pattern": {
    "type": "specific_days",
    "days_of_week": ["MON", "TUE", "WED", "THU", "FRI"]
  },
  "location": {
    "type": "zone",
    "zone_name": "Downtown Residential Permit Zone A",
    "zone_id": "CHI_ZONE_AAAAAA",
    "geometry": {
      "type": "Polygon",
      "coordinates": [[[-87.64, 41.88], [-87.63, 41.88], [-87.63, 41.87], [-87.64, 41.87], [-87.64, 41.88]]]
    }
  },
  "metadata": {
    "government_source": "Chicago Department of Transportation",
    "tag": "gov_data",
    "confidence_score": 0.90,
    "notes": "Zone boundary from municipal ordinance; not all permits signed at every blockface"
  }
}
```

---

### Example 5: Camera Scan (LLM-Extracted)

```jsonc
{
  "extraction_id": "cam-extract-uuid-12345",
  "source": "camera_scan",
  "city": "San Francisco",
  "state": "CA",
  "parser_version": "claude-vision-extractor-v1",
  "ingestion_timestamp": "2026-07-15T18:04:23Z",
  "confidence": 0.96,
  "raw_text": "NO PARKING ANY TIME\nSF MUNICIPAL CODE\nSection 42-A",
  "rules": [
    {
      "rule_id": "cam-extract-uuid-12345-r1",
      "type": "no_parking",
      "description": "No Parking Any Time",
      "original_description": "NO PARKING ANY TIME\nSF MUNICIPAL CODE\nSection 42-A",
      "day_pattern": {
        "type": "any_day"
      },
      "time_windows": [
        {"all_day": true}
      ],
      "location": {
        "type": "point",
        "latitude": 37.7749,
        "longitude": -122.4194,
        "address": "Market Street near 5th, SF"
      },
      "metadata": {
        "tag": "camera_scan",
        "parser_version_used": "claude-vision-extractor-v1",
        "confidence_score": 0.96
      }
    }
  ]
}
```

---

## Notes for Implementers

1. **Blockface vs. Zone**: A single geographic area might be covered by both blockface rules (from signage) and zone rules (from ordinance). Both are valid; the engine evaluates all relevant rules and applies most-restrictive-wins.

2. **Midnight-Crossing**: Always set `crosses_midnight: true` if `end_time` is numerically less than `start_time` (e.g., 22:00-06:00).

3. **Free-Text Fallback**: If a rule's `original_description` exists and parsing failed, log it for manual review rather than silently guessing.

4. **Confidence Scores**:
   - Gov data: typically 0.85-1.0 (depending on data quality, recency, verification)
   - Camera scans: typically 0.80-0.99 (depends on image clarity, sign legibility, LLM confidence)
   - NYC free-text parsed: typically 0.75-0.90 (due to ambiguous phrasing)

5. **Reproducibility**: Always store `parser_version` and `ingestion_timestamp` so old extractions can be reprocessed when the parser improves.

6. **Versioning**: This schema is version 1.0. Any breaking changes require a new major version; Phase 1 will only add fields, not remove or restructure existing ones.

---

## References

- NYC DOT: [Parking Regulation Locations and Signs](https://data.cityofnewyork.us/Transportation/Parking-Regulation-Locations-and-Signs/nfid-uabd)
- SF DataSF: [Map of Parking Regulations](https://data.sfgov.org/Transportation/Map-of-Parking-Regulations/qbyz-te2i)
- LADOT: [Metered Parking Inventory](https://data.lacity.org/Transportation/LADOT-Metered-Parking-Inventory-Policies/s49e-q6j2)
- Chicago: [Permit Parking Zones](https://data.cityofchicago.org/Transportation/Permit-Parking-Zones/qiag-khha)
- CurbLR Spec: [SharedStreets/CurbLR](https://github.com/curblr/curblr-spec)
- Curb Data Spec: [OpenMobility Foundation](https://github.com/openmobilityfoundation/curb-data-specification)
