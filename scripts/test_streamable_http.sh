#!/bin/bash
# Test script for Streamable HTTP MCP transport
# Usage: ./scripts/test_streamable_http.sh [port]

PORT=${1:-19878}
URL="http://localhost:$PORT"

echo "=== Streamable HTTP MCP Test ==="
echo "URL: $URL"
echo ""

# 1. Health check
echo "[1/6] Health check..."
HEALTH=$(curl -sf "$URL/health")
if [ "$HEALTH" = '{"status":"ok"}' ]; then
    echo "  PASS: health endpoint"
else
    echo "  FAIL: health endpoint"
    exit 1
fi

# 2. Initialize
echo "[2/6] Initialize..."
curl -sf -X POST "$URL/mcp" \
    -H "Content-Type: application/json" \
    -D /tmp/mcp_h.txt \
    -d '{"jsonrpc":"2.0","method":"initialize","params":{"protocolVersion":"1.0","clientInfo":{"name":"test"}},"id":1}' \
    > /tmp/mcp_init.json

SESSION=$(grep "Mcp-Session-Id:" /tmp/mcp_h.txt | awk '{print $2}' | tr -d '\r\n')
if [ -n "$SESSION" ]; then
    echo "  PASS: got session $SESSION"
else
    echo "  FAIL: no session ID"
    exit 1
fi

# 3. tools/list
echo "[3/6] tools/list..."
TOOLS=$(curl -sf -X POST "$URL/mcp" \
    -H "Content-Type: application/json" \
    -H "Mcp-Session-Id: $SESSION" \
    -d '{"jsonrpc":"2.0","method":"tools/list","params":{},"id":2}')

TOOL_COUNT=$(echo "$TOOLS" | grep -o '"name":' | wc -l | tr -d ' ')
if [ "$TOOL_COUNT" -ge 10 ]; then
    echo "  PASS: $TOOL_COUNT tools"
else
    echo "  FAIL: only $TOOL_COUNT tools"
    exit 1
fi

# 4. tools/call
echo "[4/6] tools/call (add 5+3)..."
RESULT=$(curl -sf -X POST "$URL/mcp" \
    -H "Content-Type: application/json" \
    -H "Mcp-Session-Id: $SESSION" \
    -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"add","arguments":{"a":5,"b":3}},"id":3}')

if echo "$RESULT" | grep -q '8'; then
    echo "  PASS: add(5,3) = 8"
else
    echo "  FAIL: unexpected result: $RESULT"
    exit 1
fi

# 5. Invalid session rejected
echo "[5/6] Invalid session..."
INVALID=$(curl -s -X POST "$URL/mcp" \
    -H "Content-Type: application/json" \
    -H "Mcp-Session-Id: bad-session" \
    -d '{"jsonrpc":"2.0","method":"tools/list","params":{},"id":4}')

if echo "$INVALID" | grep -q '"error"'; then
    echo "  PASS: invalid session rejected"
else
    echo "  FAIL: invalid session not rejected"
    exit 1
fi

# 6. DELETE session
echo "[6/6] DELETE session..."
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "$URL/mcp" \
    -H "Mcp-Session-Id: $SESSION")

if [ "$HTTP_CODE" = "204" ] || [ "$HTTP_CODE" = "200" ]; then
    echo "  PASS: session deleted (HTTP $HTTP_CODE)"
else
    echo "  FAIL: unexpected HTTP $HTTP_CODE"
    exit 1
fi

# Verify session gone
GONE=$(curl -s -X POST "$URL/mcp" \
    -H "Content-Type: application/json" \
    -H "Mcp-Session-Id: $SESSION" \
    -d '{"jsonrpc":"2.0","method":"tools/list","params":{},"id":5}')

if echo "$GONE" | grep -q '"error"'; then
    echo "  PASS: deleted session rejected"
else
    echo "  FAIL: deleted session still works"
    exit 1
fi

echo ""
echo "=== ALL TESTS PASSED ==="
