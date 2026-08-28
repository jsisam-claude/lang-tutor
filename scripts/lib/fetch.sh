#!/usr/bin/env bash
# Shared download primitives: fetch once, verify always, never half-write.
#
# Every script under scripts/ that pulls a pinned artifact sources this, so the
# rules below hold in one place instead of being re-derived (and re-broken) per
# script. Source it, don't execute it:
#
#   . "$(dirname "${BASH_SOURCE[0]}")/lib/fetch.sh"
#
# The rules, and the bug each one exists to prevent:
#
#   1. SKIP WHAT IS ALREADY RIGHT. A destination whose SHA-256 matches its pin
#      is never re-downloaded. This is the point of the whole file: the models
#      are gigabytes and a re-run should cost nothing.
#
#   2. NEVER RESUME INTO A FILE THAT FAILED ITS HASH. `curl -C -` continues
#      from the existing byte count, so a corrupt or truncated cache entry gets
#      a remote tail appended to a wrong prefix — verified: a 30-byte bad file
#      plus a resumed fetch produced a 7923-byte Frankenstein. It then fails the
#      hash and is deleted, costing a SECOND full download. We delete first.
#
#   3. WRITE ATOMICALLY. Download to `<dest>.part`, verify, then mv. A killed
#      download must never leave something a later run mistakes for the real
#      file, and must never truncate a file that is already correct — which
#      matters doubly here because download-sideload.sh HARD-LINKS cache
#      entries into the device dirs, so writing in place would corrupt copies
#      that were already placed.
#
#   4. CLEAN UP THE PARTIAL. An interrupt leaves no `.part` behind to rot next
#      to the real assets.
#
#   5. AN ABSENT PIN IS AN ERROR, NOT A PASS. "No hash recorded" must never
#      quietly mean "whatever is on disk is fine".

# sha256 of a file, portable across coreutils and BSD/macOS.
sha_of_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

# cached_ok <file> <sha> — true when the file is present AND already correct.
cached_ok() {
  [ -n "${2:-}" ] || return 1
  [ -f "$1" ] || return 1
  [ "$(sha_of_file "$1")" = "$2" ]
}

# fetch_verified <url> <dest> <sha> [label]
#
# Returns 0 having left a verified file at <dest>, or non-zero having left
# <dest> untouched. Prints one line to stderr saying which happened, so a run
# over 28 voices reads as a list of decisions rather than silence.
fetch_verified() {
  local url="$1" dest="$2" sha="$3" label="${4:-$(basename "$2")}"
  local part="$dest.part"

  if [ -z "$sha" ]; then
    echo "!! $label: no SHA-256 pin — refusing to fetch unverified" >&2
    return 1
  fi

  if cached_ok "$dest" "$sha"; then
    echo "   cached   $label" >&2
    return 0
  fi

  # Rule 2: a present-but-wrong file is removed before the download, so curl
  # cannot resume onto it and no stale bytes survive.
  [ -e "$dest" ] && rm -f "$dest"

  mkdir -p "$(dirname "$dest")"
  rm -f "$part"
  echo ">> download $label" >&2
  # --retry-all-errors also covers curl's HTTP/2 PROTOCOL_ERROR (exit 92),
  # which plain --retry does not.
  if ! curl -fsSL --retry 4 --retry-all-errors -o "$part" "$url"; then
    rm -f "$part"
    echo "!! $label: download failed" >&2
    return 1
  fi

  local got
  got="$(sha_of_file "$part")"
  if [ "$got" != "$sha" ]; then
    rm -f "$part"
    echo "!! $label: SHA-256 mismatch — got $got, want $sha" >&2
    return 1
  fi

  mv -f "$part" "$dest"
  echo "   verified $label" >&2
  return 0
}

# Remove any `.part` left by an interrupted run in the given directories.
# Call from a trap so ^C during a 3 GB download leaves nothing behind.
clean_partials() {
  local d
  for d in "$@"; do
    [ -d "$d" ] && find "$d" -name '*.part' -type f -delete 2>/dev/null
  done
  return 0
}
