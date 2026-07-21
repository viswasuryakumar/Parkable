package com.parkable.datasource;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;

/** Bulk enumeration boundary for government datasets used by the ETL job. */
public interface GovDataFeed {
    Iterator<JsonNode> fetchAll();
}
