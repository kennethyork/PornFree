import * as SQLite from 'expo-sqlite';

type BlockedRow = { id: number; domain: string; at: number };
type TopRow = { domain: string; hits: number };

let database: Promise<SQLite.SQLiteDatabase> | null = null;

function open(): Promise<SQLite.SQLiteDatabase> {
  if (!database) {
    database = (async () => {
      const db = await SQLite.openDatabaseAsync('pornfree.db');
      await db.execAsync(`
        PRAGMA journal_mode = WAL;
        CREATE TABLE IF NOT EXISTS blocked_events (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          domain TEXT NOT NULL,
          at INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS blocked_events_at ON blocked_events (at);
      `);
      return db;
    })();
  }
  return database;
}

export async function recordBlocked(domains: { domain: string; at: number }[]): Promise<void> {
  if (domains.length === 0) return;
  const db = await open();
  await db.withTransactionAsync(async () => {
    for (const item of domains) {
      await db.runAsync('INSERT INTO blocked_events (domain, at) VALUES (?, ?)', item.domain, item.at);
    }
  });
}

export async function recentBlocked(limit = 40): Promise<BlockedRow[]> {
  const db = await open();
  return db.getAllAsync<BlockedRow>(
    'SELECT id, domain, at FROM blocked_events ORDER BY at DESC LIMIT ?',
    limit
  );
}

export async function topBlocked(since: number, limit = 10): Promise<TopRow[]> {
  const db = await open();
  return db.getAllAsync<TopRow>(
    'SELECT domain, COUNT(*) AS hits FROM blocked_events WHERE at >= ? GROUP BY domain ORDER BY hits DESC LIMIT ?',
    since,
    limit
  );
}

export async function blockedSince(since: number): Promise<number> {
  const db = await open();
  const row = await db.getFirstAsync<{ total: number }>(
    'SELECT COUNT(*) AS total FROM blocked_events WHERE at >= ?',
    since
  );
  return row?.total ?? 0;
}

/** Domain level detail is only kept for a week; the daily totals live in native storage. */
export async function pruneHistory(days = 7): Promise<void> {
  const db = await open();
  await db.runAsync('DELETE FROM blocked_events WHERE at < ?', Date.now() - days * 86_400_000);
}

export async function clearHistory(): Promise<void> {
  const db = await open();
  await db.runAsync('DELETE FROM blocked_events');
}
