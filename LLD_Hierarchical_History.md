# Low-Level Design: Hierarchical History Tracking System

## 1. Overview
This design outlines a system for tracking historical changes across a nested, hierarchical data structure. The core architectural decision is implementing a **"History table per table"** pattern, which isolates historical data for different live entities while maintaining relationships through standard identifiers and JSONB metadata fields.

## 2. Entity Hierarchy
The system maps dependencies and hierarchical relationships stemming from a root `Project` entity. This structure is crucial for tracking which child components are affected by or belong to specific parent history states.

*   **Project** (Root)
    *   **Test Script**
        *   **Screen**
        *   **ORepo** (Object Repository)
        *   **TD** (Test Data) 
            *   *TD2* (Nested/Associated Test Data)
        *   **KW** (Keywords)

## 3. Database Schema Design

### 3.1. Standard History Table Pattern (`<entity_name>_history`)
Each live table in the system will have a corresponding history table. This schema uses `JSONB` columns extensively to store dynamic state and hierarchical context without requiring rigid schema alterations.

| Column Name | Data Type | Description |
| :--- | :--- | :--- |
| `id` | UUID/BIGINT | Primary key for the history record. |
| `live_table_id` | UUID/BIGINT | Foreign key linking to the ID of the actual record in the live table. |
| `live_table_name` | VARCHAR | The name of the table being tracked (e.g., 'test_script', 'screen'). |
| `version` | INT | Sequential version number for the record. |
| `operation` | VARCHAR | The action performed (e.g., `INSERT`, `UPDATE`, `DELETE`). |
| `metadata` | JSONB | Stores entity-specific payload data and operational metadata (see 3.2). |
| `hierarchical_metadata` | JSONB | Stores the state of the entity's position in the hierarchy at the time of creation (e.g., parent/child IDs). |
| `project_id` | UUID/BIGINT | Foreign key associating the change with the root project. |
| `created_by` | VARCHAR/UUID | Identifier for the user or system process that made the change. |
| `created_at` | TIMESTAMP | Timestamp of when the history record was generated. |

### 3.2. Payload Structures (JSONB)

**`metadata` JSONB Structure:**
The diagram specifies that the metadata payload explicitly handles automation routing and target schemas.
```json
{
  "automation": {
    "screen_list": [...],
    "target_table": "table_name",
    "json_payload": { ... }
  },
  "additional_metadata": { ... }
}
```

### 3.3. External Storage Configuration
To manage large payloads (such as deep hierarchical snapshots) efficiently, large data blobs can be offloaded. This configuration defines how external files/snapshots are referenced.

| Field Name | Type | Description |
| :--- | :--- | :--- |
| `storage_type` | ENUM | `local`, `S3` (Indicates where the data is stored). |
| `locator` | VARCHAR | The URI, bucket path, or file path to retrieve the blob. |
| `size_bytes` | BIGINT | The size of the payload. |
| `compression_technique`| VARCHAR | Compression algorithm used (e.g., `gzip`, `snappy`, `none`). |

### 3.4. History Snapshot Record
For taking complete point-in-time snapshots of the entire hierarchy or specific large nodes.

| Column Name | Data Type | Description |
| :--- | :--- | :--- |
| `id` | UUID/BIGINT | Unique snapshot identifier. |
| `content_locator` | VARCHAR | Reference to the storage configuration (S3/local). |
| `size` | BIGINT | Overall size of the snapshot. |
| `project_id` | UUID/BIGINT | Associated project. |
| `created_by` | VARCHAR/UUID | Snapshot initiator. |
| `created_at` | TIMESTAMP | Snapshot generation time. |

## 4. Key Design Considerations
1.  **JSONB Utilization:** Utilizing `JSONB` for `hierarchical_metadata` allows the system to flexibly query historical parent-child relationships (e.g., using PostgreSQL JSON path queries) without requiring complex recursive table joins on historical data.
2.  **Scalability via Offloading:** Storing large hierarchical snapshots in S3 (with compression) while keeping the relational metadata in the database prevents history tables from bloating excessively.
3.  **Auditability:** Every transaction is firmly tied to an `operation`, `created_by`, and `version`, creating a strict, immutable audit log.
