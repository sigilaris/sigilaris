#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
REF="${1:-HEAD}"
OUT_DIR="${2:-}"
IGNORE_FILE="${ROOT}/.public-export-ignore"

if [[ -z "${OUT_DIR}" ]]; then
  OUT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/sigilaris-public-export.XXXXXX")"
else
  out_base="$(basename "${OUT_DIR}")"
  case "${out_base}" in
    sigilaris-public-export|sigilaris-public-export.*)
      ;;
    *)
      echo "Refusing unsafe export directory: ${OUT_DIR}" >&2
      exit 1
      ;;
  esac
fi

rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"

git -C "${ROOT}" archive --format=tar "${REF}" | tar -xf - -C "${OUT_DIR}"

while IFS= read -r raw_pattern || [[ -n "${raw_pattern}" ]]; do
  pattern="${raw_pattern#"${raw_pattern%%[![:space:]]*}"}"
  pattern="${pattern%"${pattern##*[![:space:]]}"}"

  if [[ -z "${pattern}" || "${pattern}" == \#* ]]; then
    continue
  fi

  find "${OUT_DIR}" -depth -path "${OUT_DIR}/${pattern}" -exec rm -rf -- {} +
done < "${IGNORE_FILE}"

while IFS= read -r raw_pattern || [[ -n "${raw_pattern}" ]]; do
  pattern="${raw_pattern#"${raw_pattern%%[![:space:]]*}"}"
  pattern="${pattern%"${pattern##*[![:space:]]}"}"

  if [[ -z "${pattern}" || "${pattern}" == \#* ]]; then
    continue
  fi

  if find "${OUT_DIR}" -path "${OUT_DIR}/${pattern}" -print -quit | grep -q .; then
    echo "private path leaked into public export: ${pattern}" >&2
    exit 1
  fi
done < "${IGNORE_FILE}"

printf '%s\n' "${OUT_DIR}"
