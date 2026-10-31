"""Tiny Bank: a small web app for trying KoraDB by hand.

It keeps accounts and transfers in KoraDB and talks to the server's HTTP API,
using only the Python standard library.

    java -jar kora-server/target/koradb-server.jar --role STANDALONE --data-dir ../bank-data
    python tools/manual-test/tiny_bank.py

Then open http://127.0.0.1:5000.

Options come from the environment: KORA_URL (default http://127.0.0.1:8080),
KORA_PASSWORD (if the server has one) and BANK_PORT (default 5000).
"""

import base64
import html
import json
import os
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

KORA_URL = os.environ.get("KORA_URL", "http://127.0.0.1:8080")
KORA_USER = os.environ.get("KORA_USER", "kora")
KORA_PASSWORD = os.environ.get("KORA_PASSWORD")
BANK_PORT = int(os.environ.get("BANK_PORT", "5000"))
DATABASE = "tiny_bank"


class DbError(Exception):
    pass


def sql(statement, create=False):
    """Runs SQL on KoraDB and returns the parsed JSON response."""
    body = {"database": DATABASE, "sql": statement}
    if create:
        body["create"] = True
    request = urllib.request.Request(
        KORA_URL + "/v1/query",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
    )
    if KORA_PASSWORD:
        token = base64.b64encode(f"{KORA_USER}:{KORA_PASSWORD}".encode()).decode()
        request.add_header("Authorization", "Basic " + token)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as e:
        raise DbError(json.load(e).get("error", str(e))) from e
    except urllib.error.URLError as e:
        raise DbError(f"Cannot reach KoraDB at {KORA_URL}: {e.reason}") from e


def rows(statement):
    return sql(statement).get("rows", [])


def text(value):
    """Quotes a string as a SQL literal."""
    return "'" + value.replace("'", "''") + "'"


def setup():
    sql("CREATE TABLE IF NOT EXISTS accounts (id INT PRIMARY KEY, owner VARCHAR(50) NOT NULL, balance INT NOT NULL)", create=True)
    sql("CREATE TABLE IF NOT EXISTS transfers (id INT PRIMARY KEY, from_id INT NOT NULL, to_id INT NOT NULL, amount INT NOT NULL)")


def next_id(table):
    return (rows(f"SELECT MAX(id) FROM {table}")[0][0] or 0) + 1


def add_account(owner, balance):
    if balance < 0:
        raise DbError("The opening balance can't be negative.")
    sql(f"INSERT INTO accounts (id, owner, balance) VALUES ({next_id('accounts')}, {text(owner)}, {balance})")


def transfer(from_id, to_id, amount):
    if amount <= 0:
        raise DbError("The amount must be more than 0.")
    if from_id == to_id:
        raise DbError("Pick two different accounts.")
    found = rows(f"SELECT balance FROM accounts WHERE id = {from_id}")
    if not found:
        raise DbError(f"Account {from_id} doesn't exist.")
    if found[0][0] < amount:
        raise DbError("Not enough money in that account.")
    if not rows(f"SELECT id FROM accounts WHERE id = {to_id}"):
        raise DbError(f"Account {to_id} doesn't exist.")
    # All three changes are sent as one transaction: either all of them happen or none do.
    sql(
        "BEGIN; "
        f"UPDATE accounts SET balance = balance - {amount} WHERE id = {from_id}; "
        f"UPDATE accounts SET balance = balance + {amount} WHERE id = {to_id}; "
        f"INSERT INTO transfers (id, from_id, to_id, amount) VALUES ({next_id('transfers')}, {from_id}, {to_id}, {amount}); "
        "COMMIT"
    )


def table(columns, data):
    head = "".join(f"<th>{html.escape(c)}</th>" for c in columns)
    body = "".join(
        "<tr>" + "".join(f"<td>{html.escape(str(v))}</td>" for v in row) + "</tr>" for row in data
    )
    return f"<table><tr>{head}</tr>{body}</table>"


