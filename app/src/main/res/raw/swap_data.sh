#!/system/bin/sh
# Replaces an app's data folder with a restored copy, keeping the old files as a safety copy.
# usage: swap_data.sh LIVE STAGE UNDO
#   LIVE  the app's real data folder (for example /data/data/org.example.app)
#   STAGE the same folder, restored from the backup into a temporary place (already checked)
#   UNDO  where the app's current files are kept until the next restore
# The folders "cache" and "code_cache" belong to Android and always stay where they are.
# Prints SWAP_OK, or SWAP_FAILED_* after putting everything back as it was.
L="$1"; S="$2"; U="$3"

[ -d "$L" ] && [ -d "$S" ] || { echo SWAP_FAILED_NO_DIR; exit 10; }
mkdir -p "$U" || { echo SWAP_FAILED_UNDO_DIR; exit 11; }

# 1. move the current files aside
for f in "$L"/* "$L"/.[!.]* "$L"/..?*; do
  [ -e "$f" ] || [ -L "$f" ] || continue
  case "${f##*/}" in cache|code_cache) continue ;; esac
  if ! mv -f "$f" "$U"/; then
    for g in "$U"/* "$U"/.[!.]* "$U"/..?*; do
      [ -e "$g" ] || [ -L "$g" ] || continue
      mv -f "$g" "$L"/
    done
    echo SWAP_FAILED_OLD; exit 12
  fi
done

# 2. move the restored files in
for f in "$S"/* "$S"/.[!.]* "$S"/..?*; do
  [ -e "$f" ] || [ -L "$f" ] || continue
  case "${f##*/}" in cache|code_cache) continue ;; esac
  if ! mv -f "$f" "$L"/; then
    # take back what already came in, then put the old files back
    for g in "$L"/* "$L"/.[!.]* "$L"/..?*; do
      [ -e "$g" ] || [ -L "$g" ] || continue
      case "${g##*/}" in cache|code_cache) continue ;; esac
      rm -rf "$g"
    done
    for g in "$U"/* "$U"/.[!.]* "$U"/..?*; do
      [ -e "$g" ] || [ -L "$g" ] || continue
      mv -f "$g" "$L"/
    done
    echo SWAP_FAILED_NEW; exit 13
  fi
done
echo SWAP_OK
