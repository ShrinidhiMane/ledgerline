#!/usr/bin/env bash
# End-to-end walkthrough against the running stack (docker compose up first).
# Prints each step; exits non-zero if anything is wrong. Used by CI too.
set -euo pipefail

PAY=${PAYMENTS_URL:-http://localhost:8081}
LED=${LEDGER_URL:-http://localhost:8082}
JSON='Content-Type: application/json'

step() { printf '\n== %s\n' "$*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

wait_for() { # url
  for _ in $(seq 1 90); do curl -fsS "$1" >/dev/null 2>&1 && return 0; sleep 2; done
  fail "$1 never became healthy"
}

open_account() { # owner balance
  curl -fsS -X POST "$LED/api/v1/accounts" -H "$JSON" \
    -d "{\"ownerName\":\"$1\",\"currency\":\"USD\",\"openingBalanceMinor\":$2}" | jq -r .id
}

pay() { # key from to amount  -> prints "<http status> <body>"
  curl -sS -o /tmp/pay.json -w '%{http_code}' -X POST "$PAY/api/v1/payments" -H "$JSON" \
    -H "Idempotency-Key: $1" -H "X-Client-Id: e2e" \
    -d "{\"payerAccountId\":\"$2\",\"payeeAccountId\":\"$3\",\"amountMinor\":$4,\"currency\":\"USD\"}"
}

wait_final() { # payment id -> prints final status
  for _ in $(seq 1 60); do
    s=$(curl -fsS "$PAY/api/v1/payments/$1" | jq -r .status)
    [ "$s" != "PENDING" ] && { echo "$s"; return 0; }
    sleep 0.5
  done
  fail "payment $1 stuck in PENDING"
}

balance() { curl -fsS "$LED/api/v1/accounts/$1" | jq -r .balanceMinor; }

step "Waiting for services"
wait_for "$PAY/actuator/health/readiness"
wait_for "$LED/actuator/health/readiness"

step "Open accounts: alice \$100.00, bob \$0.00"
ALICE=$(open_account alice 10000)
BOB=$(open_account bob 0)
echo "alice=$ALICE bob=$BOB"

step "Alice pays Bob \$25.00"
KEY="e2e-$(date +%s%N)"
code=$(pay "$KEY" "$ALICE" "$BOB" 2500); [ "$code" = 202 ] || fail "expected 202, got $code"
PID=$(jq -r .id /tmp/pay.json)
status=$(wait_final "$PID"); echo "payment $PID -> $status"
[ "$status" = COMPLETED ] || fail "expected COMPLETED"
[ "$(balance "$ALICE")" = 7500 ] && [ "$(balance "$BOB")" = 2500 ] || fail "balances wrong"
echo "alice=7500 bob=2500 ✔"

step "Client retries with the same Idempotency-Key: no double charge"
code=$(pay "$KEY" "$ALICE" "$BOB" 2500); [ "$code" = 200 ] || fail "expected 200 replay, got $code"
[ "$(jq -r .id /tmp/pay.json)" = "$PID" ] || fail "replay returned a different payment"
sleep 1
[ "$(balance "$ALICE")" = 7500 ] || fail "retry charged twice"
echo "same payment returned, alice still 7500 ✔"

step "Same key, different amount: rejected"
code=$(pay "$KEY" "$ALICE" "$BOB" 9999); [ "$code" = 422 ] || fail "expected 422, got $code"
echo "422 ✔"

step "Bob tries to pay \$500.00 he doesn't have"
code=$(pay "e2e-nsf-$(date +%s%N)" "$BOB" "$ALICE" 50000); [ "$code" = 202 ] || fail "expected 202, got $code"
NSF=$(jq -r .id /tmp/pay.json)
status=$(wait_final "$NSF"); reason=$(curl -fsS "$PAY/api/v1/payments/$NSF" | jq -r .failureReason)
echo "payment -> $status ($reason)"
[ "$status" = FAILED ] && [ "$reason" = INSUFFICIENT_FUNDS ] || fail "expected FAILED/INSUFFICIENT_FUNDS"
[ "$(balance "$BOB")" = 2500 ] || fail "bob's balance changed"

step "Ledger invariants"
curl -fsS "$LED/api/v1/ledger/verify" | jq .
[ "$(curl -fsS "$LED/api/v1/ledger/verify" | jq -r .consistent)" = true ] || fail "ledger inconsistent"

printf '\nAll end-to-end checks passed.\n'