def page(message="", failed=False, query="", query_html=""):
    accounts = rows("SELECT id, owner, balance FROM accounts ORDER BY id")
    history = rows(
        "SELECT t.id, a.owner, b.owner, t.amount FROM transfers t "
        "JOIN accounts a ON t.from_id = a.id JOIN accounts b ON t.to_id = b.id "
        "ORDER BY t.id DESC LIMIT 10"
    )
    total = sum(row[2] for row in accounts)
    banner = f'<p class="{"bad" if failed else "good"}">{html.escape(message)}</p>' if message else ""
    return f"""<!doctype html><meta charset="utf-8"><title>Tiny Bank</title>
<style>
 body {{ font: 16px system-ui, sans-serif; max-width: 760px; margin: 2em auto; padding: 0 1em; }}
 table {{ border-collapse: collapse; width: 100%; margin: .5em 0 1.5em; }}
 th, td {{ border: 1px solid #ccc; padding: .35em .6em; text-align: left; }}
 input, textarea {{ font: inherit; padding: .3em; margin: .15em .3em .15em 0; }}
 textarea {{ width: 100%; box-sizing: border-box; font-family: monospace; }}
 .good {{ color: #16691f; }} .bad {{ color: #a61b1b; }}
</style>
<h1>Tiny Bank</h1>{banner}
<h2>Accounts</h2>{table(["ID", "Owner", "Balance"], accounts)}
<p>Total money in the bank: <b>{total}</b></p>
<h2>Open an account</h2>
<form method="post" action="/account">
 <input name="owner" placeholder="Owner" required>
 <input name="balance" type="number" placeholder="Opening balance" required>
 <button>Open</button>
</form>
<h2>Transfer money</h2>
<form method="post" action="/transfer">
 <input name="from_id" type="number" placeholder="From ID" required>
 <input name="to_id" type="number" placeholder="To ID" required>
 <input name="amount" type="number" placeholder="Amount" required>
 <button>Transfer</button>
</form>
<h2>Last 10 transfers</h2>{table(["ID", "From", "To", "Amount"], history)}
<h2>Run your own SQL</h2>
<form method="post" action="/sql">
 <textarea name="query" rows="3" placeholder="SELECT owner, balance FROM accounts WHERE balance > 100">{html.escape(query)}</textarea>
 <button>Run</button>
</form>{query_html}"""


class Handler(BaseHTTPRequestHandler):
    def send_page(self, content, status=200):
        data = content.encode()
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path != "/":
            self.send_error(404)
            return
        try:
            self.send_page(page())
        except DbError as e:
            self.send_page(f"<h1>Tiny Bank</h1><p>{html.escape(str(e))}</p>", 503)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        form = {k: v[0] for k, v in urllib.parse.parse_qs(self.rfile.read(length).decode()).items()}
        message, failed, query, query_html = "", False, "", ""
        try:
            if self.path == "/account":
                add_account(form["owner"], int(form["balance"]))
                message = f"Opened an account for {form['owner']}."
            elif self.path == "/transfer":
                transfer(int(form["from_id"]), int(form["to_id"]), int(form["amount"]))
                message = f"Transferred {form['amount']}."
            elif self.path == "/sql":
                query = form.get("query", "")
                result = sql(query)
                if "columns" in result:
                    query_html = table(result["columns"], result["rows"])
                message = f"Done in {result.get('executionTimeMs', 0)} ms."
                if "affectedRows" in result:
                    message += f" Rows changed: {result['affectedRows']}."
            else:
                self.send_error(404)
                return
        except DbError as e:
            message, failed = str(e), True
        except (KeyError, ValueError):
            message, failed = "Please fill in every field with a valid value.", True
        try:
            self.send_page(page(message, failed, query, query_html))
        except DbError as e:
            self.send_page(f"<h1>Tiny Bank</h1><p>{html.escape(str(e))}</p>", 503)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    setup()
    print(f"Tiny Bank is running: http://127.0.0.1:{BANK_PORT}")
    ThreadingHTTPServer(("127.0.0.1", BANK_PORT), Handler).serve_forever()
