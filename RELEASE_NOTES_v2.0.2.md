# KoraDB v2.0.2: Safer AI Prompts in the CLI

KoraDB v2.0.2 fixes the CLI's `\ai` command, which could run data-changing SQL with a single press of Enter. It is a drop-in upgrade from v2.0.1: the storage format, protocol and JDBC driver are unchanged.

For everything that came with v2.0, including behavior changes and known limitations, see the [v2.0.1 release notes](https://github.com/Priyanshvaishnav/DatabaseKora/releases/tag/v2.0.1).

---

## Fixes

### `\ai` follows the same safety rules as the AI API

In v2.0.1, `\ai` ran whatever SQL the model returned once you pressed Enter. `\ai delete all customers` deleted every customer, and a wrong or injected answer such as `DROP TABLE` would run too. The read-only default only applied to the Java `ask()` API.

`\ai` now works like `ask()`:

| Generated SQL | What happens |
|---|---|
| `SELECT`, `SHOW`, `DESCRIBE`, `EXPLAIN` | Runs after `Execute query? [Y/n]`, where Enter means yes |
| `INSERT`, `UPDATE`, `DELETE`, `TRUNCATE`, schema changes | Runs only when your request asks for that kind of change and you type `yes`. Enter cancels. |
| A change your request doesn't ask for | Shown, not run. This catches a model answering "how many orders" with `DELETE`. |
| A translation that fails validation, `BEGIN` / `COMMIT` / `ROLLBACK` | Shown, not run |

### `\ai` understands column values

With the offline provider, `\ai` now gives the AI a few values of short text columns, as the API already did. "total amount of pending orders" becomes `WHERE status = 'pending'`; in v2.0.1 it dropped the filter and summed every order. Remote providers don't get these values, because they are table data.

### Documentation
* Fixed broken links in the README's documentation table.

---

## Upgrading

Replace the jars or the image. Your data directory works as it is.

## Testing

617 automated tests pass on a clean build, including new CLI tests for each rule above. The `\ai` changes were also checked by hand against a running server.

---

## Install

```bash
docker run -d -p 8765:8765 -p 8080:8080 -p 9100:9100 \
  -e KORA_PASSWORD=change-me \
  -v koradb_data:/var/lib/koradb/data \
  ghcr.io/priyanshvaishnav/databasekora:2.0.2
```

## Release assets

* `koradb-jdbc.jar`: JDBC driver
* `koradb-server.jar`: standalone server (`java -jar koradb-server.jar`)
* `koradb-cli.jar`: terminal client (`java -jar koradb-cli.jar`)
* `koradb-dist.tar.gz` and `koradb-dist.zip`: full distribution with launchers
* `koradb-v2.0.2-all.tar.gz` and `koradb-v2.0.2-all.zip`: the same distribution, with the version in the file name
* `SHA256SUMS.txt`: checksums for `koradb-dist.tar.gz` and `koradb-dist.zip`

---
**Full Changelog**: https://github.com/Priyanshvaishnav/DatabaseKora/compare/v2.0.1...v2.0.2
