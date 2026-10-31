#!/usr/bin/env bash
# A real FTP conversation: passive mode, directory creation, byte-for-byte content, and the failures.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

root="$(mktemp -d)/ftp"; port=$((28000 + RANDOM % 900)); user=ci; pass='s3cret pw'
start() { python3 tools/fake_ftp.py "$port" "$root" "$user" "$pass" "$@" >/dev/null 2>&1 & server=$!
  for _ in $(seq 1 40); do nc -z 127.0.0.1 "$port" 2>/dev/null && break; sleep 0.1; done; }
stop() { kill $server 2>/dev/null; wait $server 2>/dev/null; }
start; trap 'stop' EXIT

run_ftp() { # run_ftp <workspace> <password-secret-json> [key=value ...]
  local dir="$1" secrets="$2"; shift 2
  python3 - "$dir/input.json" "$port" "$@" <<'PY'
import json, sys
path, port = sys.argv[1], sys.argv[2]
doc = {"host": "127.0.0.1", "port": port, "username": "ci", "remoteDir": "site", "sourceFiles": "dist/**/*"}
for pair in sys.argv[3:]:
    key, _, value = pair.partition("=")
    doc[key] = value
json.dump(doc, open(path, "w"))
PY
  printf '%s' "$secrets" >"$dir/.secrets.json"
  run "$dir" >"$dir/atom.log" 2>&1
  echo "$dir"
}

# --- the workspace, with the shapes that usually break a hand-rolled FTP client
ws="$(mktemp -d)"
mkdir -p "$ws/dist/nested" "$ws/private"
printf 'jar-bytes\n' >"$ws/dist/app.jar"
printf 'deep\n' >"$ws/dist/nested/deep.txt"
printf 'spaced\n' >"$ws/dist/a file with spaces.txt"
printf 'zh\n' >"$ws/dist/报告.txt"
printf 'nope\n' >"$ws/private/secret.txt"

dir=$(run_ftp "$ws" '{"password": "s3cret pw"}')
status "$dir" | grep -q success || fail "upload: $(cat "$dir/output.json") $(tail -3 "$dir/atom.log")"
[ "$(output "$dir" filesUploaded)" = "4" ] || fail "file count: $(cat "$dir/output.json")"
cmp -s "$ws/dist/app.jar" "$root/site/dist/app.jar" || fail "app.jar content"
cmp -s "$ws/dist/nested/deep.txt" "$root/site/dist/nested/deep.txt" || fail "the nested path was flattened"
cmp -s "$ws/dist/a file with spaces.txt" "$root/site/dist/a file with spaces.txt" || fail "a name with spaces broke the upload"
cmp -s "$ws/dist/报告.txt" "$root/site/dist/报告.txt" || fail "a UTF-8 name broke the upload"
[ ! -e "$root/site/private/secret.txt" ] || fail "a file outside the pattern must not be sent"

# the runner's scratch, including .secrets.json, must never reach the server
mkdir -p "$ws/.atoms/run9"
printf '{"token": "super-secret"}' >"$ws/.atoms/run9/.secrets.json"
dir=$(run_ftp "$ws" '{"password": "s3cret pw"}' sourceFiles="**/*")
[ ! -e "$root/site/.atoms/run9/.secrets.json" ] || fail "the runner's secrets were uploaded over FTP"
[ -f "$root/site/dist/app.jar" ] || fail "a real file was skipped along with the scratch"

# --- the password must not be in the process arguments curl was given
grep -q -- "--user" "$dir/atom.log" && fail "the command line must not carry --user"
grep -q "s3cret" "$dir/atom.log" && fail "the password ended up in the log"

# --- excluding and narrowing
dir=$(run_ftp "$ws" '{"password": "s3cret pw"}' sourceFiles="dist/*.jar")
[ "$(output "$dir" filesUploaded)" = "1" ] || fail "a narrower pattern: $(cat "$dir/output.json")"
dir=$(run_ftp "$ws" '{"password": "s3cret pw"}' exclude="**/*.txt")
[ "$(output "$dir" filesUploaded)" = "1" ] || fail "exclude: $(cat "$dir/output.json")"

# --- a different target directory is created for us
dir=$(run_ftp "$ws" '{"password": "s3cret pw"}' remoteDir="new/place")
status "$dir" | grep -q success || fail "a fresh directory: $(cat "$dir/output.json")"
[ -f "$root/new/place/dist/app.jar" ] || fail "the directory was not created remotely"

# --- a wrong password is a USER error that says so
dir=$(run_ftp "$ws" '{"password": "wrong"}')
status "$dir" | grep -q error || fail "a wrong password must fail"
grep -q "530" "$dir/output.json" || fail "the FTP reply should surface: $(cat "$dir/output.json")"
grep -q '"errorType":1' "$dir/output.json" || fail "a login failure is USER(1)"

# --- a server that refuses writes
stop; start --read-only
dir=$(run_ftp "$ws" '{"password": "s3cret pw"}')
status "$dir" | grep -q error || fail "a read-only server must fail the upload"
grep -q "550" "$dir/output.json" || fail "the refusal should surface: $(cat "$dir/output.json")"
stop; start

# --- bad input
dir=$(run_ftp "$ws" '{}')
status "$dir" | grep -q error || fail "no password must be refused"
grep -q "password is required" "$dir/output.json" || fail "message: $(cat "$dir/output.json")"
dir=$(run_ftp "$ws" '{"password": "s3cret pw"}' remoteDir="/")
status "$dir" | grep -q error || fail "remoteDir / must be refused"
dir=$(run_ftp "$ws" '{"password": "s3cret pw"}' sourceFiles="nothing/**/*.jar")
status "$dir" | grep -q error || fail "matching nothing must fail"
grep -q "no file matched" "$dir/output.json" || fail "message: $(cat "$dir/output.json")"

echo "smoke ok: real FTP upload, nested/spaced/UTF-8 names, exclude, new directory, wrong password, read-only, bad input"
