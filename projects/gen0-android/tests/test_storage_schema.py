"""Execute the exported Room storage schema with the host SQLite engine."""

import json
import os
from pathlib import Path
import sqlite3
import unittest


class LocalStorageSchemaTest(unittest.TestCase):
    def setUp(self):
        default = Path(__file__).resolve().parents[1] / "src/data/media/schemas/ai.argvid.gen0.media.db.Gen0Database/1.json"
        self.schema = json.loads(Path(os.environ.get("GEN0_SCHEMA_PATH", default)).read_text())["database"]
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.execute("PRAGMA foreign_keys=ON")
        for entity in self.schema["entities"]:
            self.db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
            for index in entity.get("indices", []):
                self.db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))

    def test_fresh_database_contains_only_local_capture_storage(self):
        tables = {row[0] for row in self.db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        self.assertEqual({"sessions", "moments"}, tables)
        self.assertEqual(1, self.schema["version"])

    def test_moment_metadata_requires_a_session(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute(
                "INSERT INTO moments (id,sessionId,source,qualityTier,mediaUri,durationUs,createdAt,status) "
                "VALUES ('m1','missing','manual_rescue','Proxy','content://media/1',15000000,'2026-08-30','SAVED')"
            )


if __name__ == "__main__":
    unittest.main()
