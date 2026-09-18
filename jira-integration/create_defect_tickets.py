#!/usr/bin/env python3
"""Files real defect tickets for OrderFlow in a real Atlassian Cloud (JIRA) project.

Uses the real JIRA Cloud REST API v3 (https://developer.atlassian.com/cloud/jira/platform/rest/v3/).
Nothing here is mocked: `--dry-run` prints the exact payloads without calling the API (useful to
sanity-check credentials/config before spending real API calls), and the default mode creates the
tickets for real, then reads them back with a separate GET to prove they exist.

Configuration is entirely via environment variables (see .env.example) - never hard-code a token.

Usage:
    pip install -r requirements.txt
    cp .env.example .env && fill in real values
    python create_defect_tickets.py                 # create + verify both tickets
    python create_defect_tickets.py --dry-run        # print payloads only, no network calls
    python create_defect_tickets.py --only historical
    python create_defect_tickets.py --only new-issue
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import dataclass, field

import requests
from dotenv import load_dotenv

API_VERSION = "3"


@dataclass
class JiraConfig:
    site_url: str
    email: str
    api_token: str
    project_key: str
    preferred_issue_type: str = "Bug"

    @property
    def api_base(self) -> str:
        return f"{self.site_url.rstrip('/')}/rest/api/{API_VERSION}"

    @property
    def auth(self) -> tuple[str, str]:
        return (self.email, self.api_token)


@dataclass
class TicketSpec:
    key: str  # local identifier for --only, not the JIRA key
    summary: str
    description_paragraphs: list[str]
    labels: list[str] = field(default_factory=list)


def load_config() -> JiraConfig:
    load_dotenv()
    missing = [
        name
        for name in ("JIRA_SITE_URL", "JIRA_EMAIL", "JIRA_API_TOKEN", "JIRA_PROJECT_KEY")
        if not os.getenv(name)
    ]
    if missing:
        raise SystemExit(
            "Missing required environment variable(s): "
            + ", ".join(missing)
            + "\nCopy .env.example to .env and fill in real values, "
              "or export them in your shell."
        )
    return JiraConfig(
        site_url=os.environ["JIRA_SITE_URL"],
        email=os.environ["JIRA_EMAIL"],
        api_token=os.environ["JIRA_API_TOKEN"],
        project_key=os.environ["JIRA_PROJECT_KEY"],
        preferred_issue_type=os.getenv("JIRA_ISSUE_TYPE", "Bug"),
    )


def text_to_adf(paragraphs: list[str]) -> dict:
    """Minimal Atlassian Document Format builder: one ADF paragraph per input paragraph,
    with bare `- ` lines rendered as a bullet list. That is all this script's descriptions
    need; it is not a general ADF renderer."""
    content = []
    for para in paragraphs:
        lines = para.strip("\n").split("\n")
        if all(line.strip().startswith("- ") for line in lines if line.strip()):
            items = [
                {
                    "type": "listItem",
                    "content": [{"type": "paragraph", "content": [{"type": "text", "text": line.strip()[2:]}]}],
                }
                for line in lines
                if line.strip()
            ]
            content.append({"type": "bulletList", "content": items})
        else:
            content.append({"type": "paragraph", "content": [{"type": "text", "text": para}]})
    return {"type": "doc", "version": 1, "content": content}


def resolve_issue_type(config: JiraConfig) -> str:
    """Different Jira project templates ship different issue types. Ask the project what it
    actually has and use `preferred_issue_type` if present, else fall back to "Task"."""
    url = f"{config.api_base}/project/{config.project_key}"
    resp = requests.get(url, auth=config.auth, headers={"Accept": "application/json"}, timeout=15)
    resp.raise_for_status()
    issue_types = [t["name"] for t in resp.json().get("issueTypes", [])]
    if config.preferred_issue_type in issue_types:
        return config.preferred_issue_type
    if "Task" in issue_types:
        print(f"  (issue type '{config.preferred_issue_type}' not in project; using 'Task' instead)")
        return "Task"
    if issue_types:
        print(f"  (falling back to first available issue type: {issue_types[0]})")
        return issue_types[0]
    raise RuntimeError(f"project {config.project_key} reports no issue types at all")


def create_issue(config: JiraConfig, issue_type: str, spec: TicketSpec, dry_run: bool) -> dict | None:
    payload = {
        "fields": {
            "project": {"key": config.project_key},
            "issuetype": {"name": issue_type},
            "summary": spec.summary,
            "description": text_to_adf(spec.description_paragraphs),
            "labels": spec.labels,
        }
    }
    url = f"{config.api_base}/issue"

    if dry_run:
        print(f"--- DRY RUN: would POST {url} ---")
        print(json.dumps(payload, indent=2))
        return None

    resp = requests.post(url, auth=config.auth, json=payload, headers={"Accept": "application/json"}, timeout=15)
    if not resp.ok:
        raise RuntimeError(f"POST {url} failed [{resp.status_code}]: {resp.text}")
    return resp.json()


def verify_issue(config: JiraConfig, issue_key: str) -> dict:
    """Fetches the issue back from JIRA - proof the ticket genuinely exists, not just that
    the create call returned 2xx."""
    url = f"{config.api_base}/issue/{issue_key}"
    resp = requests.get(url, auth=config.auth, headers={"Accept": "application/json"}, timeout=15)
    if not resp.ok:
        raise RuntimeError(f"GET {url} failed [{resp.status_code}]: {resp.text}")
    return resp.json()


# --- the two tickets this script files --------------------------------------------------

def historical_oversell_ticket() -> TicketSpec:
    return TicketSpec(
        key="historical",
        summary="[Regression] Concurrent orders could oversell stock under race conditions",
        labels=["orderflow", "concurrency", "already-fixed", "regression-test"],
        description_paragraphs=[
            "Filed for traceability: this is a real historical defect class in OrderFlow's "
            "design history, already fixed, with permanent regression coverage. It is the "
            "central correctness problem the Inventory Service exists to solve.",
            "Component: inventory-service (Reservation / stock-hold logic).",
            "Defect: if stock reservation were implemented as a naive read-then-write "
            "(read available_quantity, check it, write available_quantity - qty as separate "
            "steps), two concurrent requests for the same SKU can both read the same "
            "available_quantity, both pass the check, and both write a decrement - "
            "overselling stock the system does not have. Classic lost-update / TOCTOU race.",
            "Steps to reproduce (would reproduce on a naive implementation; does NOT "
            "reproduce on OrderFlow's current implementation, which is the point):",
            "- Seed an inventory item with available_quantity = 1.\n"
            "- Fire N (e.g. 40-50) concurrent POST /api/v1/reservations requests for 1 unit "
            "of that SKU, each with a distinct reservationId, released simultaneously.\n"
            "- On a naive implementation, more than one request can succeed, taking "
            "available_quantity negative.",
            "Fix actually shipped in OrderFlow: the reserve path never does read-then-write. "
            "InventoryItemRepository.reserve(sku, qty) issues a single conditional SQL "
            "UPDATE (\"UPDATE inventory_item SET available_quantity = available_quantity - "
            ":qty ... WHERE sku = :sku AND available_quantity >= :qty\"), so PostgreSQL's own "
            "row lock serialises concurrent requests for the same row and the guard is always "
            "evaluated against a committed value. A CHECK (available_quantity >= 0) "
            "constraint is a second, database-enforced floor. See "
            "inventory-service/src/main/java/com/orderflow/inventory/repository/"
            "InventoryItemRepository.java and the design write-up in the repo README under "
            "\"The concurrency problem\".",
            "Regression test that verifies the fix: "
            "inventory-service/src/test/java/com/orderflow/inventory/service/"
            "ReservationConcurrencyIT.java, specifically "
            "concurrent_reservations_for_the_last_unit_never_oversell() - fires 40 concurrent "
            "reservation attempts at a SKU with exactly 1 unit in stock against a real "
            "PostgreSQL instance (Testcontainers) and asserts exactly 1 succeeds, the rest "
            "are rejected with 409, and stock never goes negative. Also covered end-to-end "
            "against the full running stack by scripts/e2e-oversell.sh.",
        ],
    )


def new_ui_refresh_race_ticket() -> TicketSpec:
    return TicketSpec(
        key="new-issue",
        summary="Order-rejected error banner is cleared before a user can read it",
        labels=["orderflow", "frontend", "bug", "found-by-selenium-suite"],
        description_paragraphs=[
            "Found while building the Selenium UI regression suite for OrderFlow "
            "(selenium-tests/), specifically while automating the "
            "\"place an order that exceeds available stock\" flow. This is a genuine new "
            "defect, not a test issue: the underlying order rejection logic is correct "
            "(the order is created with status REJECTED and the right reason), but the "
            "banner meant to show that reason to the user cannot be read in practice.",
            "Component: frontend (frontend/src/App.jsx).",
            "Root cause: placeOrder() sets the top-of-page banner to the rejection message "
            "via setError(...), then immediately calls await refresh(). refresh()'s success "
            "path unconditionally calls setError(null) after it re-fetches inventory and "
            "orders - wiping the banner an instant after it was set, before a human can "
            "read it. Even if that race were won, the same unconditional setError(null) "
            "fires again on every subsequent 3-second poll (useEffect -> "
            "setInterval(refresh, 3000)), so the banner cannot persist for more than a few "
            "seconds under any circumstance.",
            "Steps to reproduce:",
            "- Open the storefront.\n"
            "- Set the quantity of any catalogue item's order-qty field far above its "
            "available stock (e.g. 1,000,000).\n"
            "- Click \"Place order\".\n"
            "- Observe: the order correctly appears in the Orders list with status REJECTED "
            "and the correct reason shown on the order card - but the red banner at the top "
            "of the page (intended to surface the same message) is not visibly present; it "
            "is set and cleared within the same refresh cycle.",
            "Impact: low-to-medium. The rejection reason IS available (on the order card, "
            "which is driven by the polled order list rather than the racy error state), so "
            "no data is lost, but the primary, most visible error-reporting mechanism in the "
            "UI does not work as designed for this case.",
            "Suggested fix (not applied as part of this addition, which is scoped to test "
            "automation and defect tracking only, not frontend changes): have refresh() only "
            "clear a pre-existing error when it is refreshing on its own timer, not "
            "immediately after placeOrder() has just set one - e.g. by not calling "
            "setError(null) inside refresh() at all, and instead letting each caller manage "
            "its own error lifecycle, or by giving the polling refresh a "
            "\"don't clobber a fresh error\" grace window.",
            "Regression coverage: selenium-tests/src/test/java/com/orderflow/selenium/"
            "OrderRejectionUiTest.java documents this exact race in its class Javadoc and "
            "asserts on the reliable signal (the order card's persisted rejection reason) "
            "rather than the known-unreliable banner, so the suite does not flake on a bug "
            "outside its scope to fix. Once this ticket is fixed, that test can be extended "
            "to also assert the banner text is visible.",
        ],
    )


def main() -> int:
    try:
        # Windows consoles often default to a legacy code page (cp1252) that can't encode
        # characters JIRA might legitimately return (summaries, etc.); widen it defensively.
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass
    try:
        return _run()
    except requests.exceptions.RequestException as exc:
        print(f"Network/API error talking to JIRA: {exc}", file=sys.stderr)
        return 1
    except RuntimeError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1


def _run() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dry-run", action="store_true", help="print payloads, make no network calls")
    parser.add_argument(
        "--only",
        choices=["historical", "new-issue"],
        help="file only one of the two tickets (default: both)",
    )
    args = parser.parse_args()

    config = load_config()
    specs = [historical_oversell_ticket(), new_ui_refresh_race_ticket()]
    if args.only:
        specs = [s for s in specs if s.key == args.only]

    if args.dry_run:
        issue_type = config.preferred_issue_type
        print(f"(dry run: skipping issue-type auto-detection, assuming '{issue_type}')\n")
    else:
        print(f"Connecting to {config.site_url} as {config.email}, project {config.project_key} ...")
        issue_type = resolve_issue_type(config)
        print(f"Using issue type: {issue_type}\n")

    created_keys: list[str] = []
    for spec in specs:
        print(f"Creating ticket: {spec.summary}")
        result = create_issue(config, issue_type, spec, args.dry_run)
        if result is None:
            continue
        key = result["key"]
        created_keys.append(key)
        print(f"  -> created {key}  ({config.site_url.rstrip('/')}/browse/{key})\n")

    if args.dry_run or not created_keys:
        print("Dry run complete; nothing was created." if args.dry_run else "No tickets were created.")
        return 0

    print("Verifying tickets by reading them back from JIRA (not just trusting the create response):\n")
    all_verified = True
    for key in created_keys:
        try:
            issue = verify_issue(config, key)
        except RuntimeError as exc:
            print(f"  [FAIL] {key}: FAILED TO VERIFY - {exc}")
            all_verified = False
            continue
        fields = issue["fields"]
        print(f"  [OK] {key}: \"{fields['summary']}\" | status={fields['status']['name']} | "
              f"{config.site_url.rstrip('/')}/browse/{key}")

    if not all_verified:
        print("\nOne or more tickets could not be verified - see above.")
        return 1

    print(f"\nAll {len(created_keys)} ticket(s) confirmed to exist in the real JIRA instance: "
          f"{', '.join(created_keys)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
