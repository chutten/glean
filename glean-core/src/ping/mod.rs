// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

use std::fs::{create_dir_all, File};
use std::io::Write;
use std::path::{Path, PathBuf};

use log::info;
use serde_json::{json, Value as JsonValue};

use crate::database::Database;
use crate::storage::StorageManager;

fn merge(a: &mut JsonValue, b: &JsonValue) {
    match (a, b) {
        (&mut JsonValue::Object(ref mut a), &JsonValue::Object(ref b)) => {
            for (k, v) in b {
                merge(a.entry(k.clone()).or_insert(JsonValue::Null), v);
            }
        }
        (a, b) => {
            *a = b.clone();
        }
    }
}

fn get_ping_seq(_storage: &str) -> usize {
    1
}

fn get_ping_info(storage_name: &str) -> JsonValue {
    json!({
        "ping_type": storage_name,
        "seq": get_ping_seq(storage_name),
        "start_time": "2019-03-29T09:50-04:00",
        "end_time": "2019-03-29T09:53-04:00"
    })
}

fn get_client_info(storage: &Database) -> JsonValue {
    let client_info = StorageManager.snapshot_as_json(storage, "glean_client_info", true);
    // Add the "telemetry_sdk_build", which is the glean-core version.
    let version = env!("CARGO_PKG_VERSION");
    let mut map = json!({
        "telemetry_sdk_build": version,
    });

    // Flatten the whole thing.
    let client_info_obj = client_info.as_object().unwrap(); // safe, snapshot always returns an object.
    for (_key, value) in client_info_obj {
        merge(&mut map, value);
    }

    json!(map)
}

pub fn collect(storage: &Database, storage_name: &str) -> JsonValue {
    info!("Collecting {}", storage_name);

    let metrics_data = StorageManager.snapshot_as_json(storage, storage_name, true);

    let ping_info = get_ping_info(storage_name);
    let client_info = get_client_info(storage);

    json!({
        "ping_info": ping_info,
        "client_info": client_info,
        "metrics": metrics_data,
    })
}

pub fn collect_string(storage: &Database, storage_name: &str) -> String {
    let ping = collect(storage, storage_name);
    ::serde_json::to_string_pretty(&ping).unwrap()
}

fn get_pings_dir(data_path: &Path) -> std::io::Result<PathBuf> {
    let pings_dir = data_path.join("pings");
    create_dir_all(&pings_dir)?;
    Ok(pings_dir)
}

/// Store a ping to disk in the pings directory.
pub fn store_ping(
    doc_id: &str,
    data_path: &Path,
    url_path: &str,
    ping_content: &str,
) -> std::io::Result<()> {
    let pings_dir = get_pings_dir(data_path)?;

    // Write to a temporary location and then move when done,
    // for transactional writes.
    let temp_ping_path = std::env::temp_dir().join(doc_id);
    let ping_path = pings_dir.join(doc_id);

    {
        let mut file = File::create(&temp_ping_path)?;
        file.write_all(url_path.as_bytes())?;
        file.write_all(b"\n")?;
        file.write_all(ping_content.as_bytes())?;
    }

    std::fs::rename(temp_ping_path, ping_path)?;

    Ok(())
}
